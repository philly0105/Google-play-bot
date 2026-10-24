namespace ClashFarmer.Native;

/** Spike: image preparation tried ahead of Windows OCR. */
internal static class Preprocess
{
    public static Frame Run(Frame source, int scale, string mode)
    {
        var frame = scale > 1 ? Upscale(source, scale) : source;
        return mode switch
        {
            "white" => WhiteText(frame),
            "gray" => Gray(frame),
            _ => frame
        };
    }

    /** Bilinear upscale by a whole factor. */
    private static Frame Upscale(Frame f, int k)
    {
        int w = f.Width * k, h = f.Height * k;
        var outBytes = new byte[w * h * 4];
        for (var y = 0; y < h; y++)
        {
            var sy = Math.Max(0, (y + 0.5) / k - 0.5);
            var y0 = Math.Min((int)sy, f.Height - 1);
            var y1 = Math.Min(y0 + 1, f.Height - 1);
            var fy = sy - y0;
            for (var x = 0; x < w; x++)
            {
                var sx = Math.Max(0, (x + 0.5) / k - 0.5);
                var x0 = Math.Min((int)sx, f.Width - 1);
                var x1 = Math.Min(x0 + 1, f.Width - 1);
                var fx = sx - x0;
                for (var c = 0; c < 4; c++)
                {
                    var a = f.Bgra[(y0 * f.Width + x0) * 4 + c] * (1 - fx) + f.Bgra[(y0 * f.Width + x1) * 4 + c] * fx;
                    var b = f.Bgra[(y1 * f.Width + x0) * 4 + c] * (1 - fx) + f.Bgra[(y1 * f.Width + x1) * 4 + c] * fx;
                    outBytes[(y * w + x) * 4 + c] = (byte)Math.Round(a * (1 - fy) + b * fy);
                }
            }
        }
        return new Frame(w, h, outBytes, f.TimestampMs);
    }

    /** Near-white pixels become black text on a white page; everything else is dropped. */
    private static Frame WhiteText(Frame f)
    {
        var outBytes = new byte[f.Bgra.Length];
        for (var i = 0; i < f.Bgra.Length; i += 4)
        {
            var min = Math.Min(f.Bgra[i], Math.Min(f.Bgra[i + 1], f.Bgra[i + 2]));
            var max = Math.Max(f.Bgra[i], Math.Max(f.Bgra[i + 1], f.Bgra[i + 2]));
            var text = min > 185 && max - min < 60;
            var v = (byte)(text ? 0 : 255);
            outBytes[i] = v;
            outBytes[i + 1] = v;
            outBytes[i + 2] = v;
            outBytes[i + 3] = 255;
        }
        return new Frame(f.Width, f.Height, outBytes, f.TimestampMs);
    }

    private static Frame Gray(Frame f)
    {
        var outBytes = new byte[f.Bgra.Length];
        for (var i = 0; i < f.Bgra.Length; i += 4)
        {
            var v = (byte)((f.Bgra[i] * 29 + f.Bgra[i + 1] * 150 + f.Bgra[i + 2] * 77) >> 8);
            outBytes[i] = v;
            outBytes[i + 1] = v;
            outBytes[i + 2] = v;
            outBytes[i + 3] = 255;
        }
        return new Frame(f.Width, f.Height, outBytes, f.TimestampMs);
    }
}
