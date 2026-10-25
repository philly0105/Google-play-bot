using System.Buffers.Binary;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;

namespace ClashFarmer.Native;

/** A failure the app is told about by [Code]; see docs/SIDECAR.md. */
internal sealed class SidecarException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

/**
 * The protocol host the app runs as `ClashFarmer.Native.exe serve`. Requests come in on standard
 * input and responses go out on standard output, framed as docs/SIDECAR.md describes. Requests
 * run concurrently; input, capture and OCR each run one at a time.
 */
internal sealed class Server(Stream input, Stream output)
{
    private const string Version = "1";
    private const int MaxBlockBytes = 256 << 20;

    private readonly object _writeLock = new();
    private readonly SemaphoreSlim _inputLane = new(1, 1);
    private readonly SemaphoreSlim _captureLane = new(1, 1);
    private readonly SemaphoreSlim _ocrLane = new(1, 1);
    private WindowCapture? _capture;

    public static void Log(string message) => Console.Error.WriteLine($"{DateTime.Now:HH:mm:ss.fff} {message}");

    public int Run()
    {
        // Gesture steps of 8 ms need a finer timer than the default 15.6 ms.
        Win32.timeBeginPeriod(1);
        PaddleOcr.Warm();
        PcState.WatchDisplay();
        Log($"sidecar {Version} ready, pid {Environment.ProcessId}");
        try
        {
            while (ReadBlock() is { } json)
            {
                var payload = ReadBlock() ?? throw new EndOfStreamException("Input closed between a request and its payload");
                var request = JsonNode.Parse(json)?.AsObject() ?? throw new InvalidDataException("Empty request");
                _ = Task.Run(() => Handle(request, payload));
            }
            Log("input closed, exiting");
            return 0;
        }
        catch (Exception e)
        {
            Log($"stopping on a broken stream: {e.Message}");
            return 2;
        }
        finally
        {
            Input.ReleaseHeld();
        }
    }

    private async Task Handle(JsonObject request, byte[] payload)
    {
        var id = request["id"]?.GetValue<long>() ?? 0;
        var op = request["op"]?.GetValue<string>() ?? "";
        JsonObject response;
        byte[] data = [];
        try
        {
            (response, data) = await Dispatch(op, request, payload);
            response["ok"] = true;
        }
        catch (SidecarException e)
        {
            response = Failure(e.Code, e.Message);
        }
        catch (TimeoutException e)
        {
            response = Failure("timeout", e.Message);
        }
        catch (Exception e)
        {
            Log($"{op} failed: {e}");
            response = Failure("failed", e.Message);
        }
        response["id"] = id;
        try
        {
            Write(response, data);
        }
        catch (IOException e)
        {
            Log($"could not answer {op} {id}: {e.Message}");
        }
    }

    private async Task<(JsonObject, byte[])> Dispatch(string op, JsonObject r, byte[] payload) => op switch
    {
        "hello" => (new JsonObject { ["version"] = Version, ["pid"] = Environment.ProcessId, ["ocrReady"] = PaddleOcr.Ready, ["ocrError"] = PaddleOcr.Error }, []),
        "find" => (Find(), []),
        "capture" => await InLane(_captureLane, () => Capture(r)),
        "gesture" => await InLane(_inputLane, () => Gesture(r)),
        "wheel" => await InLane(_inputLane, () => Done(() => Input.Wheel(Require().Game, Int(r, "x"), Int(r, "y"), Int(r, "notches"), Int(r, "intervalMs", 40)))),
        "key" => await InLane(_inputLane, () => Done(() => Input.Key(Require().Game, (uint)Int(r, "vk")))),
        "ocr" => await InLane(_ocrLane, () => Ocr(r, payload)),
        // A resize moves the surface under any gesture in flight, so it waits its turn with them.
        "resize" => await InLane(_inputLane, () => Resize(r)),
        "launch" => Done(() => Launch(Str(r, "url"))),
        "power" => Power(),
        "keepAwake" => Done(() => SystemState.KeepAwake(Bool(r, "on"))),
        "input" => Activity(),
        "sessionState" => SessionState(),
        "pcInfo" => PcInfo(),
        _ => throw new SidecarException("bad_request", $"Unknown op '{op}'")
    };

    private static async Task<(JsonObject, byte[])> InLane(SemaphoreSlim lane, Func<(JsonObject, byte[])> work)
    {
        await lane.WaitAsync();
        try { return work(); }
        finally { lane.Release(); }
    }

    private static (JsonObject, byte[]) Done(Action action)
    {
        action();
        return (new JsonObject(), []);
    }

    private static GameWindow Require() =>
        GameWindow.Find() ?? throw new SidecarException("no_window", "No Clash of Clans window from Google Play Games");

