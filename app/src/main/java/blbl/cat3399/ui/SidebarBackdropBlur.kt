package blbl.cat3399.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Samples only the small area behind the sidebar and blurs it once off the main thread. */
internal object SidebarBackdropBlur {
    private const val MAX_BITMAP_EDGE = 256
    private const val BLUR_RADIUS = 12
    private const val BLUR_PASSES = 3

    fun capture(source: View, target: View): Bitmap? {
        if (source.width == 0 || source.height == 0 || target.width == 0 || target.height == 0) return null

        val scale = min(1f, MAX_BITMAP_EDGE.toFloat() / max(target.width, target.height))
        val width = (target.width * scale).toInt().coerceAtLeast(1)
        val height = (target.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sourceLocation = IntArray(2)
        val targetLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        target.getLocationOnScreen(targetLocation)

        canvas.drawColor(Color.rgb(32, 33, 36))
        canvas.scale(scale, scale)
        canvas.translate(
            (sourceLocation[0] - targetLocation[0]).toFloat(),
            (sourceLocation[1] - targetLocation[1]).toFloat(),
        )
        source.draw(canvas)
        return bitmap
    }

    suspend fun blur(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()[Job]
        val width = source.width
        val height = source.height
        var pixels = IntArray(width * height)
        var scratch = IntArray(pixels.size)
        var output = IntArray(pixels.size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        repeat(BLUR_PASSES) {
            job?.ensureActive()
            boxPass(pixels, scratch, width, height, BLUR_RADIUS, horizontal = true)
            boxPass(scratch, output, width, height, BLUR_RADIUS, horizontal = false)
            val previous = pixels
            pixels = output
            output = previous
        }

        job?.ensureActive()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun boxPass(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val start = max(0, (if (horizontal) x else y) - radius)
                val end = min((if (horizontal) width else height) - 1, (if (horizontal) x else y) + radius)
                var alpha = 0
                var red = 0
                var green = 0
                var blue = 0

                for (position in start..end) {
                    val index = if (horizontal) y * width + position else position * width + x
                    val color = input[index]
                    alpha += Color.alpha(color)
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                }

                val count = end - start + 1
                output[y * width + x] = Color.argb(alpha / count, red / count, green / count, blue / count)
            }
        }
    }
}
