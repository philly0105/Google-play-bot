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
 */
internal sealed class WindowCapture : IDisposable
{
    private static readonly Guid IID_IGraphicsCaptureItem = new("79C3F95B-31F7-4EC2-A464-632EF5D30760");

    private readonly IntPtr _hwnd;
    private readonly IntPtr _cropTo;
    private readonly IDirect3DDevice _device;
    private readonly GraphicsCaptureItem _item;
    private Direct3D11CaptureFramePool _pool;
    private readonly GraphicsCaptureSession _session;
    private Windows.Graphics.SizeInt32 _poolSize;
    private readonly object _lock = new();

    /** Captures [hwnd]; frames are cropped to [cropTo]'s window rect if given, else to the client area. */
    public WindowCapture(IntPtr hwnd, IntPtr cropTo = default)
    {
        _hwnd = hwnd;
        _cropTo = cropTo;
        _device = D3D.CreateDevice();
        _item = CreateItem(hwnd);
        _poolSize = _item.Size;
        _pool = Direct3D11CaptureFramePool.CreateFreeThreaded(_device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, _poolSize);
        _session = _pool.CreateCaptureSession(_item);
        try { _session.IsCursorCaptureEnabled = false; } catch { }
        try { _session.IsBorderRequired = false; } catch { }
        _session.StartCapture();
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
     * The newest frame, cropped to the window's client area. Waits up to [timeoutMs] for one to
     * arrive; frames come only when the window redraws, so a static window may need the wait.
     */
    public Frame Grab(int timeoutMs = 2000)
    {
        lock (_lock)
        {
            var deadline = Environment.TickCount64 + timeoutMs;
            Direct3D11CaptureFrame? newest = null;
            while (true)
            {
                // Drain to the latest frame the pool holds.
                Direct3D11CaptureFrame? next;
                while ((next = _pool.TryGetNextFrame()) != null)
                {
                    newest?.Dispose();
                    newest = next;
                }
                if (newest != null) break;
                if (Environment.TickCount64 > deadline) throw new TimeoutException("No frame from Windows Graphics Capture");
                Thread.Sleep(5);
            }
            using (newest)
            {
                var contentSize = newest.ContentSize;
                if (contentSize.Width != _poolSize.Width || contentSize.Height != _poolSize.Height)
                {
                    _poolSize = contentSize;
                    _pool.Recreate(_device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, _poolSize);
                }
                using var bitmap = SoftwareBitmap.CreateCopyFromSurfaceAsync(newest.Surface, BitmapAlphaMode.Premultiplied).AsTask().GetAwaiter().GetResult();
                var full = new byte[bitmap.PixelWidth * bitmap.PixelHeight * 4];
                bitmap.CopyToBuffer(full.AsBuffer());
                return CropToClient(full, bitmap.PixelWidth, bitmap.PixelHeight, contentSize.Width, contentSize.Height);
            }
        }
    }

    /** The captured surface covers the window's frame bounds; the client area sits inside it. */
    private Frame CropToClient(byte[] full, int stride, int surfaceHeight, int contentWidth, int contentHeight)
    {
        var frame = Win32.FrameBounds(_hwnd);
        Win32.RECT client;
        if (_cropTo != IntPtr.Zero) Win32.GetWindowRect(_cropTo, out client);
        else client = Win32.ClientOnScreen(_hwnd);
        var dx = Math.Max(0, client.Left - frame.Left);
        var dy = Math.Max(0, client.Top - frame.Top);
        var w = Math.Min(client.Width, contentWidth - dx);
        var h = Math.Min(client.Height, contentHeight - dy);
        if (w <= 0 || h <= 0) throw new InvalidOperationException($"Client area {client} lies outside the captured {contentWidth}x{contentHeight}");
        var outBytes = new byte[w * h * 4];
        for (var row = 0; row < h; row++)
        {
            Buffer.BlockCopy(full, ((row + dy) * stride + dx) * 4, outBytes, row * w * 4, w * 4);
        }
        return new Frame(w, h, outBytes, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public void Dispose()
    {
        _session.Dispose();
        _pool.Dispose();
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