    private static JsonObject Find()
    {
        var window = GameWindow.Find();
        if (window == null) return new JsonObject { ["found"] = false };
        Win32.GetClientRect(window.Game, out var game);
        var (ownerPid, ownerStartedMs) = PcState.Owner(window.Top);
        var room = Room(window);
        return new JsonObject
        {
            ["found"] = true,
            // The largest game area a resize can reach on this monitor, when it can be measured.
            ["roomWidth"] = room?.Item1,
            ["roomHeight"] = room?.Item2,
            ["top"] = window.Top.ToInt64(),
            ["game"] = window.Game.ToInt64(),
            ["title"] = window.Title,
            ["iconic"] = Win32.IsIconic(window.Top),
            // Closing the window by hand hides it and leaves the game running.
            ["visible"] = Win32.IsWindowVisible(window.Top),
            ["width"] = game.Width,
            ["height"] = game.Height,
            ["foreground"] = Win32.GetForegroundWindow() == window.Top,
            ["monitorDpi"] = Win32.MonitorDpi(window.Top),
            // Google Play Games keeps the game running when the window is closed, and shows it in
            // the same process when it is opened again; a new process means a fresh start.
            ["ownerPid"] = ownerPid,
            ["ownerStartedMs"] = ownerStartedMs,
            ["fullscreen"] = PcState.FullScreen(window.Top)
        };
    }

    private static (JsonObject, byte[]) SessionState()
    {
        var (locked, displayOn) = PcState.Session();
        return (new JsonObject { ["locked"] = locked, ["displayOn"] = displayOn }, []);
    }

    private static (JsonObject, byte[]) PcInfo()
    {
        // A shortcut scan that fails leaves Clash unknown rather than failing the whole answer.
        bool? clash;
        try { clash = PcState.ClashInstalled(); }
        catch (Exception) { clash = null; }
        return (new JsonObject
        {
            ["osBuild"] = PcState.OsBuild(), ["gpgInstalled"] = PcState.GpgInstalled(), ["clashInstalled"] = clash
        }, []);
    }

    private (JsonObject, byte[]) Capture(JsonObject r)
    {
        var window = Require();
        // A window closed by hand sends no frames, and showing it again is not a restart
        // (docs/gpg-spike.md, Restarting).
        if (!Win32.IsWindowVisible(window.Top)) throw new SidecarException("closed", "The Clash window was closed");
        long newerThan = 0;
        if (Win32.IsIconic(window.Top))
        {
            if (!Bool(r, "restore", true)) throw new SidecarException("minimized", "The Clash window is minimized");
            Win32.ShowWindow(window.Top, Win32.SW_SHOWNOACTIVATE);
            // Every frame held from before is from before the minimize.
            newerThan = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 300;
            Log("restored the minimized Clash window without activating it");
        }
        if (_capture == null || _capture.Hwnd != window.Top || _capture.CropTo != window.Game)
        {
            _capture?.Dispose();
            _capture = new WindowCapture(window.Top, window.Game);
        }
        Frame frame;
        try
        {
            frame = _capture.Grab(Int(r, "timeoutMs", 3000), newerThan);
        }
        catch (TimeoutException)
        {
            // A session that stopped sending, after a lost graphics device say, starts over next time.
            _capture.Dispose();
            _capture = null;
            throw;
        }
        // Windows sends a frame only when the window changes, so an old frame is either a game
        // standing still or a locked PC or dark display; the app tells which with sessionState.
        var ageMs = Math.Max(0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - frame.TimestampMs);
        return (new JsonObject
        {
            ["width"] = frame.Width, ["height"] = frame.Height, ["timestampMs"] = frame.TimestampMs, ["ageMs"] = ageMs
        }, frame.Bgra);
    }

    private static (JsonObject, byte[]) Gesture(JsonObject r)
    {
        var strokes = new List<Stroke>();
        foreach (var node in r["strokes"] as JsonArray ?? throw new SidecarException("bad_request", "Missing 'strokes'"))
        {
            var stroke = node as JsonObject ?? throw new SidecarException("bad_request", "A stroke is not an object");
            var (fromX, fromY) = PointOf(stroke, "from");
            var (toX, toY) = PointOf(stroke, "to");
            strokes.Add(new Stroke(fromX, fromY, toX, toY, Int(stroke, "ms")));
        }
        if (strokes.Count == 0) throw new SidecarException("bad_request", "A gesture needs a stroke");
        Input.Gesture(Require().Game, strokes);
        return (new JsonObject(), []);
    }

