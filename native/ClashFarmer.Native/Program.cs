using System.Globalization;
using ClashFarmer.Native;

// `serve` is the sidecar the app runs (docs/SIDECAR.md). The other commands are the probe used for
// docs/gpg-spike.md.
var command = args.Length > 0 ? args[0] : "help";
var rest = args.Skip(1).ToArray();

IntPtr Hwnd(string s) => (IntPtr)long.Parse(s.StartsWith("0x") ? s[2..] : s, s.StartsWith("0x") ? NumberStyles.HexNumber : NumberStyles.Integer);
int I(string s) => int.Parse(s, CultureInfo.InvariantCulture);

void Describe(IntPtr h, string indent)
{
    Win32.GetWindowRect(h, out var w);
    var c = Win32.ClientOnScreen(h);
    Console.WriteLine($"{indent}0x{h.ToInt64():X} pid={Win32.Pid(h)} proc={Win32.ProcessName(h)} class='{Win32.Class(h)}' title='{Win32.Text(h)}' " +
                      $"visible={Win32.IsWindowVisible(h)} iconic={Win32.IsIconic(h)} dpi={Win32.GetDpiForWindow(h)} awareness={Win32.Awareness(h)} " +
                      $"monitorDpi={Win32.MonitorDpi(h)} window={w} frame={Win32.FrameBounds(h)} client={c} " +
                      $"style=0x{Win32.GetWindowLong(h, Win32.GWL_STYLE):X8} ex=0x{Win32.GetWindowLong(h, Win32.GWL_EXSTYLE):X8}");
}

