using System.Runtime.InteropServices;

namespace ClashFarmer.Native;

/** Power, keep-awake, and whether a person is using the PC or the Clash window. */
internal static class SystemState
{
    private static readonly object Gate = new();
    private static IntPtr _request;
    private static bool _awake;

    /**
     * Plugged in, battery percent (-1 when unknown), battery saver on, and whether the PC has a
     * battery. Only Windows' "no system battery" flag (128) says it has none; an unknown status
     * (255) counts as a battery, so a laptop that can't be read keeps its battery line.
     */
    public static (bool Ac, int BatteryPercent, bool Saver, bool HasBattery) Power()
    {
        if (!Win32.GetSystemPowerStatus(out var status)) throw new InvalidOperationException("GetSystemPowerStatus failed");
        var hasBattery = status.BatteryFlag == 255 || (status.BatteryFlag & 128) == 0;
        return (status.ACLineStatus == 1, status.BatteryLifePercent == 255 ? -1 : status.BatteryLifePercent, (status.SystemStatusFlag & 1) != 0, hasBattery);
    }

    /**
     * Holds the system and the display awake while [on]. A power request rather than
     * SetThreadExecutionState, which belongs to the thread that called it; the request lapses
     * when the process exits, and powercfg /requests names it.
     */
    public static void KeepAwake(bool on)
    {
        lock (Gate)
        {
            if (on == _awake) return;
            if (_request == IntPtr.Zero)
            {
                // POWER_REQUEST_CONTEXT_VERSION, POWER_REQUEST_CONTEXT_SIMPLE_STRING
                var context = new Win32.REASON_CONTEXT { Version = 0, Flags = 1, SimpleReasonString = "Clash Farmer is running" };
                var request = Win32.PowerCreateRequest(ref context);
                if (request == IntPtr.Zero || request == new IntPtr(-1)) throw new InvalidOperationException($"PowerCreateRequest failed: {Marshal.GetLastWin32Error()}");
                _request = request;
            }
            if (on)
            {
                Win32.PowerSetRequest(_request, Win32.PowerRequestSystemRequired);
                Win32.PowerSetRequest(_request, Win32.PowerRequestDisplayRequired);
            }
            else
            {
                Win32.PowerClearRequest(_request, Win32.PowerRequestSystemRequired);
                Win32.PowerClearRequest(_request, Win32.PowerRequestDisplayRequired);
            }
            _awake = on;
        }
    }

    /**
     * Milliseconds since the last real mouse or keyboard input anywhere (posted messages don't
     * count), whether Clash's window is the active one, and whether the real cursor is over the
     * game.
     */
    public static (long IdleMs, bool GameForeground, bool CursorInGame) Activity()
    {
        var info = new Win32.LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<Win32.LASTINPUTINFO>() };
        Win32.GetLastInputInfo(ref info);
        var idle = unchecked((uint)Environment.TickCount - info.dwTime);
        var window = GameWindow.Find();
        if (window == null) return (idle, false, false);
        var inGame = false;
        if (Win32.GetCursorPos(out var cursor))
        {
            var game = Win32.ClientOnScreen(window.Game);
            inGame = cursor.X >= game.Left && cursor.X < game.Right && cursor.Y >= game.Top && cursor.Y < game.Bottom;
        }
        return (idle, Win32.GetForegroundWindow() == window.Top, inGame);
    }
}
