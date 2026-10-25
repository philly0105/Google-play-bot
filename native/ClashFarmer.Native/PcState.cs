using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace ClashFarmer.Native;

/**
 * What the app asks about the PC around the game: whether the session is locked or the display
 * off (Windows Graphics Capture stops sending frames then), whether Google Play Games and Clash
 * are installed, the Windows build, the process behind the Clash window, and full screen.
 */
internal static class PcState
{
    private const int WTS_CURRENT_SESSION = -1;
    private const int WTSSessionInfoEx = 25;
    private const int WTS_SESSIONSTATE_LOCK = 0;
    private const int WTS_SESSIONSTATE_UNLOCK = 1;

    private const uint DEVICE_NOTIFY_CALLBACK = 2;
    private const uint PBT_POWERSETTINGCHANGE = 0x8013;
    private static readonly Guid GUID_CONSOLE_DISPLAY_STATE = new("6FE69556-704A-47A0-8F24-C28D936FDA47");

    /** The launch id inside Google Play Games' Start menu shortcut for Clash. */
    private const string ClashLaunchId = "id=com.supercell.clashofclans";

    private static readonly object Gate = new();
    private static IntPtr _systemRequest;
    private static bool _systemAwake;

    /** 0 off, 1 on, 2 dimmed, -1 not told yet. */
    private static volatile int _display = -1;
    private static bool _displayRegistered;
    private static DeviceNotifyCallback? _displayCallback;

    /**
     * Whether the session is locked (null when Windows doesn't say) and whether the display is on
     * (null until Windows has told). Either one stops the frames Windows Graphics Capture sends.
     */
    public static (bool? Locked, bool? DisplayOn) Session()
    {
        WatchDisplay();
        var display = _display;
        return (Locked(), display < 0 ? null : display != 0);
    }

    private static bool? Locked()
    {
        if (!WTSQuerySessionInformation(IntPtr.Zero, WTS_CURRENT_SESSION, WTSSessionInfoEx, out var buffer, out _)) return null;
        try
        {
            var info = Marshal.PtrToStructure<WTSINFOEX>(buffer);
            if (info.Level != 1) return null;
            return info.Data.SessionFlags switch
            {
                WTS_SESSIONSTATE_LOCK => true,
                WTS_SESSIONSTATE_UNLOCK => false,
                _ => null
            };
        }
        finally
        {
            WTSFreeMemory(buffer);
        }
    }

    /**
     * Asks Windows, once, to say when the display turns on or off. It answers at once with the
     * current state, on a thread of its own; a callback needs no window or message loop.
     */
    public static void WatchDisplay()
    {
        lock (Gate)
        {
            if (_displayRegistered) return;
            _displayRegistered = true;
            _displayCallback = OnDisplayChanged;
            // Windows reads the parameters for as long as the registration lasts, which is the process's life.
            var parameters = Marshal.AllocHGlobal(Marshal.SizeOf<DEVICE_NOTIFY_SUBSCRIBE_PARAMETERS>());
            Marshal.StructureToPtr(new DEVICE_NOTIFY_SUBSCRIBE_PARAMETERS
            {
                Callback = Marshal.GetFunctionPointerForDelegate(_displayCallback), Context = IntPtr.Zero
            }, parameters, false);
            var setting = GUID_CONSOLE_DISPLAY_STATE;
            var error = PowerSettingRegisterNotification(ref setting, DEVICE_NOTIFY_CALLBACK, parameters, out _);
            if (error != 0) Server.Log($"can't watch the display state: error {error}");
        }
    }

    private static uint OnDisplayChanged(IntPtr context, uint type, IntPtr setting)
    {
        // POWERBROADCAST_SETTING: the setting's GUID, a DWORD length, then the value.
        if (type == PBT_POWERSETTINGCHANGE && setting != IntPtr.Zero && Marshal.ReadInt32(setting, 16) >= 4)
        {
            _display = Marshal.ReadInt32(setting, 20);
        }
        return 0;
    }

    /**
     * Holds the system awake, not the display, while [on]: for a start set for later, which the
     * PC's idle sleep would otherwise miss. Its own power request, apart from the run's.
     */
    public static void KeepSystemAwake(bool on)
    {
        lock (Gate)
        {
            if (on == _systemAwake) return;
            if (_systemRequest == IntPtr.Zero)
            {
                // POWER_REQUEST_CONTEXT_VERSION, POWER_REQUEST_CONTEXT_SIMPLE_STRING
                var context = new Win32.REASON_CONTEXT { Version = 0, Flags = 1, SimpleReasonString = "Clash Farmer has a start set for later" };
                var request = Win32.PowerCreateRequest(ref context);
                if (request == IntPtr.Zero || request == new IntPtr(-1)) throw new InvalidOperationException($"PowerCreateRequest failed: {Marshal.GetLastWin32Error()}");
                _systemRequest = request;
            }
            if (on) Win32.PowerSetRequest(_systemRequest, Win32.PowerRequestSystemRequired);
            else Win32.PowerClearRequest(_systemRequest, Win32.PowerRequestSystemRequired);
            _systemAwake = on;
        }
    }

