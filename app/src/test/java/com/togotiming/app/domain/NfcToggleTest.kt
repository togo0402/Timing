package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.NfcBindingEntity
import com.togotiming.app.data.TimeRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcToggleTest {

    private val study = ActivityTypeEntity(id = 1, name = "学习", colorArgb = 0xFF1E88E5.toInt(), path = "学习")
    private val work = ActivityTypeEntity(id = 2, name = "工作", colorArgb = 0xFFE53935.toInt(), path = "工作")
    private val binding = NfcBindingEntity(id = 1, tagUid = "A1B2C3", typeId = 1, enabled = true)

    @Test
    fun noActiveStartsBoundType() {
        val d = NfcToggle.decide(true, binding, study, null)
        assertEquals(NfcDecision.Start(1), d)
    }

    @Test
    fun sameTagStopsCurrent() {
        val active = TimeRecordEntity(id = 10, typeId = 1, startAt = 1000L)
        assertEquals(NfcDecision.StopCurrent, NfcToggle.decide(true, binding, study, active))
    }

    @Test
    fun differentTypeAsksSwitch() {
        val active = TimeRecordEntity(id = 10, typeId = 2, startAt = 1000L)
        val d = NfcToggle.decide(true, binding, study, active)
        assertTrue(d is NfcDecision.AskSwitch)
        assertEquals(2L, (d as NfcDecision.AskSwitch).activeRecord.typeId)
        assertEquals(1L, d.targetTypeId)
    }

    @Test
    fun disabledOrMissingBindingIsIgnored() {
        assertEquals(NfcDecision.Ignore, NfcToggle.decide(true, null, study, null))
        assertEquals(NfcDecision.Ignore, NfcToggle.decide(true, binding.copy(enabled = false), study, null))
        assertEquals(NfcDecision.Ignore, NfcToggle.decide(false, binding, study, null))
        assertEquals(NfcDecision.Ignore, NfcToggle.decide(true, binding, null, null))
        assertEquals(NfcDecision.Ignore, NfcToggle.decide(true, binding, work.copy(archived = true), null))
    }

    @Test
    fun normalizeUidKeepsFullHexUppercase() {
        assertEquals("04A1B2C3D4E5F6", NfcToggle.normalizeUid(" 04:a1:b2:c3:d4:e5:f6 "))
        assertEquals("A1B2C3D4", NfcToggle.normalizeUid("a1b2c3d4"))
        assertEquals("", NfcToggle.normalizeUid("zzz"))
    }

    @Test
    fun debouncerIgnoresSameUidWithin5s() {
        var now = 1_000_000L
        val debouncer = NfcDebouncer(windowMs = 5_000L) { now }
        assertTrue(debouncer.accept("A1B2"))
        now += 1_000L
        assertFalse(debouncer.accept("A1B2"))
        now += 4_000L
        assertTrue(debouncer.accept("A1B2"))
    }

    @Test
    fun debouncerIsPerUid() {
        var now = 0L
        val debouncer = NfcDebouncer(5_000L) { now }
        assertTrue(debouncer.accept("AAAA"))
        assertTrue(debouncer.accept("BBBB"))
        now += 100L
        assertFalse(debouncer.accept("AAAA"))
        assertFalse(debouncer.accept("BBBB"))
    }
}
