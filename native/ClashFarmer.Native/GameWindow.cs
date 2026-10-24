namespace ClashFarmer.Native;

/** Clash of Clans in Google Play Games: the top-level window and the game surface inside it. */
internal sealed record GameWindow(IntPtr Top, IntPtr Game, string Title)
{
    /**
     * Looked up again on every call: handles change when Google Play Games restarts. The process
     * check keeps out a browser tab titled "Clash of Clans".
     */
    public static GameWindow? Find()
    {
        GameWindow? found = null;
        Win32.EnumWindows((top, _) =>
        {
            var title = Win32.Text(top);
            if (!title.StartsWith("Clash of Clans", StringComparison.Ordinal) || Win32.ProcessName(top) != "crosvm") return true;
            var game = IntPtr.Zero;
            Win32.EnumChildWindows(top, (child, _) =>
            {
                if (Win32.Class(child) != "CROSVM_1") return true;
                game = child;
                return false;
            }, IntPtr.Zero);
            if (game == IntPtr.Zero) return true;
            found = new GameWindow(top, game, title);
            return false;
        }, IntPtr.Zero);
        return found;
    }
}
