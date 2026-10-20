package app.purse.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Amounts are stored as Long minor units (paise / cents) everywhere. */
object Money {
    private val NUMBER = Regex("""-?\d[\d,]*(?:\.\d+)?""")
    private val FRACTION_ONLY = Regex("""-?\.\d+""")

    /** Parses "2,135.50", "Rs.2135", "1.5" into minor units. Returns null if no number is present. */
    fun parseMinor(text: String): Long? {
        val compact = text.replace(" ", "")
        val raw = (NUMBER.find(compact) ?: FRACTION_ONLY.find(compact))?.value ?: return null
        return try {
            BigDecimal(raw.replace(",", "")).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Plain editable form without grouping or symbol: 213550 -> "2135.5", 200 -> "2". */
    fun toPlain(minor: Long): String =
        BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()

    /**
     * Formats minor units for display. Decimals are shown only when non-zero.
     * Indian grouping gives 12,34,567; western gives 1,234,567.
     */
    fun format(minor: Long, symbol: String, indianGrouping: Boolean, signed: Boolean = false): String {
        val a = abs(minor)
        val whole = group((a / 100).toString(), indianGrouping)
        val frac = a % 100
        val fracStr = if (frac == 0L) "" else "." + frac.toString().padStart(2, '0')
        val sign = when {
            minor < 0 -> "-"
            signed && minor > 0 -> "+"
            else -> ""
        }
        return "$sign$symbol$whole$fracStr"
    }

    /** Short form for tight spaces such as calendar cells: 2135 -> 2.1k, 250000 -> 2.5L. */
    fun compact(minor: Long, indianGrouping: Boolean): String {
        val v = abs(minor) / 100.0
        val sign = if (minor < 0) "-" else ""
        val units = if (indianGrouping) {
            listOf(1e7 to "Cr", 1e5 to "L", 1e3 to "k")
        } else {
            listOf(1e9 to "B", 1e6 to "M", 1e3 to "k")
        }
        for ((size, suffix) in units) {
            if (v >= size) return sign + trim1(v / size) + suffix
        }
        return sign + v.toLong().toString()
    }

    private fun trim1(d: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.1f", d)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun group(digits: String, indian: Boolean): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val size = if (indian) 2 else 3
        val parts = rest.reversed().chunked(size).map { it.reversed() }.reversed()
        return parts.joinToString(",") + "," + last3
    }
}
