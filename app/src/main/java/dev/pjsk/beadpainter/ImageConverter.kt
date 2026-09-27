package dev.pjsk.beadpainter

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToInt

data class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }
}

enum class FitMode { CROP, CONTAIN, STRETCH }
enum class DitherMode { ILLUSTRATION, NONE, FLOYD_STEINBERG, ATKINSON }

data class ConvertOptions(
    val fit: FitMode = FitMode.CROP,
    val dither: DitherMode = DitherMode.ILLUSTRATION,
    val brightness: Int = 100,
    val contrast: Int = 100,
    val saturation: Int = 100,
    val hue: Int = 0,
    val temperature: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val paintWhite: Boolean = false,
) {
    init {
        require(brightness in 0..200 && contrast in 0..200 && saturation in 0..200)
        require(hue in -180..180 && temperature in -100..100 && highlights in -100..100 && shadows in -100..100)
    }
}

object ImageConverter {
    const val GRID_SIZE = 24
    const val CELL_COUNT = GRID_SIZE * GRID_SIZE

    private data class Sample(val average: DoubleArray, val subSamples: DoubleArray)
    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    private data class Viewport(val originX: Double, val originY: Double, val width: Double, val height: Double)

    fun convert(image: PixelImage, options: ConvertOptions): IntArray {
        val viewport = viewport(image, options)
        val sampled = sampleGrid(image, viewport, options)
        val result = if (options.dither == DitherMode.ILLUSTRATION) illustration(sampled) else diffuse(sampled, options.dither)
        if (!options.paintWhite) {
            for (index in result.indices) if (result[index] == BeadPalette.WHITE) result[index] = BeadPalette.EMPTY
        }
        return result
    }

    fun adjustedPreview(image: PixelImage, options: ConvertOptions, size: Int = 144): PixelImage {
        require(size > 0)
        val viewport = viewport(image, options)
        val pixels = IntArray(size * size) { position ->
            val x = viewport.originX + (position % size + 0.5) * viewport.width / size
            val y = viewport.originY + (position / size + 0.5) * viewport.height / size
            val color = bilinear(image, x, y)
            applyFilters(color, options)
            BeadPalette.argb(color[0].roundToInt(), color[1].roundToInt(), color[2].roundToInt())
        }
        return PixelImage(size, size, pixels)
    }

    private fun contentBounds(image: PixelImage, paintWhite: Boolean): Bounds {
        if (image.width == GRID_SIZE && image.height == GRID_SIZE) return Bounds(0, 0, image.width, image.height)
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.pixels[y * image.width + x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < 16 || (!paintWhite && BeadPalette.red(pixel) >= 250 && BeadPalette.green(pixel) >= 250 && BeadPalette.blue(pixel) >= 250)) continue
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        return if (right < left) Bounds(0, 0, image.width, image.height) else Bounds(left, top, right + 1, bottom + 1)
    }

    private fun viewport(image: PixelImage, options: ConvertOptions): Viewport {
        val bounds = contentBounds(image, options.paintWhite)
        val sourceWidth = bounds.width.toDouble()
        val sourceHeight = bounds.height.toDouble()
        val side = when (options.fit) {
            FitMode.CROP -> min(sourceWidth, sourceHeight)
            FitMode.CONTAIN -> max(sourceWidth, sourceHeight)
            FitMode.STRETCH -> 0.0
        }
        val mappedWidth = if (options.fit == FitMode.STRETCH) sourceWidth else side
        val mappedHeight = if (options.fit == FitMode.STRETCH) sourceHeight else side
        return Viewport(bounds.left + (sourceWidth - mappedWidth) / 2.0, bounds.top + (sourceHeight - mappedHeight) / 2.0, mappedWidth, mappedHeight)
    }