    /** The Windows build number: 22000 and later is Windows 11. */
    public static int OsBuild() => Environment.OSVersion.Version.Build;

    /** Google Play Games registers its launch links; without it nothing opens them. */
    public static bool GpgInstalled()
    {
        using var command = Registry.ClassesRoot.OpenSubKey(@"googleplaygames\shell\open\command");
        return command != null;
    }

    /**
     * Google Play Games puts a Start menu shortcut for each game it installs, under
     * "Google Play Games", that opens the game's launch link. Any shortcut that names Clash's
     * counts, wherever it sits and whatever it is called.
     */
    public static bool ClashInstalled()
    {
        var checkedFiles = 0;
        foreach (var root in new[] { Environment.SpecialFolder.Programs, Environment.SpecialFolder.CommonPrograms })
        {
            var dir = Environment.GetFolderPath(root);
            if (dir.Length == 0 || !Directory.Exists(dir)) continue;
            IEnumerable<string> shortcuts;
            try { shortcuts = Directory.EnumerateFiles(dir, "*.lnk", SearchOption.AllDirectories); }
            catch (Exception) { continue; }
            foreach (var path in shortcuts)
            {
                if (++checkedFiles > 2000) return false;
                try
                {
                    var info = new FileInfo(path);
                    if (info.Length > 65536) continue;
                    if (Encoding.Unicode.GetString(File.ReadAllBytes(path)).Contains(ClashLaunchId, StringComparison.Ordinal)) return true;
                }
                catch (Exception)
                {
                    // A shortcut that can't be read is not Clash's.
                }
            }
        }
        return false;
    }

    /** The process that owns [top] and when it started (Unix ms), or nulls when it can't be read. */
    public static (long? Pid, long? StartedMs) Owner(IntPtr top)
    {
        var pid = Win32.Pid(top);
        if (pid == 0) return (null, null);
        try
        {
            using var process = Process.GetProcessById((int)pid);
            return (pid, new DateTimeOffset(process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds());
        }
        catch (Exception)
        {
            return (pid, null);
        }
    }

    /**
     * Whether [top] covers its whole monitor, taskbar included, which is what Google Play Games'
     * full screen does. A maximized window stops at the taskbar.
     */
    public static bool FullScreen(IntPtr top)
    {
        if (Win32.IsIconic(top) || !Win32.IsWindowVisible(top)) return false;
        var info = new Win32.MONITORINFO { cbSize = Marshal.SizeOf<Win32.MONITORINFO>() };
        if (!Win32.GetMonitorInfo(Win32.MonitorFromWindow(top, 2), ref info)) return false;
        var seen = Win32.FrameBounds(top);
        var screen = info.rcMonitor;
        return seen.Left <= screen.Left && seen.Top <= screen.Top && seen.Right >= screen.Right && seen.Bottom >= screen.Bottom;
    }

    private delegate uint DeviceNotifyCallback(IntPtr context, uint type, IntPtr setting);

    [StructLayout(LayoutKind.Sequential)]
    private struct DEVICE_NOTIFY_SUBSCRIBE_PARAMETERS
    {
        public IntPtr Callback;
        public IntPtr Context;
    }

    /** WTSINFOEX_LEVEL1_W. The 64-bit times align it to 8 bytes, so it starts 8 bytes into WTSINFOEXW. */
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WTSINFOEX_LEVEL1
    {
        public int SessionId;
        public int SessionState;
        public int SessionFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 33)] public string WinStationName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 21)] public string UserName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 18)] public string DomainName;
        public long LogonTime, ConnectTime, DisconnectTime, LastInputTime, CurrentTime;
        public int IncomingBytes, OutgoingBytes, IncomingFrames, OutgoingFrames, IncomingCompressedBytes, OutgoingCompressedBytes;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WTSINFOEX
    {
        public int Level;
        public WTSINFOEX_LEVEL1 Data;
    }

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);

    [DllImport("wtsapi32.dll")] private static extern void WTSFreeMemory(IntPtr memory);

    [DllImport("powrprof.dll")]
    private static extern uint PowerSettingRegisterNotification(ref Guid setting, uint flags, IntPtr recipient, out IntPtr handle);
}
