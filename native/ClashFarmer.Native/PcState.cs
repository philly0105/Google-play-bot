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
    private const int WS_CAPTION = 0x00C00000;

    private const uint DEVICE_NOTIFY_CALLBACK = 2;
    private const uint PBT_POWERSETTINGCHANGE = 0x8013;
    private static readonly Guid GUID_CONSOLE_DISPLAY_STATE = new("6FE69556-704A-47A0-8F24-C28D936FDA47");

    /** The launch id inside Google Play Games' Start menu shortcut for Clash. */
    private const string ClashLaunchId = "id=com.supercell.clashofclans";

    private static readonly object Gate = new();

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

    /**
     * WTSINFOEXW holds Level, then WTSINFOEX_LEVEL1_W from byte 8 (its 64-bit times align it), whose
     * SessionFlags is its third int. Only those two ints are read, so nothing is read past the
     * buffer Windows returns.
     */
    private static bool? Locked()
    {
        if (!WTSQuerySessionInformationW(IntPtr.Zero, WTS_CURRENT_SESSION, WTSSessionInfoEx, out var buffer, out var bytes)) return null;
        try
        {
            if (bytes < 20 || Marshal.ReadInt32(buffer, 0) != 1) return null;
            return Marshal.ReadInt32(buffer, 16) switch
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
     * counts, wherever it sits and whatever it is called. Folders it may not read are skipped.
     * Null, not known, when there are too many shortcuts to look through.
     */
    public static bool? ClashInstalled()
    {
        var checkedFiles = 0;
        var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true };
        foreach (var root in new[] { Environment.SpecialFolder.Programs, Environment.SpecialFolder.CommonPrograms })
        {
            var dir = Environment.GetFolderPath(root);
            if (dir.Length == 0 || !Directory.Exists(dir)) continue;
            IEnumerable<string> shortcuts;
            try { shortcuts = Directory.EnumerateFiles(dir, "*.lnk", options); }
            catch (Exception) { continue; }
            foreach (var path in shortcuts)
            {
                if (++checkedFiles > 2000) return null;
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
     * Whether [top] covers its whole monitor, taskbar included, with no title bar and not
     * maximized, which is what Google Play Games' full screen does. A maximized window stops at
     * the taskbar, unless the taskbar hides itself, so a maximized window or one with a title bar
     * never counts.
     */
    public static bool FullScreen(IntPtr top)
    {
        if (Win32.IsIconic(top) || !Win32.IsWindowVisible(top) || Win32.IsZoomed(top)) return false;
        if ((Win32.GetWindowLong(top, Win32.GWL_STYLE) & WS_CAPTION) == WS_CAPTION) return false;
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

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQuerySessionInformationW(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);

    [DllImport("wtsapi32.dll")] private static extern void WTSFreeMemory(IntPtr memory);

    [DllImport("powrprof.dll")]
    private static extern uint PowerSettingRegisterNotification(ref Guid setting, uint flags, IntPtr recipient, out IntPtr handle);
}