    private static (JsonObject, byte[]) Ocr(JsonObject r, byte[] payload)
    {
        int width = Int(r, "width"), height = Int(r, "height");
        if (width <= 0 || height <= 0 || payload.Length != width * height * 4)
        {
            throw new SidecarException("bad_request", $"Expected {width}x{height} BGRA, got {payload.Length} bytes");
        }
        var lines = new JsonArray();
        foreach (var line in PaddleOcr.Read(width, height, payload))
        {
            lines.Add(new JsonObject
            {
                ["text"] = line.Text,
                ["box"] = new JsonArray(line.Left, line.Top, line.Right, line.Bottom),
                ["score"] = Math.Round(line.Score, 3)
            });
        }
        return (new JsonObject { ["lines"] = lines }, []);
    }

    /**
     * Sizes the top-level window so the game surface comes out [width] by [height]. Google Play
     * Games keeps the surface at the aspect it chose at launch, inside its own title bar and
     * sidebar, so the chrome is measured and kept, and a second pass corrects what the first
     * missed. A window the new size runs off its monitor is then moved back onto it.
     *
     * A size the monitor's work area can't hold with the chrome around it is shrunk, at the same
     * aspect, to the largest that fits: a window bigger than its screen was pushed to the top left
     * and its bottom rows, where Battle and the troops are, went under the taskbar. The reply
     * gives the size reached, the size asked for, and whether it was capped.
     */
    private static (JsonObject, byte[]) Resize(JsonObject r)
    {
        int requestedWidth = Int(r, "width"), requestedHeight = Int(r, "height");
        var window = Require();
        var (width, height) = (requestedWidth, requestedHeight);
        var room = Room(window);
        if (room.HasValue) (width, height) = FitInside(width, height, room.Value.Item1, room.Value.Item2);
        if (width != requestedWidth || height != requestedHeight)
        {
            Log($"capped the game area at {width}x{height}, not {requestedWidth}x{requestedHeight}, to keep it on its monitor");
        }
        for (var pass = 0; pass < 2; pass++)
        {
            Win32.GetClientRect(window.Game, out var game);
            if (game.Width == width && game.Height == height) break;
            Win32.GetWindowRect(window.Top, out var outer);
            Win32.SetWindowPos(window.Top, IntPtr.Zero, 0, 0, outer.Width + width - game.Width, outer.Height + height - game.Height,
                Win32.SWP_NOMOVE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
            Settle(window.Game);
        }
        KeepOnMonitor(window.Top);
        Win32.GetClientRect(window.Game, out var now);
        return (new JsonObject
        {
            ["width"] = now.Width,
            ["height"] = now.Height,
            ["requestedWidth"] = requestedWidth,
            ["requestedHeight"] = requestedHeight,
            ["capped"] = width != requestedWidth || height != requestedHeight
        }, []);
    }

    /**
     * The largest game surface the work area of [window]'s monitor holds with the window's chrome
     * around it, or null when that can't be measured, as for a minimized window.
     */
    private static (int, int)? Room(GameWindow window)
    {
        var info = new Win32.MONITORINFO { cbSize = Marshal.SizeOf<Win32.MONITORINFO>() };
        if (Win32.IsIconic(window.Top) || !Win32.GetMonitorInfo(Win32.MonitorFromWindow(window.Top, 2), ref info)) return null;
        if (Win32.DwmGetWindowAttribute(window.Top, Win32.DWMWA_EXTENDED_FRAME_BOUNDS, out var seen, Marshal.SizeOf<Win32.RECT>()) != 0) return null;
        Win32.GetClientRect(window.Game, out var game);
        if (game.Width <= 0 || game.Height <= 0) return null;
        return (info.rcWork.Width - (seen.Width - game.Width), info.rcWork.Height - (seen.Height - game.Height));
    }

    /**
     * [width] by [height], or the largest size of its aspect inside [roomWidth] by [roomHeight]
     * when it doesn't fit. PcProfiles.fitInside in the app does the same sum, and its tests cover it.
     */
    private static (int, int) FitInside(int width, int height, int roomWidth, int roomHeight)
    {
        if (width <= roomWidth && height <= roomHeight) return (width, height);
        var scale = Math.Min((double)roomWidth / width, (double)roomHeight / height);
        return ((int)Math.Floor(width * scale), (int)Math.Floor(height * scale));
    }

    /**
     * Moves [top], without activating it, back inside the work area of the monitor it is mostly
     * on wherever its visible frame runs past an edge, and to the work area's top left when it is
     * too big for it. It stays on that monitor: another one may have another DPI, which would
     * change the surface's size in physical pixels.
     */
    private static void KeepOnMonitor(IntPtr top)
    {
        var info = new Win32.MONITORINFO { cbSize = Marshal.SizeOf<Win32.MONITORINFO>() };
        if (Win32.IsIconic(top) || !Win32.GetMonitorInfo(Win32.MonitorFromWindow(top, 2), ref info)) return;
        // The frame bounds leave out the invisible resize borders the window rect includes.
        if (Win32.DwmGetWindowAttribute(top, Win32.DWMWA_EXTENDED_FRAME_BOUNDS, out var seen, Marshal.SizeOf<Win32.RECT>()) != 0) return;
        var work = info.rcWork;
        var dx = Math.Max(work.Left - seen.Left, Math.Min(0, work.Right - seen.Right));
        var dy = Math.Max(work.Top - seen.Top, Math.Min(0, work.Bottom - seen.Bottom));
        if (dx == 0 && dy == 0) return;
        Win32.GetWindowRect(top, out var outer);
        Win32.SetWindowPos(top, IntPtr.Zero, outer.Left + dx, outer.Top + dy, 0, 0,
            Win32.SWP_NOSIZE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
        Log($"moved the Clash window by {dx},{dy} to keep it on its monitor");
    }

    /** Waits, up to 3 s, until the surface has kept one size for 300 ms: Google Play Games lays it out after the window. */
    private static void Settle(IntPtr game)
    {
        var deadline = Environment.TickCount64 + 3000;
        Win32.GetClientRect(game, out var last);
        var steadySince = Environment.TickCount64;
        while (Environment.TickCount64 < deadline && Environment.TickCount64 - steadySince < 300)
        {
            Thread.Sleep(50);
            Win32.GetClientRect(game, out var now);
            if (now.Width == last.Width && now.Height == last.Height) continue;
            last = now;
            steadySince = Environment.TickCount64;
        }
    }

    /** Only Google Play Games launch links: the shell would run anything else it is handed. */
    private static void Launch(string url)
    {
        if (!url.StartsWith("googleplaygames://", StringComparison.Ordinal))
        {
            throw new SidecarException("bad_request", "Only googleplaygames:// links are launched");
        }
        // Without Google Play Games the shell shows its own "how do you want to open this" dialog.
        if (!PcState.GpgInstalled()) throw new SidecarException("not_installed", "Google Play Games is not installed");
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true })?.Dispose();
    }

