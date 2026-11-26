package com.togotiming.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPaletteTest {

    private fun distance(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
    }

    @Test
    fun rootPaletteCyclesInOrder() {
        assertEquals(0xFFE53935.toInt(), ColorPalette.rootColor(0))
        assertEquals(0xFFFB8C00.toInt(), ColorPalette.rootColor(1))
        assertEquals(0xFF546E7A.toInt(), ColorPalette.rootColor(11))
        assertEquals(0xFFE53935.toInt(), ColorPalette.rootColor(12))
        assertEquals(0xFF546E7A.toInt(), ColorPalette.rootColor(-1))
    }

    @Test
    fun parseHexAcceptsBothForms() {
        assertEquals(0xFFE53935.toInt(), ColorPalette.parseHex("#E53935"))
        assertEquals(0xFFE53935.toInt(), ColorPalette.parseHex("e53935"))
        assertEquals(0xFF000000.toInt(), ColorPalette.parseHex("#000000"))
    }

    @Test
    fun parseHexRejectsInvalid() {
        assertNull(ColorPalette.parseHex("#12345"))
        assertNull(ColorPalette.parseHex("#GGGGGG"))
        assertNull(ColorPalette.parseHex(""))
        assertNull(ColorPalette.parseHex("#1234567"))
    }

    @Test
    fun toHexIsUppercaseWithHash() {
        assertEquals("#E53935", ColorPalette.toHex(0xFFE53935.toInt()))
        assertEquals("#0A0B0C", ColorPalette.toHex(0xFF0A0B0C.toInt()))
    }

    @Test
    fun childColorMatchesGuideApproximation() {
        val parent = 0xFFE53935.toInt()
        val hsl = ColorPalette.rgbToHsl(parent)
        assertEquals(1f, hsl[0], 4f)
        assertEquals(0.75f, hsl[1], 0.05f)
        assertEquals(0.55f, hsl[2], 0.05f)

        val i0 = ColorPalette.childColor(parent, 0)
        val i2 = ColorPalette.childColor(parent, 2)
        // 指南示例 #C62828 / #EF5350 为近似值，这里是公式实现，颜色族与明暗递进必须正确
        assertTrue("i0=$i0", distance(i0, 0xFFC62828.toInt()) < 40)
        assertTrue("i2=$i2", ColorPalette.rgbToHsl(i2)[2] > ColorPalette.rgbToHsl(i0)[2])
        assertEquals(1f, ColorPalette.rgbToHsl(i2)[0], 8f)
        assertTrue("i2=$i2", distance(i2, 0xFFEF5350.toInt()) < 120)
    }

    @Test
    fun childColorsAreStableAndDistinct() {
        val parent = 0xFF1E88E5.toInt()
        val first = ColorPalette.childColor(parent, 0)
        val again = ColorPalette.childColor(parent, 0)
        assertEquals(first, again)
        val second = ColorPalette.childColor(parent, 1)
        assertTrue(first != second)
    }

    @Test
    fun childColorClampsAtDeepIndexes() {
        val parent = 0xFFE53935.toInt()
        val deep = ColorPalette.childColor(parent, 20)
        val deep2 = ColorPalette.childColor(parent, 40)
        assertNotNull(deep)
        assertNotNull(deep2)
        // 亮度 clamp 到 0.75，深色仍可读
        val hsl = ColorPalette.rgbToHsl(deep)
        assertTrue(hsl[2] <= 0.76f)
    }
}
