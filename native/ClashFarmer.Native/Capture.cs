using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using WinRT;

namespace ClashFarmer.Native;

/** Pixels as BGRA, top row first, no padding. */
internal sealed record Frame(int Width, int Height, byte[] Bgra, long TimestampMs);

[ComImport]
[Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IGraphicsCaptureItemInterop
{
    IntPtr CreateForWindow([In] IntPtr window, [In] ref Guid iid);
    IntPtr CreateForMonitor([In] IntPtr monitor, [In] ref Guid iid);
}

internal static class D3D
{
    [DllImport("d3d11.dll")]
    private static extern int D3D11CreateDevice(IntPtr adapter, int driverType, IntPtr software, uint flags,
        IntPtr featureLevels, uint numFeatureLevels, uint sdkVersion, out IntPtr device, out int featureLevel, out IntPtr context);

    [DllImport("d3d11.dll")]
    private static extern int CreateDirect3D11DeviceFromDXGIDevice(IntPtr dxgiDevice, out IntPtr graphicsDevice);

    private static readonly Guid IID_IDXGIDevice = new("54ec77fa-1377-44e6-8c32-88fd5f44c84c");

    public static IDirect3DDevice CreateDevice()
    {
        // D3D_DRIVER_TYPE_HARDWARE, D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_SDK_VERSION
        var hr = D3D11CreateDevice(IntPtr.Zero, 1, IntPtr.Zero, 0x20, IntPtr.Zero, 0, 7, out var device, out _, out var context);
        Marshal.ThrowExceptionForHR(hr);
        try
        {
            var iid = IID_IDXGIDevice;
            Marshal.ThrowExceptionForHR(Marshal.QueryInterface(device, in iid, out var dxgi));
            try
            {
                Marshal.ThrowExceptionForHR(CreateDirect3D11DeviceFromDXGIDevice(dxgi, out var inspectable));
                try { return MarshalInterface<IDirect3DDevice>.FromAbi(inspectable); }
                finally { Marshal.Release(inspectable); }
            }
            finally { Marshal.Release(dxgi); }
        }
        finally
        {
            Marshal.Release(device);
            if (context != IntPtr.Zero) Marshal.Release(context);
        }
    }
}

/**
 * Windows Graphics Capture of one window. Works for DXGI flip-model windows that GDI reads as
 * black, and for windows covered by others; not for minimized ones.
 *
 * Every frame is taken off the pool as it arrives and only the newest is kept. A pool left full
 * drops the frames that come after, so draining it at grab time would hand out a frame from just
 * after the previous grab, however long ago that was.
 */
internal sealed class WindowCapture : IDisposable
{
    private static readonly Guid IID_IGraphicsCaptureItem = new("79C3F95B-31F7-4EC2-A464-632EF5D30760");

    public IntPtr Hwnd { get; }
    public IntPtr CropTo { get; }
    private readonly IDirect3DDevice _device;
    private readonly GraphicsCaptureItem _item;
    private readonly Direct3D11CaptureFramePool _pool;
    private readonly GraphicsCaptureSession _session;
    private Windows.Graphics.SizeInt32 _poolSize;
    private readonly object _lock = new();

    /** The newest frame not yet copied out, and when it arrived (Unix ms). */
    private Direct3D11CaptureFrame? _latest;
    private long _latestAt;

    /** The last frame copied out. A still window sends no new frames, and this is still what it shows. */
    private Frame? _copied;

    /** Captures [hwnd]; frames are cropped to [cropTo]'s client area if given, else to [hwnd]'s. */
    public WindowCapture(IntPtr hwnd, IntPtr cropTo = default)
    {
        Hwnd = hwnd;
        CropTo = cropTo;
        _device = D3D.CreateDevice();
        _item = CreateItem(hwnd);
        _poolSize = _item.Size;
        _pool = Direct3D11CaptureFramePool.CreateFreeThreaded(_device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 3, _poolSize);
        _pool.FrameArrived += OnFrameArrived;
        _session = _pool.CreateCaptureSession(_item);
        try { _session.IsCursorCaptureEnabled = false; } catch { }
        try { _session.IsBorderRequired = false; } catch { }
        _session.StartCapture();
    }

    private void OnFrameArrived(Direct3D11CaptureFramePool pool, object _)
    {
        Direct3D11CaptureFrame? old;
        lock (_lock)
        {
            var next = pool.TryGetNextFrame();
            if (next == null) return;
            old = _latest;
            _latest = next;
            _latestAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var size = next.ContentSize;
            if (size.Width != _poolSize.Width || size.Height != _poolSize.Height)
            {
                _poolSize = size;
                pool.Recreate(_device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 3, _poolSize);
            }
            Monitor.PulseAll(_lock);
        }
        old?.Dispose();
    }

    private static GraphicsCaptureItem CreateItem(IntPtr hwnd)
    {
        var factory = ActivationFactory.Get("Windows.Graphics.Capture.GraphicsCaptureItem");
        var interop = factory.AsInterface<IGraphicsCaptureItemInterop>();
        var iid = IID_IGraphicsCaptureItem;
        var pointer = interop.CreateForWindow(hwnd, ref iid);
        try { return MarshalInterface<GraphicsCaptureItem>.FromAbi(pointer); }
        finally { Marshal.Release(pointer); }
    }

    /**
     * The newest frame that arrived after [newerThan] (Unix ms), cropped to the client area.
     * Waits up to [timeoutMs] when there is none yet.
     */
    public Frame Grab(int timeoutMs = 3000, long newerThan = 0)
    {
        lock (_lock)
        {
            var deadline = Environment.TickCount64 + timeoutMs;
            while (true)
            {
                if (_latest != null && _latestAt > newerThan)
                {
                    var latest = _latest;
                    _latest = null;
                    using (latest) _copied = Copy(latest, _latestAt);
                    return _copied;
                }
                if (_latest == null && _copied != null && _copied.TimestampMs > newerThan) return _copied;
                var left = deadline - Environment.TickCount64;
                if (left <= 0) throw new TimeoutException($"No frame from Windows Graphics Capture in {timeoutMs} ms");
                Monitor.Wait(_lock, (int)left);
            }
        }
    }

    private Frame Copy(Direct3D11CaptureFrame frame, long arrivedAt)
    {
        var contentSize = frame.ContentSize;
        using var bitmap = SoftwareBitmap.CreateCopyFromSurfaceAsync(frame.Surface, BitmapAlphaMode.Premultiplied).AsTask().GetAwaiter().GetResult();
        var full = new byte[bitmap.PixelWidth * bitmap.PixelHeight * 4];
        bitmap.CopyToBuffer(full.AsBuffer());
        return CropToClient(full, bitmap.PixelWidth, Math.Min(contentSize.Width, bitmap.PixelWidth),
            Math.Min(contentSize.Height, bitmap.PixelHeight), arrivedAt);
    }

    /**
     * The captured surface covers the window's frame bounds and the client area sits inside it,
     * except that the game surface runs a few pixels past the frame on the right and bottom. Those
     * pixels are copied from the nearest captured ones, so the frame has the client area's size and
     * its pixels are the ones input coordinates name.
     */
    private Frame CropToClient(byte[] full, int stride, int contentWidth, int contentHeight, long timestampMs)
    {
        var frame = Win32.FrameBounds(Hwnd);
        var client = Win32.ClientOnScreen(CropTo != IntPtr.Zero ? CropTo : Hwnd);
        int x0 = client.Left - frame.Left, y0 = client.Top - frame.Top, w = client.Width, h = client.Height;
        if (w <= 0 || h <= 0 || x0 >= contentWidth || y0 >= contentHeight || x0 + w <= 0 || y0 + h <= 0)
        {
            throw new InvalidOperationException($"Client area {client} lies outside the captured {contentWidth}x{contentHeight}");
        }
        // The columns of each row that the surface has; the rest repeat the pixel at the edge.
        int from = Math.Max(0, -x0), to = Math.Min(w, contentWidth - x0);
        var outBytes = new byte[w * h * 4];
        for (var row = 0; row < h; row++)
        {
            var sourceRow = Math.Clamp(y0 + row, 0, contentHeight - 1) * stride;
            var target = row * w * 4;
            Buffer.BlockCopy(full, (sourceRow + x0 + from) * 4, outBytes, target + from * 4, (to - from) * 4);
            for (var col = 0; col < from; col++) Buffer.BlockCopy(full, (sourceRow + x0 + from) * 4, outBytes, target + col * 4, 4);
            for (var col = to; col < w; col++) Buffer.BlockCopy(full, (sourceRow + x0 + to - 1) * 4, outBytes, target + col * 4, 4);
        }
        return new Frame(w, h, outBytes, timestampMs);
    }

    public void Dispose()
    {
        _pool.FrameArrived -= OnFrameArrived;
        _session.Dispose();
        _pool.Dispose();
        lock (_lock)
        {
            _latest?.Dispose();
            _latest = null;
        }
    }
}

internal static class GdiCapture
{
    /** PrintWindow with full-content rendering, of the client area. */
    public static Frame PrintWindowClient(IntPtr hwnd)
    {
        Win32.GetWindowRect(hwnd, out var window);
        var client = Win32.ClientOnScreen(hwnd);
        var screen = Win32.GetDC(IntPtr.Zero);
        var dc = Win32.CreateCompatibleDC(screen);
        var bmp = Win32.CreateCompatibleBitmap(screen, window.Width, window.Height);
        var old = Win32.SelectObject(dc, bmp);
        try
        {
            if (!Win32.PrintWindow(hwnd, dc, Win32.PW_RENDERFULLCONTENT)) throw new InvalidOperationException("PrintWindow failed");
            var full = ReadBits(dc, bmp, window.Width, window.Height);
            return Crop(full, window.Width, client.Left - window.Left, client.Top - window.Top, client.Width, client.Height);
        }
        finally
        {
            Win32.SelectObject(dc, old);
            Win32.DeleteObject(bmp);
            Win32.DeleteDC(dc);
            Win32.ReleaseDC(IntPtr.Zero, screen);
        }
    }

    /** What is on screen where the client area is, whatever covers it. */
    public static Frame ScreenClient(IntPtr hwnd)
    {
        var client = Win32.ClientOnScreen(hwnd);
        var screen = Win32.GetDC(IntPtr.Zero);
        var dc = Win32.CreateCompatibleDC(screen);
        var bmp = Win32.CreateCompatibleBitmap(screen, client.Width, client.Height);
        var old = Win32.SelectObject(dc, bmp);
        try
        {
            Win32.BitBlt(dc, 0, 0, client.Width, client.Height, screen, client.Left, client.Top, 0x00CC0020 | 0x40000000);
            var bits = ReadBits(dc, bmp, client.Width, client.Height);
            return new Frame(client.Width, client.Height, bits, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        }
        finally
        {
            Win32.SelectObject(dc, old);
            Win32.DeleteObject(bmp);
            Win32.DeleteDC(dc);
            Win32.ReleaseDC(IntPtr.Zero, screen);
        }
    }

    private static byte[] ReadBits(IntPtr dc, IntPtr bmp, int w, int h)
    {
        var header = new Win32.BITMAPINFOHEADER
        {
            biSize = Marshal.SizeOf<Win32.BITMAPINFOHEADER>(), biWidth = w, biHeight = -h, biPlanes = 1, biBitCount = 32
        };
        var bits = new byte[w * h * 4];
        Win32.GetDIBits(dc, bmp, 0, (uint)h, bits, ref header, 0);
        for (var i = 3; i < bits.Length; i += 4) bits[i] = 255;
        return bits;
    }

    private static Frame Crop(byte[] full, int stride, int dx, int dy, int w, int h)
    {
        var outBytes = new byte[w * h * 4];
        for (var row = 0; row < h; row++) Buffer.BlockCopy(full, ((row + dy) * stride + dx) * 4, outBytes, row * w * 4, w * 4);
        return new Frame(w, h, outBytes, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }
}
