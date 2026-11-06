package vn.cnldg.oncam.node.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RomFamilyTest {
    @Test
    fun `nhan dien hang may`() {
        assertEquals(RomFamily.SAMSUNG, RomFamily.of("samsung"))
        assertEquals(RomFamily.COLOR_OS, RomFamily.of("OPPO"))
        assertEquals(RomFamily.COLOR_OS, RomFamily.of("realme"))
        assertEquals(RomFamily.COLOR_OS, RomFamily.of("OnePlus"))
        assertEquals(RomFamily.OTHER, RomFamily.of("Xiaomi"))
        assertEquals(RomFamily.OTHER, RomFamily.of(""))
    }
}
