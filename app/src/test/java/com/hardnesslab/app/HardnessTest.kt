package com.hardnesslab.app

import org.junit.Assert.*
import org.junit.Test

class HardnessTest {
    @Test fun vickersUnitsAndMicroscope() {
        val mm = Hardness.vickers(0.5, 0.5, 30.0, 0, false)
        assertEquals(222.48,mm,1e-8)
        assertEquals(mm,Hardness.vickers(1000.0,1000.0,30.0,2,false),1e-8)
        assertEquals(mm,Hardness.vickers(0.5,0.5,30.0*9.80665,0,true),0.1)
    }
    @Test fun brinellUnits() {
        val kgf = Hardness.brinell(10.0,4.0,4.0,3000.0,false,false)
        assertEquals(kgf,Hardness.brinell(10.0,400.0,400.0,3000.0,false,true),1e-8)
        assertEquals(kgf,Hardness.brinell(10.0,4.0,4.0,3000.0*9.80665,true,false),0.1)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidImprint() {
        Hardness.brinell(10.0,10.0,10.0,3000.0,false,false)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNegativeDiagonal() {
        Hardness.vickers(-1.0,2.0,30.0,0,false)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOneOversizedImprint() {
        Hardness.brinell(5.0,1.0,6.0,100.0,false,false)
    }
    @Test fun workbookExamples() {
        assertEquals(1891.9647959183667,Hardness.vickers(0.14,0.14,196.1,0,true),1e-8)
        assertEquals(210.23975056689346,Hardness.vickers(210.0,210.0,49.03,1,true),1e-8)
        assertEquals(1287.8497916666665,Hardness.vickers(240.0,240.0,98.07,2,true),1e-8)
        assertEquals(37.83543515967758,Hardness.vickers(320.0,340.0,1.961,3,true),1e-8)
        assertEquals(94.59183673469387,Hardness.vickers(0.14,0.14,1.0,0,false),1e-8)
        assertEquals(210.2040816326531,Hardness.vickers(210.0,210.0,5.0,1,false),1e-8)
        assertEquals(512.4307292602368,Hardness.vickers(271.0,267.0,5.0,2,false),1e-8)
        assertEquals(433.3990795529257,Hardness.vickers(99.0,96.0,0.2,3,false),1e-8)
        assertEquals(31.21917833513282,Hardness.brinell(5.0,2.2,2.2,1226.0,true,false),1e-8)
        assertEquals(130.95507121966898,Hardness.brinell(2.5,0.78,0.76,62.5,false,false),1e-8)
        assertEquals(127.53347636322259,Hardness.brinell(2.5,77.0,79.0,62.5,false,true),1e-8)
    }
}
