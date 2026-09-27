package dev.pjsk.beadpainter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class GridLocation(val left: Float, val top: Float, val cellWidth: Float, val cellHeight: Float) {
    val right: Float get() = left + 24 * cellWidth
    val bottom: Float get() = top + 24 * cellHeight
    fun cell(column: Int, row: Int): PointF = PointF(left + (column + 0.5f) * cellWidth, top + (row + 0.5f) * cellHeight)
}

data class GameLayout(
    val grid: GridLocation,
    val palette: List<PointF>,
    val eraser: PointF,
    val saveIcon: PointF,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
)

class GameVision(context: Context) {
    private val icon = BitmapFactory.decodeStream(context.assets.open("save_icon.png"))

    fun locate(bitmap: Bitmap): GameLayout? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val vertical = linePositions(pixels, bitmap.width, bitmap.height, true)
        val horizontal = linePositions(pixels, bitmap.width, bitmap.height, false)
        Log.d("BeadVision", "lines vertical=$vertical horizontal=$horizontal size=${bitmap.width}x${bitmap.height}")
        val xLines = findGridRun(vertical, min(bitmap.width, bitmap.height)) ?: return null
        val yLines = findGridRun(horizontal, min(bitmap.width, bitmap.height)) ?: return null
        val grid = GridLocation(
            xLines[1], yLines[1],
            (xLines[25] - xLines[1]) / 24f,
            (yLines[25] - yLines[1]) / 24f,
        )
        if (abs(grid.cellWidth / grid.cellHeight - 1f) > 0.08f) return null
        if (grid.left < 0 || grid.top < 0 || grid.right >= bitmap.width || grid.bottom >= bitmap.height) return null
        Log.d("BeadVision", "grid left=${grid.left} top=${grid.top} pitch=${grid.cellWidth}")