switch (command)
{
    case "serve":
    {
        // Standard output carries protocol frames only, so anything written to the console goes to standard error.
        var output = Console.OpenStandardOutput();
        Console.SetOut(Console.Error);
        Environment.ExitCode = new Server(Console.OpenStandardInput(), output).Run();
        break;
    }
    case "list":
    {
        // Every visible top-level window whose process or title matches the filter, with its children.
        var filter = rest.Length > 0 ? rest[0].ToLowerInvariant() : "";
        Win32.EnumWindows((h, _) =>
        {
            if (!Win32.IsWindowVisible(h)) return true;
            var text = (Win32.Text(h) + " " + Win32.ProcessName(h) + " " + Win32.Class(h)).ToLowerInvariant();
            if (filter.Length > 0 && !text.Contains(filter)) return true;
            Describe(h, "");
            Win32.EnumChildWindows(h, (child, _) => { Describe(child, "    "); return true; }, IntPtr.Zero);
            return true;
        }, IntPtr.Zero);
        break;
    }
    case "capture":
    {
        // capture <hwnd> <out.png> <wgc|print|screen> [crop to this child hwnd]
        var hwnd = Hwnd(rest[0]);
        var method = rest.Length > 2 ? rest[2] : "wgc";
        var cropTo = rest.Length > 3 ? Hwnd(rest[3]) : IntPtr.Zero;
        var started = Environment.TickCount64;
        Frame frame;
        if (method == "wgc")
        {
            using var capture = new WindowCapture(hwnd, cropTo);
            frame = capture.Grab();
            var first = Environment.TickCount64 - started;
            // A second grab on the same session shows the steady-state cost.
            var again = Environment.TickCount64;
            frame = capture.Grab();
            Console.WriteLine($"wgc first={first}ms second={Environment.TickCount64 - again}ms");
        }
        else if (method == "print") frame = GdiCapture.PrintWindowClient(hwnd);
        else frame = GdiCapture.ScreenClient(hwnd);
        File.WriteAllBytes(rest[1], Png.Encode(frame));
        long sum = 0;
        for (var i = 0; i < frame.Bgra.Length; i += 4 * 97) sum += frame.Bgra[i] + frame.Bgra[i + 1] + frame.Bgra[i + 2];
        Console.WriteLine($"{method}: {frame.Width}x{frame.Height} in {Environment.TickCount64 - started}ms, mean sampled level {sum / Math.Max(1, frame.Bgra.Length / (4 * 97)) / 3}");
        break;
    }
    case "click":
    {
        // click <hwnd> <x> <y> [holdMs] : posted messages, client coordinates
        var hwnd = Hwnd(rest[0]);
        int x = I(rest[1]), y = I(rest[2]);
        var hold = rest.Length > 3 ? I(rest[3]) : 60;
        var lp = Win32.MakeLParam(x, y);
        Win32.PostMessage(hwnd, Win32.WM_MOUSEMOVE, IntPtr.Zero, lp);
        Win32.PostMessage(hwnd, Win32.WM_LBUTTONDOWN, (IntPtr)Win32.MK_LBUTTON, lp);
        Thread.Sleep(hold);
        Win32.PostMessage(hwnd, Win32.WM_LBUTTONUP, IntPtr.Zero, lp);
        Console.WriteLine($"posted click at {x},{y} hold {hold}ms; foreground=0x{Win32.GetForegroundWindow().ToInt64():X}");
        break;
    }
    case "drag":
    {
        // drag <hwnd> <x1> <y1> <x2> <y2> <durationMs> [holdEndMs]
        var hwnd = Hwnd(rest[0]);
        int x1 = I(rest[1]), y1 = I(rest[2]), x2 = I(rest[3]), y2 = I(rest[4]), dur = I(rest[5]);
        var holdEnd = rest.Length > 6 ? I(rest[6]) : 0;
        var steps = Math.Max(2, dur / 10);
        Win32.PostMessage(hwnd, Win32.WM_MOUSEMOVE, IntPtr.Zero, Win32.MakeLParam(x1, y1));
        Win32.PostMessage(hwnd, Win32.WM_LBUTTONDOWN, (IntPtr)Win32.MK_LBUTTON, Win32.MakeLParam(x1, y1));
        for (var i = 1; i <= steps; i++)
        {
            Thread.Sleep(dur / steps);
            var x = x1 + (x2 - x1) * i / steps;
            var y = y1 + (y2 - y1) * i / steps;
            Win32.PostMessage(hwnd, Win32.WM_MOUSEMOVE, (IntPtr)Win32.MK_LBUTTON, Win32.MakeLParam(x, y));
        }
        if (holdEnd > 0) Thread.Sleep(holdEnd);
        Win32.PostMessage(hwnd, Win32.WM_LBUTTONUP, IntPtr.Zero, Win32.MakeLParam(x2, y2));
        Console.WriteLine($"posted drag {x1},{y1} -> {x2},{y2} over {dur}ms");
        break;
    }
    case "wheel":
    {
        // wheel <hwnd> <x> <y> <delta> [ctrl] : delta in notches (+ away from user)
        var hwnd = Hwnd(rest[0]);
        int x = I(rest[1]), y = I(rest[2]), notches = I(rest[3]);
        var ctrl = rest.Length > 4 && rest[4] == "ctrl";
        var origin = new Win32.POINT();
        Win32.ClientToScreen(hwnd, ref origin);
        var screenLp = Win32.MakeLParam(origin.X + x, origin.Y + y);
        var step = Math.Sign(notches);
        for (var i = 0; i < Math.Abs(notches); i++)
        {
            var keys = ctrl ? Win32.MK_CONTROL : 0;
            var wp = (IntPtr)(((120 * step) << 16) | (keys & 0xFFFF));
            Win32.PostMessage(hwnd, Win32.WM_MOUSEWHEEL, wp, screenLp);
            Thread.Sleep(40);
        }
        Console.WriteLine($"posted {notches} wheel notches at {x},{y} ctrl={ctrl}");
        break;
    }
    case "key":
    {
        // key <hwnd> <vk hex>
        var hwnd = Hwnd(rest[0]);
        var vk = Convert.ToUInt32(rest[1], 16);
        var scan = Win32.MapVirtualKey(vk, 0);
        Win32.PostMessage(hwnd, Win32.WM_KEYDOWN, (IntPtr)vk, (IntPtr)(1 | (scan << 16)));
        Thread.Sleep(50);
        Win32.PostMessage(hwnd, Win32.WM_KEYUP, (IntPtr)vk, (IntPtr)(1 | (scan << 16) | (1u << 30) | (1u << 31)));
        Console.WriteLine($"posted key 0x{vk:X}");
        break;
    }
    case "setclient":
    {
        // setclient <hwnd> <w> <h> : resize so the client area is w x h, without activating
        var hwnd = Hwnd(rest[0]);
        int w = I(rest[1]), h = I(rest[2]);
        Win32.GetWindowRect(hwnd, out var outer);
        Win32.GetClientRect(hwnd, out var client);
        var ok = Win32.SetWindowPos(hwnd, IntPtr.Zero, 0, 0, outer.Width + (w - client.Width), outer.Height + (h - client.Height),
            Win32.SWP_NOMOVE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
        Thread.Sleep(500);
        Win32.GetClientRect(hwnd, out client);
        Console.WriteLine($"setclient ok={ok} now client {client.Width}x{client.Height}");
        break;
    }
    case "setwindow":
    {
        // setwindow <hwnd> <w> <h> : resize the outer window, without activating
        var hwnd = Hwnd(rest[0]);
        var ok = Win32.SetWindowPos(hwnd, IntPtr.Zero, 0, 0, I(rest[1]), I(rest[2]), Win32.SWP_NOMOVE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
        Thread.Sleep(800);
        Console.WriteLine($"setwindow ok={ok}");
        Describe(hwnd, "");
        Win32.EnumChildWindows(hwnd, (child, _) => { Describe(child, "    "); return true; }, IntPtr.Zero);
        break;
    }
    case "show":
    {
        // show <hwnd> <SW_ number> : 6 minimize, 4 show without activating, 9 restore
        var hwnd = Hwnd(rest[0]);
        Win32.ShowWindow(hwnd, I(rest[1]));
        Thread.Sleep(500);
        Console.WriteLine($"show {rest[1]}; iconic={Win32.IsIconic(hwnd)} foreground=0x{Win32.GetForegroundWindow().ToInt64():X}");
        break;
    }
    case "ocr":
    {
        // ocr <image file> [scale] [raw|white|gray] [save.png] : boxes are reported in the original's pixels
        var frame = Png.Decode(File.ReadAllBytes(rest[0]));
        var scale = rest.Length > 1 ? I(rest[1]) : 1;
        var mode = rest.Length > 2 ? rest[2] : "raw";
        var started = Environment.TickCount64;
        var prepared = Preprocess.Run(frame, scale, mode);
        var prepMs = Environment.TickCount64 - started;
        if (rest.Length > 3) File.WriteAllBytes(rest[3], Png.Encode(prepared));
        var lines = WindowsOcr.Recognize(prepared);
        Console.WriteLine($"ocr {WindowsOcr.Language} {prepared.Width}x{prepared.Height} {mode}: {lines.Count} lines, prep {prepMs}ms, total {Environment.TickCount64 - started}ms");
        foreach (var l in lines) Console.WriteLine($"  [{l.Left / scale},{l.Top / scale},{l.Right / scale},{l.Bottom / scale}] {l.Text}");
        break;
    }
    case "cover":
    {
        // cover <top hwnd> <game hwnd> <x> <y> : cover the game with a topmost window that never
        // activates, then screen-grab, WGC-grab and click it at x,y while covered
        IntPtr top = Hwnd(rest[0]), game = Hwnd(rest[1]);
        Win32.GetWindowRect(game, out var r);
        // WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE; WS_POPUP | WS_VISIBLE | SS_BLACKRECT
        var cover = Win32.CreateWindowEx(0x00000008 | 0x00000080 | 0x08000000, "STATIC", "cover", 0x80000000 | 0x10000000 | 0x4,
            r.Left, r.Top, r.Width, r.Height, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
        try
        {
            Win32.Pump(700);
            Console.WriteLine($"cover 0x{cover.ToInt64():X} over {r}; foreground=0x{Win32.GetForegroundWindow().ToInt64():X}");
            File.WriteAllBytes("audit-evidence/spike/covered-screen.png", Png.Encode(GdiCapture.ScreenClient(game)));
            using (var capture = new WindowCapture(top, game))
                File.WriteAllBytes("audit-evidence/spike/covered-wgc-before.png", Png.Encode(capture.Grab()));
            var lp = Win32.MakeLParam(I(rest[2]), I(rest[3]));
            Win32.PostMessage(game, Win32.WM_MOUSEMOVE, IntPtr.Zero, lp);
            Win32.PostMessage(game, Win32.WM_LBUTTONDOWN, (IntPtr)Win32.MK_LBUTTON, lp);
            Thread.Sleep(80);
            Win32.PostMessage(game, Win32.WM_LBUTTONUP, IntPtr.Zero, lp);
            Win32.Pump(2000);
            using (var capture = new WindowCapture(top, game))
                File.WriteAllBytes("audit-evidence/spike/covered-wgc-after.png", Png.Encode(capture.Grab()));
            Console.WriteLine($"clicked {rest[2]},{rest[3]} while covered; foreground=0x{Win32.GetForegroundWindow().ToInt64():X}");
        }
        finally { Win32.DestroyWindow(cover); }
        break;
    }
    case "ocr2":
    {
        // ocr2 <image file> [threads] [passes] [angle] : PaddleOCR PP-OCRv5 through RapidOcrNet
        var threads = rest.Length > 1 ? I(rest[1]) : 0;
        var passes = rest.Length > 2 ? I(rest[2]) : 2;
        var options = RapidOcrNet.RapidOcrOptions.Default with { DoAngle = rest.Length > 3 && rest[3] == "angle" };
        using var ocr = new RapidOcrNet.RapidOcr();
        var loadStarted = Environment.TickCount64;
        ocr.InitModels(threads);
        Console.WriteLine($"models loaded in {Environment.TickCount64 - loadStarted}ms, threads={threads} angle={options.DoAngle}");
        using var bitmap = SkiaSharp.SKBitmap.Decode(rest[0]);
        RapidOcrNet.OcrResult? result = null;
        for (var pass = 0; pass < passes; pass++)
        {
            var started = Environment.TickCount64;
            result = ocr.Detect(bitmap, options);
            Console.WriteLine($"pass {pass}: {result.TextBlocks.Length} lines in {Environment.TickCount64 - started}ms");
        }
        foreach (var block in result!.TextBlocks)
        {
            var p = block.BoxPoints;
            int l = (int)p.Min(q => q.X), t = (int)p.Min(q => q.Y), r = (int)p.Max(q => q.X), b = (int)p.Max(q => q.Y);
            Console.WriteLine($"  [{l},{t},{r},{b}] {block.Text}  ({block.CharScores!.DefaultIfEmpty(0).Average():F2})");
        }
        break;
    }
    default:
        Console.WriteLine("commands: serve | list [filter] | capture <hwnd> <out.png> [wgc|print|screen] | click <hwnd> x y [holdMs] | " +
                          "drag <hwnd> x1 y1 x2 y2 ms [holdEndMs] | wheel <hwnd> x y notches [ctrl] | key <hwnd> vkHex | setclient <hwnd> w h | ocr <file>");
        break;
}