    private static (JsonObject, byte[]) Power()
    {
        var (ac, percent, saver) = SystemState.Power();
        return (new JsonObject { ["ac"] = ac, ["batteryPercent"] = percent, ["saver"] = saver }, []);
    }

    private static (JsonObject, byte[]) Activity()
    {
        var (idleMs, gameForeground, cursorInGame) = SystemState.Activity();
        return (new JsonObject { ["idleMs"] = idleMs, ["gameForeground"] = gameForeground, ["cursorInGame"] = cursorInGame }, []);
    }

    private static int Int(JsonObject r, string name, int? fallback = null) =>
        r[name] is JsonValue v ? v.GetValue<int>() : fallback ?? throw Missing(name);

    private static bool Bool(JsonObject r, string name, bool? fallback = null) =>
        r[name] is JsonValue v ? v.GetValue<bool>() : fallback ?? throw Missing(name);

    private static string Str(JsonObject r, string name) =>
        r[name] is JsonValue v ? v.GetValue<string>() : throw Missing(name);

    private static (int, int) PointOf(JsonObject r, string name) =>
        r[name] is JsonArray { Count: 2 } p ? (p[0]!.GetValue<int>(), p[1]!.GetValue<int>()) : throw Missing(name);

    private static SidecarException Missing(string name) => new("bad_request", $"Missing '{name}'");

    private static JsonObject Failure(string code, string message) =>
        new() { ["ok"] = false, ["code"] = code, ["error"] = message };

    /** One length-prefixed block, or null when the input ends cleanly before it. */
    private byte[]? ReadBlock()
    {
        var header = new byte[4];
        var got = input.ReadAtLeast(header, 4, throwOnEndOfStream: false);
        if (got == 0) return null;
        if (got < 4) throw new EndOfStreamException("Input closed inside a length");
        var length = BinaryPrimitives.ReadInt32LittleEndian(header);
        if (length < 0 || length > MaxBlockBytes) throw new InvalidDataException($"A block of {length} bytes");
        var block = new byte[length];
        input.ReadExactly(block);
        return block;
    }

    private void Write(JsonObject response, byte[] payload)
    {
        var json = Encoding.UTF8.GetBytes(response.ToJsonString());
        var length = new byte[4];
        lock (_writeLock)
        {
            BinaryPrimitives.WriteInt32LittleEndian(length, json.Length);
            output.Write(length);
            output.Write(json);
            BinaryPrimitives.WriteInt32LittleEndian(length, payload.Length);
            output.Write(length);
            output.Write(payload);
            output.Flush();
        }
    }
}
