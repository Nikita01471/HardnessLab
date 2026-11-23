package com.hardnesslab.app

import kotlin.math.abs
import java.security.MessageDigest

/** The original TSV remains intact; only source-checked pairs are eligible for calculation. */
class ConversionTables(lines: Iterable<String>) {
    data class Point(val hrc: Double?, val hb: Double?, val hv: Double?) {
        fun at(scale: Int): Double? = when (scale) { 0 -> hrc; 1 -> hb; 2 -> hv; else -> null }
    }
    data class Series(val document: String, val material: String, val points: List<Point>,
                      val checked: Boolean = false, val note: String = "Требуется сверка с первоисточником")
    data class Answer(val value: Double?, val status: String, val nearest: Double? = null,
                      val nearestInput: Double? = null)
    val series: List<Series>

    init {
        val data = linkedMapOf<Pair<String, String>, MutableList<Point>>()
        val rtmRaw = mutableListOf<String>()
        for (line in lines) {
            val a = line.split('\t', limit = 5)
            if (a.size != 5 || a[0].isBlank() || a[1].isBlank()) continue
            fun number(i: Int) = a[i].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            data.getOrPut(a[0] to a[1]) { mutableListOf() }.add(Point(number(2), number(3), number(4)))
            if (a[0] == "РТМ 3-1947-91" && a[1] == "Сталь") rtmRaw.add(line)
        }
        val rtmDigest = MessageDigest.getInstance("SHA-256").digest(rtmRaw.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2,'0') }
        series = data.map { (key, rows) ->
            if (key.first == "РТМ 3-1947-91" && key.second == "Сталь" &&
                rows.size == 92 && rtmDigest == "53d145a0c1270c2b0436a885f8500e7383fa9af815e310f491db78c8306a6148") {
                // Table 1 uses HRCэ. Appendix 2 maps HRCэ to HRC. The accessible text copy
                // is ambiguous at 35 and 51.5; do not infer a correction at either point.
                val points = rows.map rowMap@ { row ->
                    val legacy = row.hrc ?: return@rowMap Point(null,null,null)
                    val hrc = when {
                        legacy * 2 != (legacy * 2).toInt().toDouble() -> null
                        legacy in 20.0..34.5 -> legacy - 2.0
                        legacy in 35.5..51.0 -> legacy - 1.5
                        legacy in 52.0..65.5 -> legacy - 1.0
                        else -> null
                    }
                    // Values disagree between the supplied asset and the accessible text copy.
                    // Preserve the raw data in the TSV and leave these cells unavailable.
                    val hv = if (legacy in setOf(25.5, 26.0, 26.5, 52.5, 54.0, 54.5)) null else row.hv
                    Point(hrc, row.hb, hv)
                }
                Series(key.first, key.second, points, true,
                    "Табл. 1 + прил. 2; HRC из HRCэ. Сверка по текстовой копии, отдельные ячейки исключены. HB/HBW: D=10 мм, 3000 кгс; HV30.")
            } else Series(key.first, key.second, emptyList(), false,
                "Справочник содержит смешанные блоки; нужен исходный Excel и таблица стандарта")
        }
    }

    fun convert(s: Series, from: Int, to: Int, x: Double): Answer {
        if (!x.isFinite() || x <= 0 || from !in 0..2 || to !in 0..2 || from == to)
            return Answer(null, "Недопустимое значение")
        if (!s.checked) return Answer(null, "Не подтверждено источником")
        fun valid(scale: Int, value: Double?) = value != null && value.isFinite() && value > 0 &&
            (scale != 0 || value <= 70)
        fun pair(p: Point): Pair<Double, Double>? {
            val a = p.at(from); val b = p.at(to)
            return if (valid(from, a) && valid(to, b)) a!! to b!! else null
        }
        val pairs = s.points.mapNotNull(::pair)
        if (pairs.isEmpty()) return Answer(null, "Перевод не предусмотрен")
        val unique = pairs.distinct()
        if (unique.groupBy { it.first }.any { it.value.size > 1 })
            return Answer(null, "Неоднозначные табличные значения")
        val closest = unique.minByOrNull { abs(it.first - x) }!!
        val lo = unique.minOf { it.first }; val hi = unique.maxOf { it.first }
        fun unavailable(status: String) = Answer(null, status, closest.second, closest.first)
        if (x < lo || x > hi) return unavailable("Вне диапазона")
        unique.firstOrNull { abs(it.first - x) < 1e-8 }?.let { return Answer(it.second, "Табличное значение") }
        if (s.points.any { it.at(from)?.let { a -> abs(a - x) < 1e-8 } == true })
            return unavailable("Нет подтверждённой пары в строке")
        // Only adjacent source rows may be interpolated. Missing cells are real gaps.
        val brackets = s.points.zipWithNext().mapNotNull { (a, b) ->
            val p = pair(a) ?: return@mapNotNull null
            val q = pair(b) ?: return@mapNotNull null
            val left = if (p.first < q.first) p else q
            val right = if (p.first < q.first) q else p
            if (x > left.first && x < right.first) left to right else null
        }.distinct()
        if (brackets.size != 1) return unavailable("Нет однозначных соседних строк")
        val (a, b) = brackets.single()
        if (b.second < a.second) return unavailable("Нарушена монотонность таблицы")
        // RTM switches the Brinell ball material at 450; do not interpolate across it.
        if (from == 1 && a.first < 450 && b.first >= 450 ||
            to == 1 && a.second < 450 && b.second >= 450)
            return unavailable("Граница HB/HBW")
        return Answer(a.second + (x - a.first) * (b.second - a.second) / (b.first - a.first), "Интерполяция")
    }
}
