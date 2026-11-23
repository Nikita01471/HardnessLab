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
        val s = series(point(45.0,448.0,492.0),point(46.0,451.0,500.0)).copy(mixedBrinell=true)
        assertNull(table.convert(s,0,1,45.5).value)
        assertNull(table.convert(s,1,2,450.0).value)
        assertEquals(500.0,table.convert(s,1,2,451.0).value!!,0.0)
        assertEquals(449.5,table.convert(s.copy(mixedBrinell=false),0,1,45.5).value!!,1e-9)
    }
    @Test fun invalidAndUnverified() {
        val s = series(point(72.0,500.0,600.0))
        assertNull(table.convert(s,0,2,72.0).value)
        assertNull(table.convert(s,2,0,600.0).value)
        assertNull(table.convert(s,1,2,Double.NaN).value)
        assertNull(table.convert(s,1,2,0.0).value)
        assertNull(table.convert(s.copy(checked=false),1,2,500.0).value)
    }
    private fun asset() = listOf(File("src/main/assets/hardness_conversion.tsv"),File("app/src/main/assets/hardness_conversion.tsv")).first { it.isFile }
    @Test fun restoredAssetAndRtmUsesAppendix() {
        val file = asset()
        val data = ConversionTables(file.readLines())
        assertEquals(12,data.series.size)
        assertTrue(data.series.all { it.checked })
        val rtm = data.series.single { it.document.startsWith("РТМ") }
        assertEquals(92,rtm.points.size)
        // RTM table 1: HRCэ 45, HB 406, HV 439. Appendix 2 maps 45 to HRC 43.5.
        assertEquals(439.0,data.convert(rtm,0,2,43.5).value!!,0.0)
        assertEquals(406.0,data.convert(rtm,0,1,43.5).value!!,0.0)
        assertEquals(43.5,data.convert(rtm,2,0,439.0).value!!,0.0)
        assertNull(data.convert(rtm,0,2,64.5).value)
        assertNull(data.convert(rtm,1,2,522.0).value)
        assertNull(data.convert(rtm,1,2,240.0).value)
        assertEquals(242.0,data.convert(rtm,1,2,242.0).value!!,0.0)
        assertEquals(245.0,data.convert(rtm,1,2,246.0).value!!,0.0)
        assertEquals(90,rtm.points.count { it.hrc!=null })
        assertEquals(84,rtm.points.count { it.hv!=null })
        val altered = file.readLines().toMutableList().apply { this[0]=this[0].replace("207.0","208.0") }
        val damaged = ConversionTables(altered)
        assertFalse(damaged.series.single { it.document.startsWith("РТМ") }.checked)
        assertTrue(damaged.series.single { it.table=="A.1" }.checked)
    }
    @Test fun restoredIsoAndAstMReferencePoints() {
        val data = ConversionTables(asset().readLines())
        fun find(tableId: String, doc: String) = data.series.single { it.table==tableId && it.document.startsWith(doc) }
        fun exact(s: ConversionTables.Series, from: Int, to: Int, x: Double, y: Double) =
            assertEquals("${s.document} ${s.table}",y,data.convert(s,from,to,x).value!!,1e-8)
        val a = find("A.1","ISO")
        exact(a,0,1,25.6,257.0); exact(a,0,2,25.6,270.0); exact(a,2,0,270.0,25.6)
        val b = find("B.2","ISO")
        exact(b,0,1,24.6,255.0); exact(b,0,2,24.6,260.0)
        assertNotNull(data.convert(b,1,2,450.0).value) // single carbide-ball column
        val c = find("C.2","ISO")
        exact(c,0,1,25.4,255.0); exact(c,0,2,25.4,260.0)
        assertNull(data.convert(c,2,0,220.0).value) // parenthesized HRC outside method range
        val f = find("F.1","ISO")
        exact(f,0,1,25.5,258.0); exact(f,0,2,25.5,266.0)
        val hbs = find("Табл. 1 · HBS","ASTM")
        val hbw = find("Табл. 1 · HBW","ASTM")
        exact(hbs,0,1,48.0,451.0); exact(hbw,0,1,48.0,455.0)
        exact(hbw,0,1,59.0,634.0); assertNull(data.convert(hbw,0,1,60.0).value)
        assertNull(data.convert(hbs,0,1,49.0).value)
        val t2 = find("Табл. 2 · HRB-диапазон","ASTM")
        exact(t2,1,2,100.0,100.0); assertFalse(t2.supports(0))
        val ni = find("Табл. 3","ASTM")
        exact(ni,0,2,25.5,266.0); assertNull(data.convert(ni,2,0,220.0).value)
        val cu = find("Табл. 7 · HBS 2/20","ASTM")
        exact(cu,2,1,110.0,100.0); exact(cu,1,2,100.0,110.0); assertFalse(cu.supports(0))
        val cu10 = find("Табл. 7 · HBS 10/500","ASTM")
        exact(cu10,2,1,100.0,87.0)
        val al = find("Табл. 9","ASTM")
        exact(al,1,2,100.0,117.0); assertFalse(al.supports(0))
    }
    @Test fun everyRestoredExactPairWorksAndHasValidScale() {
        val dt = ConversionTables(asset().readLines())
        for (s in dt.series) for (from in 0..2) for(to in 0..2) {
            if(from==to) continue
            val pairs=s.points.mapNotNull { p -> val x=p.at(from); val y=p.at(to); if(x!=null && y!=null) x to y else null }
            for((x,y) in pairs) {
                assertTrue("${s.document} ${s.table} HRC invalid",if(from==0) x<=70 else true)
                assertEquals("${s.document} ${s.table} $from to $to at $x", y,dt.convert(s,from,to,x).value!!,1e-8)
            }
        }
    }
}