    private fun sampleGrid(image: PixelImage, viewport: Viewport, options: ConvertOptions): Array<Sample> {
        val subX = ceil(viewport.width / GRID_SIZE).toInt().coerceIn(1, 4)
        val subY = ceil(viewport.height / GRID_SIZE).toInt().coerceIn(1, 4)
        val exact = image.width == GRID_SIZE && image.height == GRID_SIZE

        return Array(CELL_COUNT) { position ->
            val cellX = position % GRID_SIZE
            val cellY = position / GRID_SIZE
            val samples = DoubleArray(subX * subY * 3)
            val linear = DoubleArray(3)
            var sampleIndex = 0
            for (partY in 0 until subY) for (partX in 0 until subX) {
                val sourceX = viewport.originX + (cellX + (partX + 0.5) / subX) * viewport.width / GRID_SIZE
                val sourceY = viewport.originY + (cellY + (partY + 0.5) / subY) * viewport.height / GRID_SIZE
                val color = if (exact) rgb(image.pixels[cellY * GRID_SIZE + cellX]) else bilinear(image, sourceX, sourceY)
                applyFilters(color, options)
                for (channel in 0..2) {
                    samples[sampleIndex++] = color[channel]
                    linear[channel] += BeadPalette.linear(color[channel])
                }
            }
            val count = subX * subY
            Sample(DoubleArray(3) { channel -> BeadPalette.encoded(linear[channel] / count) }, samples)
        }
    }

