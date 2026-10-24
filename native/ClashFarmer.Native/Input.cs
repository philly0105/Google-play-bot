using System.Diagnostics;

namespace ClashFarmer.Native;

internal readonly record struct Stroke(int FromX, int FromY, int ToX, int ToY, int Ms);

/**
 * Posted messages to the game surface. They need neither focus nor the real cursor, and
 * Google Play Games turns the left button into a finger and the wheel into zoom. Points are the
 * surface's client pixels.
 */
internal static class Input
{
    /** How often the finger moves along a stroke. Each position is placed by elapsed time. */
    private const int StepMs = 8;

    /** The surface the button is down on, and where, so an exit mid-gesture can lift it. */
    private static IntPtr _downOn;
    private static int _x, _y;

    /** One finger along [strokes]: down at the first one's start, up at the last one's end. */
    public static void Gesture(IntPtr game, IReadOnlyList<Stroke> strokes)
    {
        _x = strokes[0].FromX;
        _y = strokes[0].FromY;
        Post(game, Win32.WM_MOUSEMOVE, 0, _x, _y);
        Post(game, Win32.WM_LBUTTONDOWN, Win32.MK_LBUTTON, _x, _y);
        _downOn = game;
        try
        {
            foreach (var stroke in strokes)
            {
                var clock = Stopwatch.StartNew();
                while (true)
                {
                    var t = stroke.Ms <= 0 ? 1.0 : Math.Min(1.0, clock.Elapsed.TotalMilliseconds / stroke.Ms);
                    var x = (int)Math.Round(stroke.FromX + (stroke.ToX - stroke.FromX) * t);
                    var y = (int)Math.Round(stroke.FromY + (stroke.ToY - stroke.FromY) * t);
                    if (x != _x || y != _y)
                    {
                        _x = x;
                        _y = y;
                        Post(game, Win32.WM_MOUSEMOVE, Win32.MK_LBUTTON, x, y);
                    }
                    if (t >= 1.0) break;
                    Thread.Sleep(StepMs);
                }
            }
        }
        finally
        {
            _downOn = IntPtr.Zero;
            Win32.PostMessage(game, Win32.WM_LBUTTONUP, IntPtr.Zero, Win32.MakeLParam(_x, _y));
        }
    }

    /** Lifts a finger left down by a gesture the process is quitting in the middle of. */
    public static void ReleaseHeld()
    {
        var game = _downOn;
        if (game != IntPtr.Zero) Win32.PostMessage(game, Win32.WM_LBUTTONUP, IntPtr.Zero, Win32.MakeLParam(_x, _y));
    }

    /** [notches] wheel steps at [x], [y]: negative zooms Clash out, positive in. */
    public static void Wheel(IntPtr game, int x, int y, int notches, int intervalMs)
    {
        // The wheel message carries screen coordinates.
        var origin = new Win32.POINT();
        Win32.ClientToScreen(game, ref origin);
        var at = Win32.MakeLParam(origin.X + x, origin.Y + y);
        var delta = (IntPtr)((120 * Math.Sign(notches)) << 16);
        for (var i = 0; i < Math.Abs(notches); i++)
        {
            if (i > 0) Thread.Sleep(intervalMs);
            if (!Win32.PostMessage(game, Win32.WM_MOUSEWHEEL, delta, at)) throw Gone();
        }
    }

    /** A key press; Esc is Android's Back. */
    public static void Key(IntPtr game, uint vk)
    {
        var scan = Win32.MapVirtualKey(vk, 0);
        if (!Win32.PostMessage(game, Win32.WM_KEYDOWN, (IntPtr)vk, (IntPtr)(1 | (scan << 16)))) throw Gone();
        Thread.Sleep(50);
        Win32.PostMessage(game, Win32.WM_KEYUP, (IntPtr)vk, (IntPtr)(1 | (scan << 16) | (1u << 30) | (1u << 31)));
    }

    private static void Post(IntPtr game, uint message, int keys, int x, int y)
    {
        if (!Win32.PostMessage(game, message, (IntPtr)keys, Win32.MakeLParam(x, y))) throw Gone();
    }

    private static SidecarException Gone() => new("no_window", "The Clash window is gone");
}
