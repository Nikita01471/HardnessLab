package com.hardnesslab.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConversionTablesTest {
    private val table = ConversionTables(emptyList())
    private fun series(vararg values: ConversionTables.Point) =
        ConversionTables.Series("test", "steel", values.toList(), checked = true)
    private fun point(hrc: Double?, hb: Double?, hv: Double?) = ConversionTables.Point(hrc,hb,hv)

    @Test fun exactAndInterpolation() {
        val s = series(point(30.0,300.0,310.0), point(31.0,310.0,330.0))
        assertEquals(310.0, table.convert(s,0,2,30.0).value!!, 0.0)
        assertEquals(320.0, table.convert(s,0,2,30.5).value!!, 1e-9)
        assertEquals(30.5, table.convert(s,2,0,320.0).value!!, 1e-9)
        assertEquals(330.0, table.convert(s,0,2,31.0).value!!, 0.0)
    }
    @Test fun noExtrapolationAndNearest() {
        val s = series(point(30.0,300.0,310.0), point(31.0,310.0,330.0))
        val a = table.convert(s,0,2,29.0)
        assertNull(a.value); assertEquals(30.0,a.nearestInput!!,0.0); assertEquals(310.0,a.nearest!!,0.0)
        assertNull(table.convert(s,0,2,31.01).value)
    }
    @Test fun missingCellIsNotBridged() {
        val s = series(point(30.0,300.0,310.0),point(31.0,310.0,null),point(32.0,320.0,350.0))
        assertNull(table.convert(s,0,2,31.0).value)
        assertNull(table.convert(s,0,2,30.5).value)
        assertNull(table.convert(s,2,0,330.0).value)
        assertEquals(310.0,table.convert(s,0,1,31.0).value!!,0.0)
    }
    @Test fun duplicateConflictsAndInversions() {
        val duplicate = series(point(30.0,300.0,310.0),point(30.0,310.0,320.0))
        assertNull(table.convert(duplicate,0,2,30.0).value)
        val reversed = series(point(30.0,300.0,330.0),point(31.0,310.0,310.0))
        assertNull(table.convert(reversed,0,2,30.5).value)
        val descending = series(point(31.0,310.0,330.0),point(30.0,300.0,310.0))
        assertEquals(320.0,table.convert(descending,0,2,30.5).value!!,0.0)
    }
    @Test fun ballMaterialBoundary() {
        val s = series(point(45.0,448.0,492.0),point(46.0,451.0,500.0))
        assertNull(table.convert(s,0,1,45.5).value)
        assertNull(table.convert(s,1,2,450.0).value)
        assertEquals(500.0,table.convert(s,1,2,451.0).value!!,0.0)
    }
    @Test fun invalidAndUnverified() {
        val s = series(point(72.0,500.0,600.0))
        assertNull(table.convert(s,0,2,72.0).value)
        assertNull(table.convert(s,2,0,600.0).value)
        assertNull(table.convert(s,1,2,Double.NaN).value)
        assertNull(table.convert(s,1,2,0.0).value)
        assertNull(table.convert(s.copy(checked=false),1,2,500.0).value)
    }
    @Test fun realAssetIsQuarantinedAndRtmUsesAppendix() {
        val file = listOf(File("src/main/assets/hardness_conversion.tsv"),File("app/src/main/assets/hardness_conversion.tsv")).first { it.isFile }
        val data = ConversionTables(file.readLines())
        assertEquals(9,data.series.size)
        assertEquals(1,data.series.count { it.checked })
        assertEquals(8,data.series.count { !it.checked && it.points.isEmpty() })
        val rtm = data.series.single { it.checked }
        assertEquals(92,rtm.points.size)
        // RTM table 1: HRCэ 45, HB 406, HV 439. Appendix 2 maps 45 to HRC 43.5.
        assertEquals(439.0,data.convert(rtm,0,2,43.5).value!!,0.0)
        assertEquals(406.0,data.convert(rtm,0,1,43.5).value!!,0.0)
        assertEquals(43.5,data.convert(rtm,2,0,439.0).value!!,0.0)
        assertNull(data.convert(rtm,0,2,64.5).value)
        assertNull(data.convert(rtm,1,2,522.0).value)
        assertNull(data.convert(rtm,1,2,240.0).value)
        assertNull(data.convert(rtm,1,2,242.0).value)
        assertNull(data.convert(rtm,1,2,246.0).value)
        assertEquals(90,rtm.points.count { it.hrc!=null })
        assertEquals(82,rtm.points.count { it.hv!=null })
        val altered = file.readLines().toMutableList().apply { this[0]=this[0].replace("207.0","208.0") }
        assertFalse(ConversionTables(altered).series.any { it.checked })
    }
}
