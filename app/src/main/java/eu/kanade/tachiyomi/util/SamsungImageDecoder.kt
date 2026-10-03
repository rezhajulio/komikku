package eu.kanade.tachiyomi.util

import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.createBitmap
import ca.mpreg.imagedecoder.ImageDecoder
import java.io.InputStream

/**
 * Image decoding helpers for Samsung devices.
 *
 * Samsung's libhwui ships a proprietary format sniffer (kumiho::isSupportedFormat)
 * with a stack buffer overflow: while sniffing certain images (e.g. carrying large
 * XMP metadata) it smashes the native stack and the process dies with SIGILL (ARM
 * pointer-authentication failure on return). It is reached through the platform
 * decoder ([android.graphics.BitmapFactory] / [android.graphics.ImageDecoder]),
 * including bounds-only ([android.graphics.BitmapFactory.Options.inJustDecodeBounds])
 * decodes. On Samsung devices every decode of untrusted images must go through the
 * app's own (Rust) decoder instead.
 */
object SamsungImageDecoder {

    val isSamsungDevice: Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * Decodes [input] fully into a [Bitmap] using the app's Rust image decoder.
     * Returns null (instead of throwing) on corrupt input, mirroring
     * [android.graphics.BitmapFactory.decodeStream] behavior.
     *
     * The caller retains ownership of [input]: it is not closed here.
     */
    fun decodeToBitmap(input: InputStream): Bitmap? {
        return try {
            ImageDecoder.open(input).use { dec ->
                val frame = dec.decodeNext()
                try {
                    val config = if (dec.isHdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    val bitmap = createBitmap(frame.width, frame.height, config)
                    frame.image.rewind()
                    bitmap.copyPixelsFromBuffer(frame.image)
                    bitmap
                } finally {
                    frame.close()
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
