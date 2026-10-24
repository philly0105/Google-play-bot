using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;

namespace ClashFarmer.Native;

internal sealed record OcrLineBox(string Text, int Left, int Top, int Right, int Bottom);

/** Windows.Media.Ocr, the OCR built into Windows 10 and 11. */
internal static class WindowsOcr
{
    private static readonly Lazy<OcrEngine> Engine = new(() =>
        OcrEngine.TryCreateFromLanguage(new Windows.Globalization.Language("en-US"))
        ?? OcrEngine.TryCreateFromUserProfileLanguages()
        ?? throw new InvalidOperationException("No Windows OCR language is installed"));

    public static string Language => Engine.Value.RecognizerLanguage.LanguageTag;

    /** One box per recognised line, the union of its words' boxes, in the frame's pixels. */
    public static List<OcrLineBox> Recognize(Frame frame)
    {
        using var bitmap = new SoftwareBitmap(BitmapPixelFormat.Bgra8, frame.Width, frame.Height, BitmapAlphaMode.Premultiplied);
        bitmap.CopyFromBuffer(frame.Bgra.AsBuffer());
        var result = Engine.Value.RecognizeAsync(bitmap).AsTask().GetAwaiter().GetResult();
        var lines = new List<OcrLineBox>();
        foreach (var line in result.Lines)
        {
            double l = double.MaxValue, t = double.MaxValue, r = double.MinValue, b = double.MinValue;
            foreach (var word in line.Words)
            {
                var box = word.BoundingRect;
                l = Math.Min(l, box.X);
                t = Math.Min(t, box.Y);
                r = Math.Max(r, box.X + box.Width);
                b = Math.Max(b, box.Y + box.Height);
            }
            if (line.Words.Count == 0) continue;
            lines.Add(new OcrLineBox(line.Text, (int)Math.Floor(l), (int)Math.Floor(t), (int)Math.Ceiling(r), (int)Math.Ceiling(b)));
        }
        return lines;
    }
}
