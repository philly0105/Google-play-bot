using System.Runtime.InteropServices;
using RapidOcrNet;
using SkiaSharp;

namespace ClashFarmer.Native;

internal sealed record OcrLine(string Text, int Left, int Top, int Right, int Bottom, double Score);

/**
 * PaddleOCR PP-OCRv5 (latin) through RapidOcrNet and ONNX Runtime. Windows' own OCR misread
 * Clash's font; see docs/gpg-spike.md. One engine, used one call at a time.
 */
internal static class PaddleOcr
{
    /** More threads than this made ONNX Runtime thrash on this PC: 1-3 s a frame instead of 0.3 s. */
    private const int Threads = 4;

    private static readonly RapidOcrOptions Options = RapidOcrOptions.Default with { DoAngle = false };
    private static readonly Lazy<RapidOcr> Engine = new(Load);

    public static bool Ready => Engine.IsValueCreated;

    /**
     * Why the models failed to load, or null. Lazy keeps a failed load's exception, so Ready stays
     * false for good; this tells hello's caller it failed rather than still loading.
     */
    public static volatile string? Error;

    /** Loads the models in the background so the first read doesn't wait for them. */
    public static void Warm() => Task.Run(() =>
    {
        try { _ = Engine.Value; }
        catch (Exception e) { Server.Log($"OCR models failed to load: {e.Message}"); }
    });

    private static RapidOcr Load()
    {
        try
        {
            // RapidOcrNet resolves bare model paths against the working directory, not its own.
            var dir = Path.Combine(AppContext.BaseDirectory, "models", "v5");
            var ocr = new RapidOcr();
            ocr.InitModels(
                Path.Combine(dir, "ch_PP-OCRv5_mobile_det.onnx"),
                Path.Combine(dir, "ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx"),
                Path.Combine(dir, "latin_PP-OCRv5_rec_mobile_infer.onnx"),
                Path.Combine(dir, "ppocrv5_latin_dict.txt"),
                Threads);
            return ocr;
        }
        catch (Exception e)
        {
            // A missing native DLL arrives wrapped in a TypeInitializationException; its inner
            // message is the one that names the DLL.
            Error = e.GetBaseException().Message;
            throw;
        }
    }

    /** The lines in a BGRA image, in the order PaddleOCR reads them. */
    public static List<OcrLine> Read(int width, int height, byte[] bgra)
    {
        using var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Opaque));
        var pixels = bitmap.GetPixels();
        for (var row = 0; row < height; row++)
        {
            Marshal.Copy(bgra, row * width * 4, pixels + row * bitmap.RowBytes, width * 4);
        }
        var result = Engine.Value.Detect(bitmap, Options, CancellationToken.None);
        var lines = new List<OcrLine>();
        foreach (var block in result.TextBlocks)
        {
            if (string.IsNullOrWhiteSpace(block.Text)) continue;
            var points = block.BoxPoints;
            var scores = block.CharScores ?? [];
            lines.Add(new OcrLine(
                block.Text,
                points.Min(p => p.X), points.Min(p => p.Y), points.Max(p => p.X), points.Max(p => p.Y),
                scores.Length > 0 ? scores.Average() : 0.0));
        }
        return lines;
    }
}
