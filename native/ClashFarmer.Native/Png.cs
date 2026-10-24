using System.IO.Compression;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace ClashFarmer.Native;

internal static class Png
{
    private static readonly uint[] CrcTable = BuildCrcTable();

    /** Writes BGRA pixels as an RGB PNG. */
    public static byte[] Encode(Frame frame)
    {
        using var raw = new MemoryStream();
        for (var y = 0; y < frame.Height; y++)
        {
            raw.WriteByte(0);
            var row = y * frame.Width * 4;
            for (var x = 0; x < frame.Width; x++)
            {
                var i = row + x * 4;
                raw.WriteByte(frame.Bgra[i + 2]);
                raw.WriteByte(frame.Bgra[i + 1]);
                raw.WriteByte(frame.Bgra[i]);
            }
        }
        using var compressed = new MemoryStream();
        using (var z = new ZLibStream(compressed, CompressionLevel.Fastest, leaveOpen: true))
        {
            raw.Position = 0;
            raw.CopyTo(z);
        }
        using var png = new MemoryStream();
        png.Write([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
        var ihdr = new byte[13];
        WriteBigEndian(ihdr, 0, (uint)frame.Width);
        WriteBigEndian(ihdr, 4, (uint)frame.Height);
        ihdr[8] = 8; // bit depth
        ihdr[9] = 2; // RGB
        WriteChunk(png, "IHDR", ihdr);
        WriteChunk(png, "IDAT", compressed.ToArray());
        WriteChunk(png, "IEND", []);
        return png.ToArray();
    }

    /** Any image Windows can decode, as BGRA. */
    public static Frame Decode(byte[] bytes)
    {
        using var stream = new InMemoryRandomAccessStream();
        stream.WriteAsync(bytes.AsBuffer()).AsTask().GetAwaiter().GetResult();
        stream.Seek(0);
        var decoder = BitmapDecoder.CreateAsync(stream).AsTask().GetAwaiter().GetResult();
        using var bitmap = decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied).AsTask().GetAwaiter().GetResult();
        var bgra = new byte[bitmap.PixelWidth * bitmap.PixelHeight * 4];
        bitmap.CopyToBuffer(bgra.AsBuffer());
        return new Frame(bitmap.PixelWidth, bitmap.PixelHeight, bgra, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    private static void WriteChunk(Stream s, string type, byte[] data)
    {
        var len = new byte[4];
        WriteBigEndian(len, 0, (uint)data.Length);
        s.Write(len);
        var typeBytes = System.Text.Encoding.ASCII.GetBytes(type);
        s.Write(typeBytes);
        s.Write(data);
        var crc = Crc(typeBytes, data);
        var crcBytes = new byte[4];
        WriteBigEndian(crcBytes, 0, crc);
        s.Write(crcBytes);
    }

    private static void WriteBigEndian(byte[] b, int at, uint v)
    {
        b[at] = (byte)(v >> 24);
        b[at + 1] = (byte)(v >> 16);
        b[at + 2] = (byte)(v >> 8);
        b[at + 3] = (byte)v;
    }

    private static uint Crc(byte[] type, byte[] data)
    {
        var c = 0xFFFFFFFFu;
        foreach (var b in type) c = CrcTable[(c ^ b) & 0xFF] ^ (c >> 8);
        foreach (var b in data) c = CrcTable[(c ^ b) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFFu;
    }

    private static uint[] BuildCrcTable()
    {
        var table = new uint[256];
        for (uint n = 0; n < 256; n++)
        {
            var c = n;
            for (var k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[n] = c;
        }
        return table;
    }
}
