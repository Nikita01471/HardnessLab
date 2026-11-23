package com.hardnesslab.app

import kotlin.math.abs
import java.security.MessageDigest

/** The original TSV remains intact; only source-checked pairs are eligible for calculation. */
class ConversionTables(lines: Iterable<String>) {
    data class Point(val hrc: Double?, val hb: Double?, val hv: Double?) {
        fun at(scale: Int): Double? = when (scale) { 0 -> hrc; 1 -> hb; 2 -> hv; else -> null }
    }
    data class Series(val document: String, val material: String, val points: List<Point>,
                      val checked: Boolean = false, val note: String = "Требуется сверка с первоисточником",
                      val table: String = "", val hbLabel: String = "HB/HBW", val hvLabel: String = "HV",
                      val mixedBrinell: Boolean = false) {
        fun label(scale: Int): String = when(scale) { 0 -> "HRC"; 1 -> hbLabel; 2 -> hvLabel; else -> "" }
        fun supports(from: Int): Boolean = checked && points.any { p -> p.at(from) != null && (0..2).any { it != from && p.at(it) != null } }
    }
    data class Answer(val value: Double?, val status: String, val nearest: Double? = null,
                      val nearestInput: Double? = null)
    val series: List<Series>

    init {
        val data = linkedMapOf<Pair<String, String>, MutableList<Point>>()
        val raw = linkedMapOf<Pair<String, String>, MutableList<String>>()
        for (line in lines) {
            val a = line.split('\t', limit = 5)
            if (a.size != 5 || a[0].isBlank() || a[1].isBlank()) continue
            fun number(i: Int) = a[i].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            data.getOrPut(a[0] to a[1]) { mutableListOf() }.add(Point(number(2), number(3), number(4)))
            raw.getOrPut(a[0] to a[1]) { mutableListOf() }.add(line)
        }
        fun digest(values: List<String>) = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2,'0') }
        fun prefix(doc: String, material: String, count: Int, hash: String): List<Point>? {
            val key = doc to material
            val rows = raw[key]?.take(count) ?: return null
            if(rows.size != count || digest(rows) != hash) return null
            return data[key]!!.take(count)
        }
        val iso = "ISO 18265:2013"
        val astm = "ASTM E140-12b (2019)e1"
        val rtm = "РТМ 3-1947-91"
        val result = mutableListOf<Series>()
        fun add(doc: String, material: String, rows: List<Point>?, table: String, note: String,
                hb: String = "HB/HBW", hv: String = "HV", mixed: Boolean = false) {
            result += Series(doc,material,rows.orEmpty(),rows != null,
                if(rows == null) "Справочник не прошёл проверку целостности" else note,table,hb,hv,mixed)
        }
        // Only the identified prefix of each old group matches the named printed table.
        // Remaining, mislabelled/permuted blocks in the immutable legacy asset are never used.
        val rtmRows = prefix(rtm,"Сталь",92,"53d145a0c1270c2b0436a885f8500e7383fa9af815e310f491db78c8306a6148")
        if(rtmRows != null) {
                // Both duplicate HRCэ entries and four non-monotone HV entries are present
                // in the actual printed RTM, not merely OCR errors; retain them as gaps.
                val points = rtmRows.map rowMap@ { row ->
                    val legacy = row.hrc ?: return@rowMap Point(null,null,null)
                    val hrc = when {
                        legacy * 2 != (legacy * 2).toInt().toDouble() -> null
                        legacy in 20.0..34.5 -> legacy - 2.0
                        legacy in 35.5..51.0 -> legacy - 1.5
                        legacy in 52.0..65.5 -> legacy - 1.0
                        else -> null
                    }
                    // Verified against the scanned table, pp. 4 and 7.
                    val hv = when(legacy) {
                        26.0 -> 242.0
                        26.5 -> 245.0
                        25.5,52.5,54.0,54.5 -> null
                        else -> row.hv
                    }
                    Point(hrc, row.hb, hv)
                }
                add(rtm,"Сталь",points,"Табл. 1 + прил. 2","HRC из HRCэ · HB/HBW 10/3000 · спорные ячейки исключены","HB/HBW","HV30",true)
        } else add(rtm,"Сталь",null,"Табл. 1 + прил. 2","")

        val a1 = prefix(iso,"Низколегированные стали",97,"4a8db6a0a2767c7c66a77fa5f29ee8fbb59887c69257875ffd9d2d7256f9570d")
        add(iso,"Нелегированные, низколегированные стали и стальное литьё",a1,"A.1","Справочные оценки · стальной шарик до 450 HB, выше HBW","HB/HBW","HV10",true)
        val b2 = prefix(iso,"Закалённые и отпущенные стали",45,"27ca5285f604f7c16a6f1fa1473e1dc740f52f140c1bd08aa0765c20027f2ace")
        add(iso,"Закалённые и отпущенные стали",b2,"B.2","Только закалённое и отпущенное состояние · HBW, HV обычно HV30","HBW","HV")
        val c2 = prefix(iso,"Стали холодной обработки",64,"e4caccfc6dd17d7701f5268d6aaac5d3758b08267cf8fe5cc1bf5663273ec064")
            ?.map { if(it.hv==220.0) it.copy(hrc=null) else it }
        add(iso,"Стали холодной обработки",c2,"C.2","Закалённое и отпущенное состояние · HV обычно HV30 · вне диапазона метода исключено","HB/HBW","HV",true)
        fun nickel(rows: List<Point>?) = rows?.map { p -> p.copy(hrc=p.hrc?.takeIf { it>=20 },hb=p.hb?.takeIf { it<=450 }) }
        val niIso = prefix(iso,"Никелевые стали",48,"a73856792167a9de424f9501e9aa70086a5117439966d6f2cb3c0b40653b595e")
        add(iso,"Никель и высоконикелевые сплавы",nickel(niIso),"F.1","Перепечатка ASTM E140-97 · HBS 10/3000 · HV1/5/10/30","HBS","HV")

        val t1 = prefix("ASTM E140","Неаустенитные стали",49,"063f3541e84315d4fe9b787f439bbac1fea9e08d16c0516a902e194a64fc56bb")
        add(astm,"Неаустенитные стали",t1?.map { it.copy(hb=it.hb?.takeIf { _ -> it.hrc!!<=48 }) },"Табл. 1 · HBS","Однородные стали · стальной шарик 10/3000 · значения в скобках исключены","HBS","HV")
        // Table 1 separate carbide-ball column, confirmed also against a second copy.
        val carbide = mapOf(47 to 443,48 to 455,49 to 469,50 to 481,51 to 496,52 to 512,
            53 to 525,54 to 543,55 to 560,56 to 577,57 to 595,58 to 615,59 to 634)
        val t1w = t1?.map { p -> val x=p.hrc!!.toInt(); p.copy(hb=if(x<=46) p.hb else carbide[x]?.toDouble()) }
        add(astm,"Неаустенитные стали",t1w,"Табл. 1 · HBW","Однородные стали · твердосплавный шарик 10/3000 · значения в скобках исключены","HBW","HV")
        // Table 2 is independent of the Rockwell C range. Only verified HBS/HV pairs are shown.
        val t2 = listOf(100,101,103,104,106,107,108,110,112,114,116,117,119,121,123,125,127,130,132,135,137,139,141,144,147,150,153,156,159,162,165,169,172,176,180,185,190,195,200,205,210,216,222,228,234,240)
            .map { Point(null,it.toDouble(),it.toDouble()) }
        add(astm,"Неаустенитные стали",t2,"Табл. 2 · HRB-диапазон","HBS 10/3000 · только HBS ↔ HV; HRC не предусмотрена","HBS","HV")
        val niAstm = prefix("ASTM E140","Никелевые стали",48,"697ab4e44a1acb90578420963cf7a36743df37e957ba5c562c106c8a5e936e7d")
        add(astm,"Никель и высоконикелевые сплавы",nickel(niAstm),"Табл. 3","Никель >50 % · HBS 10/3000 · HV1/5/10/30","HBS","HV")
        val cu = prefix("ASTM E140","Медь",43,"21a0807973e95d40cc6f7aed79cf92c3f91fb433e744f412ed4fce831e4c1b08")
            ?.map { if(it.hv==110.0) it.copy(hb=100.0) else it }
        add(astm,"Медь",cu,"Табл. 7 · HBS 2/20","Медь №102–142 · толщина 1,02 мм · HBS 2/20","HBS","HV1")
        val cu10 = listOf(120.0,117.5,115.0,112.0,110.0,107.0,105.0,102.0,99.5,97.0,94.5,92.0,89.5,87.0,84.5,82.0,79.5,77.0,74.5)
            .mapIndexed { i,hb -> Point(null,hb,(126-i*2).toDouble()) }.reversed()
        add(astm,"Медь",cu10,"Табл. 7 · HBS 10/500","Медь №102–142 · толщина 2,03 мм · HBS 10/500","HBS","HV1")
        val al = prefix("ASTM E140","Деформируемый алюминий",25,"f2bca7c7177b1d2529aad956087ea0bd557f40b5a256717ba48e45f7dddd51ba")
        add(astm,"Деформируемый алюминий",al,"Табл. 9","HBS 10/500 · HV15 · HRC не предусмотрена","HBS","HV15")
        series = result
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
        // Only mixed-ball tables switch ball material at 450. Fixed HBS/HBW series do not.
        if (s.mixedBrinell && (from == 1 && a.first <= 450 && b.first > 450 ||
            to == 1 && a.second <= 450 && b.second > 450))
            return unavailable("Граница HB/HBW")
        return Answer(a.second + (x - a.first) * (b.second - a.second) / (b.first - a.first), "Интерполяция")
    }
}
