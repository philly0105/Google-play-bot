package android.graphics

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/** A TYPE_INT_ARGB copy of the bitmap, for ImageIO and the desktop UI. */
fun Bitmap.toBufferedImage(): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    getPixels((image.raster.dataBuffer as DataBufferInt).data, 0, width, 0, 0, width, height)
    return image
}

/** An immutable bitmap of the image, as Android decodes files and takes screenshots. */
fun BufferedImage.toBitmap(): Bitmap =
    Bitmap.createBitmap(getRGB(0, 0, width, height, null, 0, width), width, height, Bitmap.Config.ARGB_8888)