        val first = colorAnchor(pixels, bitmap.width, bitmap.height, grid, BeadPalette.colors[0].rgb) ?: return null
        val second = colorAnchor(pixels, bitmap.width, bitmap.height, grid, BeadPalette.colors[1].rgb) ?: return null
        val nextRow = colorAnchor(pixels, bitmap.width, bitmap.height, grid, BeadPalette.colors[8].rgb) ?: return null
        val stepX = second.x - first.x
        val stepY = nextRow.y - first.y
        Log.d("BeadVision", "anchors=$first $second $nextRow")
        if (stepX / grid.cellWidth !in 1.6f..3.5f || stepY / grid.cellHeight !in 1.6f..3.5f) return null
        if (abs(second.y - first.y) > stepY * 0.12f || abs(nextRow.x - first.x) > stepX * 0.12f) return null
        val palette = List(BeadPalette.colors.size) { index ->
            PointF(first.x + (index % 8) * stepX, first.y + (index / 8) * stepY)
        }
        val correct = palette.indices.count { index ->
            val sample = sampleRgb(pixels, bitmap.width, bitmap.height, palette[index].x - stepX * 0.25f, palette[index].y - stepY * 0.25f)
            sample != null && channelDistance(sample, BeadPalette.colors[index].rgb) <= 30
        }
        Log.d("BeadVision", "palette matches=$correct")
        if (correct < 43) return null
        val eraser = PointF(first.x + 7 * stepX, first.y + 5 * stepY)
        if (eraser.x >= grid.left || eraser.y >= bitmap.height) return null
        val save = findSaveIcon(bitmap, grid) ?: return null
        Log.d("BeadVision", "save=$save")
        return GameLayout(grid, palette, eraser, save, bitmap.width, bitmap.height)
    }

    fun readCells(bitmap: Bitmap, layout: GameLayout): IntArray? {
        if (bitmap.width != layout.screenshotWidth || bitmap.height != layout.screenshotHeight) return null
        val cells = IntArray(ImageConverter.CELL_COUNT)
        for (row in 0 until 24) for (column in 0 until 24) {
            val center = layout.grid.cell(column, row)
            val rgb = medianColor(bitmap, center.x.roundToInt(), center.y.roundToInt())
            val spread = maxOf(BeadPalette.red(rgb), BeadPalette.green(rgb), BeadPalette.blue(rgb)) -
                minOf(BeadPalette.red(rgb), BeadPalette.green(rgb), BeadPalette.blue(rgb))
            cells[row * 24 + column] = if (minOf(BeadPalette.red(rgb), BeadPalette.green(rgb), BeadPalette.blue(rgb)) >= 215 && spread < 8) {
                if (isWhiteBead(bitmap, center, layout.grid.cellWidth)) BeadPalette.WHITE else BeadPalette.EMPTY
            } else {
                val closest = BeadPalette.colors.indices.minBy { channelDistance(rgb, BeadPalette.colors[it].rgb) }
                if (channelDistance(rgb, BeadPalette.colors[closest].rgb) > 65) return null
                closest
            }
        }
        return cells
    }

    fun hasSaveSuccess(bitmap: Bitmap, layout: GameLayout): Boolean {
        if (bitmap.width != layout.screenshotWidth || bitmap.height != layout.screenshotHeight) return false
        val left = (bitmap.width * 0.35f).roundToInt()
        val right = (bitmap.width * 0.65f).roundToInt()
        val bottom = (bitmap.height * 0.22f).roundToInt()
        var green = 0
        for (y in 0 until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val red = BeadPalette.red(pixel)
            val blue = BeadPalette.blue(pixel)
            val channelGreen = BeadPalette.green(pixel)
            if (channelGreen >= 80 && channelGreen > red * 1.2 && channelGreen > blue * 1.15) green++
        }
        return green > (right - left) * bottom * 0.0007
    }

    private fun isWhiteBead(bitmap: Bitmap, center: PointF, cellWidth: Float): Boolean {
        val radius = max(3, (cellWidth * 0.28f).roundToInt())
        val values = ArrayList<Double>()
        for (dy in -radius..radius step 2) for (dx in -radius..radius step 2) {
            val x = center.x.roundToInt() + dx
            val y = center.y.roundToInt() + dy
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) values.add(BeadPalette.red(bitmap.getPixel(x, y)).toDouble())
        }
        val mean = values.average()
        val deviation = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return mean > 248 && deviation < 5
    }

    private fun linePositions(pixels: IntArray, width: Int, height: Int, vertical: Boolean): List<Float> {
        val bytes = ByteArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = BeadPalette.red(color)
            val green = BeadPalette.green(color)
            val blue = BeadPalette.blue(color)
            if (red in 131..224 && maxOf(red, green, blue) - minOf(red, green, blue) < 20) bytes[index] = 0xff.toByte()
        }
        val mask = Mat(height, width, CvType.CV_8UC1)
        mask.put(0, 0, bytes)
        val lines = Mat()
        val minimum = min(width, height)
        Imgproc.HoughLinesP(mask, lines, 1.0, Math.PI / 180.0, max(25, (minimum / 6.0).roundToInt()), minimum * 0.39, max(2.0, minimum * 0.004))
        val values = ArrayList<Pair<Float, Float>>()
        for (index in 0 until lines.rows()) {
            val coordinates = lines.get(index, 0) ?: continue
            val dx = abs(coordinates[2] - coordinates[0])
            val dy = abs(coordinates[3] - coordinates[1])
            if (vertical && dx <= 3 && dy > minimum * 0.39) values.add(((coordinates[0] + coordinates[2]) / 2.0).toFloat() to dy.toFloat())
            if (!vertical && dy <= 3 && dx > minimum * 0.39) values.add(((coordinates[1] + coordinates[3]) / 2.0).toFloat() to dx.toFloat())
        }
        lines.release()
        mask.release()
        val sorted = values.sortedBy { it.first }
        val merged = ArrayList<MutableList<Float>>()
        for ((position, _) in sorted) {
            if (merged.isEmpty() || abs(merged.last().average() - position) > 2.5) merged.add(mutableListOf(position)) else merged.last().add(position)
        }
        return merged.map { it.average().toFloat() }
    }

    private fun findGridRun(lines: List<Float>, minimum: Int): List<Float>? {
        if (lines.size < 24) return null
        val differences = lines.zipWithNext { first, second -> second - first }.sorted()
        val basePitch = differences[differences.size / 3]
        var best: List<Float>? = null
        var bestCount = 0
        var bestError = Float.POSITIVE_INFINITY
        for (start in lines.indices) for (next in start + 1 until lines.size) {
            val steps = ((lines[next] - lines[start]) / basePitch).roundToInt().coerceIn(1, 26)
            val pitch = (lines[next] - lines[start]) / steps
            if (pitch !in minimum / 80f..minimum / 12f) continue
            val matched = ArrayList<Float>()
            var error = 0f
            for (step in 0..26) {
                val expected = lines[start] + step * pitch
                val found = lines.minByOrNull { abs(it - expected) } ?: continue
                val difference = abs(found - expected)
                if (difference <= max(2.5f, pitch * 0.12f)) {
                    matched.add(found)
                    error += difference
                } else {
                    matched.add(expected)
                }
            }
            val count = matched.count { actual -> lines.any { abs(it - actual) <= max(2.5f, pitch * 0.12f) } }
            if (matched.size == 27 && count >= 24 && (count > bestCount || (count == bestCount && error < bestError))) {
                best = matched
                bestCount = count
                bestError = error
            }
        }
        return best
    }

    private fun colorAnchor(pixels: IntArray, width: Int, height: Int, grid: GridLocation, reference: Int): PointF? {
        val mask = ByteArray(width * height)
        for (y in grid.top.toInt().coerceAtLeast(0) until grid.bottom.toInt().coerceAtMost(height)) {
            for (x in 0 until (grid.left - grid.cellWidth).toInt().coerceAtMost(width)) {
                val index = y * width + x
                if (channelDistance(pixels[index], reference) <= 16) mask[index] = 0xff.toByte()
            }
        }
        val source = Mat(height, width, CvType.CV_8UC1)
        source.put(0, 0, mask)
        val labels = Mat()
        val stats = Mat()
        val centers = Mat()
        val count = Imgproc.connectedComponentsWithStats(source, labels, stats, centers)
        var largest = 0
        var best: PointF? = null
        for (index in 1 until count) {
            val area = stats.get(index, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area > largest && area > grid.cellWidth * grid.cellHeight * 0.3f) {
                largest = area
                best = PointF(centers.get(index, 0)[0].toFloat(), centers.get(index, 1)[0].toFloat())
            }
        }
        source.release()
        labels.release()
        stats.release()
        centers.release()
        return best
    }

    private fun findSaveIcon(bitmap: Bitmap, grid: GridLocation): PointF? {
        val scale = grid.cellWidth / 22f
        val width = (icon.width * scale).roundToInt().coerceAtLeast(10)
        val height = (icon.height * scale).roundToInt().coerceAtLeast(10)
        val left = (grid.right + grid.cellWidth).roundToInt()
        val top = grid.top.roundToInt()
        val right = bitmap.width
        val bottom = min(bitmap.height, (grid.bottom + grid.cellHeight).roundToInt())
        if (right - left < width || bottom - top < height) return null
        val resized = Bitmap.createScaledBitmap(icon, width, height, true)
        val screenshotMat = Mat()
        val iconMat = Mat()
        Utils.bitmapToMat(bitmap, screenshotMat)
        Utils.bitmapToMat(resized, iconMat)
        val screenshotRgb = Mat()
        val iconRgb = Mat()
        Imgproc.cvtColor(screenshotMat, screenshotRgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(iconMat, iconRgb, Imgproc.COLOR_RGBA2RGB)
        val result = Mat()
        val search = screenshotRgb.submat(Rect(left, top, right - left, bottom - top))
        Imgproc.matchTemplate(search, iconRgb, result, Imgproc.TM_CCOEFF_NORMED)
        val match = Core.minMaxLoc(result)
        val center = if (match.maxVal >= 0.87) PointF(left + match.maxLoc.x.toFloat() + width / 2f, top + match.maxLoc.y.toFloat() + height / 2f) else null
        search.release()
        result.release()
        screenshotRgb.release()
        iconRgb.release()
        screenshotMat.release()
        iconMat.release()
        if (resized != icon) resized.recycle()
        return center
    }

    private fun sampleRgb(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int? {
        val centerX = x.roundToInt()
        val centerY = y.roundToInt()
        if (centerX !in 1 until width - 1 || centerY !in 1 until height - 1) return null
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        for (dy in -1..1) for (dx in -1..1) {
            val color = pixels[(centerY + dy) * width + centerX + dx]
            reds.add(BeadPalette.red(color))
            greens.add(BeadPalette.green(color))
            blues.add(BeadPalette.blue(color))
        }
        return BeadPalette.argb(reds.sorted()[4], greens.sorted()[4], blues.sorted()[4])
    }

    private fun medianColor(bitmap: Bitmap, x: Int, y: Int): Int {
        val channels = Array(3) { ArrayList<Int>() }
        for (dy in -2..2) for (dx in -2..2) {
            val color = bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1))
            channels[0].add(BeadPalette.red(color))
            channels[1].add(BeadPalette.green(color))
            channels[2].add(BeadPalette.blue(color))
        }
        return BeadPalette.argb(channels[0].sorted()[12], channels[1].sorted()[12], channels[2].sorted()[12])
    }

    private fun channelDistance(left: Int, right: Int): Int = maxOf(
        abs(BeadPalette.red(left) - BeadPalette.red(right)),
        abs(BeadPalette.green(left) - BeadPalette.green(right)),
        abs(BeadPalette.blue(left) - BeadPalette.blue(right)),
    )
}