    private fun bilinear(image: PixelImage, x: Double, y: Double): DoubleArray {
        if (x < 0 || y < 0 || x >= image.width || y >= image.height) return doubleArrayOf(255.0, 255.0, 255.0)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, image.width - 1)
        val y1 = min(y0 + 1, image.height - 1)
        val topLeft = rgb(image.pixels[y0 * image.width + x0])
        val topRight = rgb(image.pixels[y0 * image.width + x1])
        val bottomLeft = rgb(image.pixels[y1 * image.width + x0])
        val bottomRight = rgb(image.pixels[y1 * image.width + x1])
        val dx = x - x0
        val dy = y - y0
        return DoubleArray(3) { channel ->
            val top = topLeft[channel] + (topRight[channel] - topLeft[channel]) * dx
            val bottom = bottomLeft[channel] + (bottomRight[channel] - bottomLeft[channel]) * dx
            top + (bottom - top) * dy
        }
    }

    private fun rgb(pixel: Int): DoubleArray {
        val alpha = ((pixel ushr 24) and 0xff) / 255.0
        return doubleArrayOf(BeadPalette.red(pixel).toDouble(), BeadPalette.green(pixel).toDouble(), BeadPalette.blue(pixel).toDouble())
            .map { it * alpha + 255.0 * (1.0 - alpha) }.toDoubleArray()
    }

    private fun applyFilters(color: DoubleArray, options: ConvertOptions) {
        val brightness = options.brightness / 100.0
        val contrast = options.contrast / 100.0
        val saturation = options.saturation / 100.0
        for (channel in color.indices) color[channel] = ((color[channel] * brightness / 255.0 - 0.5) * contrast + 0.5) * 255.0
        val luma = 0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2]
        val level = (luma / 255.0).coerceIn(0.0, 1.0)
        val tone = options.shadows / 100.0 * (1 - level) * (1 - level) * 85.0 +
            options.highlights / 100.0 * level * level * 85.0
        for (channel in color.indices) color[channel] = (luma + (color[channel] - luma) * saturation + tone).coerceIn(0.0, 255.0)
        color[0] = (color[0] + options.temperature * 0.35).coerceIn(0.0, 255.0)
        color[2] = (color[2] - options.temperature * 0.35).coerceIn(0.0, 255.0)
        if (options.hue != 0) rotateHue(color, options.hue)
    }

    private fun rotateHue(color: DoubleArray, degrees: Int) {
        val maximum = color.max()
        val minimum = color.min()
        val chroma = maximum - minimum
        if (chroma == 0.0) return
        val original = when (maximum) {
            color[0] -> ((color[1] - color[2]) / chroma) % 6.0
            color[1] -> (color[2] - color[0]) / chroma + 2.0
            else -> (color[0] - color[1]) / chroma + 4.0
        }
        val hue = ((original * 60.0 + degrees) % 360.0 + 360.0) % 360.0
        val secondary = chroma * (1.0 - abs(hue / 60.0 % 2.0 - 1.0))
        val offset = minimum
        val rotated = when (hue.toInt() / 60) {
            0 -> doubleArrayOf(chroma, secondary, 0.0)
            1 -> doubleArrayOf(secondary, chroma, 0.0)
            2 -> doubleArrayOf(0.0, chroma, secondary)
            3 -> doubleArrayOf(0.0, secondary, chroma)
            4 -> doubleArrayOf(secondary, 0.0, chroma)
            else -> doubleArrayOf(chroma, 0.0, secondary)
        }
        for (channel in color.indices) color[channel] = (rotated[channel] + offset).coerceIn(0.0, 255.0)
    }

    private fun diffuse(samples: Array<Sample>, mode: DitherMode): IntArray {
        val working = Array(CELL_COUNT) { samples[it].average.copyOf() }
        val result = IntArray(CELL_COUNT)
        fun add(x: Int, y: Int, error: DoubleArray, weight: Double) {
            if (x !in 0 until GRID_SIZE || y !in 0 until GRID_SIZE) return
            val color = working[y * GRID_SIZE + x]
            for (channel in 0..2) color[channel] = (color[channel] + error[channel] * weight).coerceIn(0.0, 255.0)
        }
        for (y in 0 until GRID_SIZE) for (step in 0 until GRID_SIZE) {
            val reverse = mode == DitherMode.FLOYD_STEINBERG && y % 2 == 1
            val x = if (reverse) GRID_SIZE - step - 1 else step
            val index = y * GRID_SIZE + x
            val old = working[index]
            val nearest = BeadPalette.nearestIndex(old[0], old[1], old[2])
            result[index] = nearest
            if (mode == DitherMode.NONE) continue
            val chosen = BeadPalette.colors[nearest].rgb
            val error = doubleArrayOf(old[0] - BeadPalette.red(chosen), old[1] - BeadPalette.green(chosen), old[2] - BeadPalette.blue(chosen))
            if (mode == DitherMode.FLOYD_STEINBERG) {
                val direction = if (reverse) -1 else 1
                add(x + direction, y, error, 7.0 / 16.0 * 0.6)
                add(x - direction, y + 1, error, 3.0 / 16.0 * 0.6)
                add(x, y + 1, error, 5.0 / 16.0 * 0.6)
                add(x + direction, y + 1, error, 1.0 / 16.0 * 0.6)
            } else {
                add(x + 1, y, error, 1.0 / 8.0)
                add(x + 2, y, error, 1.0 / 8.0)
                add(x - 1, y + 1, error, 1.0 / 8.0)
                add(x, y + 1, error, 1.0 / 8.0)
                add(x + 1, y + 1, error, 1.0 / 8.0)
                add(x, y + 2, error, 1.0 / 8.0)
            }
        }
        return result
    }

    private fun illustration(samples: Array<Sample>): IntArray {
        val representative = Array(CELL_COUNT) { index ->
            val subSamples = samples[index].subSamples
            val labs = Array(subSamples.size / 3) { sample ->
                BeadPalette.toLab(BeadPalette.argb(subSamples[sample * 3].roundToInt(), subSamples[sample * 3 + 1].roundToInt(), subSamples[sample * 3 + 2].roundToInt()))
            }
            val mean = Lab(labs.map { it.lightness }.average(), labs.map { it.a }.average(), labs.map { it.b }.average())
            labs.minBy { it.distanceSquared(mean) }
        }
        val cost = Array(CELL_COUNT) { index -> DoubleArray(BeadPalette.colors.size) { color -> representative[index].distanceSquared(BeadPalette.labs[color]) } }
        val labels = IntArray(CELL_COUNT) { index -> cost[index].indices.minBy { cost[index][it] } }
        repeat(3) {
            var changed = false
            for (index in labels.indices) {
                val x = index % GRID_SIZE
                val y = index / GRID_SIZE
                val neighbors = intArrayOf(if (x > 0) index - 1 else -1, if (x < GRID_SIZE - 1) index + 1 else -1, if (y > 0) index - GRID_SIZE else -1, if (y < GRID_SIZE - 1) index + GRID_SIZE else -1)
                val best = cost[index].indices.minBy { candidate ->
                    cost[index][candidate] + neighbors.filter { it >= 0 && labels[it] != candidate }.sumOf { neighbor ->
                        0.0012 * exp(-representative[index].distanceSquared(representative[neighbor]) / 0.0025)
                    }
                }
                if (labels[index] != best) {
                    labels[index] = best
                    changed = true
                }
            }
            if (!changed) return labels
        }
        return labels
    }
}
