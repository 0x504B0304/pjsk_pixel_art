package dev.pjsk.beadpainter

import kotlin.math.pow
import kotlin.math.roundToInt

data class BeadColor(val code: String, val rgb: Int)

data class Lab(val lightness: Double, val a: Double, val b: Double) {
    fun distanceSquared(other: Lab): Double {
        val dl = lightness - other.lightness
        val da = a - other.a
        val db = b - other.b
        return dl * dl + da * da + db * db
    }
}

object BeadPalette {
    const val EMPTY = -1
    const val WHITE = 42
    private val codes = arrayOf(
        "A4", "A6", "A7", "A10", "A11", "A13", "B3", "B5",
        "B8", "B12", "C2", "C3", "C5", "C6", "C7", "C8",
        "C10", "C11", "C13", "D3", "D6", "D7", "D9", "D13",
        "D15", "D18", "D19", "D21", "E2", "E3", "E4", "E7",
        "E8", "F5", "F8", "F13", "G1", "G5", "G7", "G8",
        "G9", "G13", "H2", "H3", "H4", "H5", "H7",
    )

    // Adapted from MaaAssistantArknights PixelPaintHelper (AGPL-3.0).
    // RGB values were sampled from the actual PJSK 6.4.0 palette, not MAA's 40-color palette.
    private val rgb = arrayOf(
        intArrayOf(255, 235, 92), intArrayOf(249, 166, 86), intArrayOf(255, 127, 80), intArrayOf(249, 162, 61),
        intArrayOf(255, 215, 155), intArrayOf(252, 182, 58), intArrayOf(182, 255, 201), intArrayOf(79, 210, 105),
        intArrayOf(24, 154, 68), intArrayOf(20, 144, 105), intArrayOf(200, 245, 255), intArrayOf(186, 234, 254),
        intArrayOf(33, 183, 242), intArrayOf(77, 166, 237), intArrayOf(45, 139, 232), intArrayOf(34, 92, 200),
        intArrayOf(76, 194, 227), intArrayOf(18, 196, 208), intArrayOf(200, 230, 255), intArrayOf(32, 54, 166),
        intArrayOf(179, 140, 232), intArrayOf(122, 77, 179), intArrayOf(216, 194, 255), intArrayOf(184, 0, 160),
        intArrayOf(50, 34, 143), intArrayOf(160, 94, 190), intArrayOf(231, 216, 232), intArrayOf(142, 63, 163),
        intArrayOf(255, 208, 225), intArrayOf(241, 154, 198), intArrayOf(237, 127, 179), intArrayOf(153, 14, 94),
        intArrayOf(255, 226, 223), intArrayOf(230, 0, 38), intArrayOf(184, 0, 36), intArrayOf(240, 90, 70),
        intArrayOf(255, 233, 203), intArrayOf(227, 154, 88), intArrayOf(160, 111, 73), intArrayOf(92, 54, 36),
        intArrayOf(237, 188, 138), intArrayOf(179, 114, 64), intArrayOf(255, 255, 255), intArrayOf(192, 192, 192),
        intArrayOf(136, 136, 136), intArrayOf(68, 68, 68), intArrayOf(0, 0, 0),
    )

    val colors: List<BeadColor> = codes.indices.map { index ->
        val value = rgb[index]
        BeadColor(codes[index], argb(value[0], value[1], value[2]))
    }
    val labs: List<Lab> = colors.map { toLab(it.rgb) }

    fun nearestIndex(red: Double, green: Double, blue: Double): Int {
        val lab = toLab(argb(red.roundToInt().coerceIn(0, 255), green.roundToInt().coerceIn(0, 255), blue.roundToInt().coerceIn(0, 255)))
        return labs.indices.minBy { labs[it].distanceSquared(lab) }
    }

    fun argb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    fun red(color: Int): Int = (color ushr 16) and 0xff
    fun green(color: Int): Int = (color ushr 8) and 0xff
    fun blue(color: Int): Int = color and 0xff

    fun toLab(color: Int): Lab {
        val red = linear(red(color).toDouble())
        val green = linear(green(color).toDouble())
        val blue = linear(blue(color).toDouble())
        val l = Math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
        val m = Math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
        val s = Math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
        return Lab(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    fun linear(value: Double): Double {
        val normalized = value / 255.0
        return if (normalized >= 0.04045) ((normalized + 0.055) / 1.055).pow(2.4) else normalized / 12.92
    }

    fun encoded(value: Double): Double =
        (if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055) * 255.0
}
