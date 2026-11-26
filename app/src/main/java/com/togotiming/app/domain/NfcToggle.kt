package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.NfcBindingEntity
import com.togotiming.app.data.TimeRecordEntity

/**
 * NFC toggle 决策（纯逻辑，便于单元测试）：
 * - 同一标签：绑定的叶子正在计时 → 结束；否则先结束当前计时（若有）再开始。
 * - 不同标签：当前有别的叶子在计时 → 询问是否切换。
 */
sealed interface NfcDecision {
    data object Ignore : NfcDecision
    data object StopCurrent : NfcDecision
    data class Start(val typeId: Long) : NfcDecision
    data class AskSwitch(val activeRecord: TimeRecordEntity, val targetTypeId: Long) : NfcDecision
}

object NfcToggle {

    fun decide(
        globalEnabled: Boolean,
        binding: NfcBindingEntity?,
        targetType: ActivityTypeEntity?,
        active: TimeRecordEntity?,
    ): NfcDecision {
        if (!globalEnabled) return NfcDecision.Ignore
        if (binding == null || !binding.enabled) return NfcDecision.Ignore
        if (targetType == null || targetType.archived) return NfcDecision.Ignore
        if (active == null) return NfcDecision.Start(binding.typeId)
        if (active.typeId == binding.typeId) return NfcDecision.StopCurrent
        return NfcDecision.AskSwitch(active, binding.typeId)
    }

    /** UID 规范化：完整十六进制、大写、无分隔符，不截断 */
    fun normalizeUid(raw: String): String =
        raw.trim().uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
}

/** 同一 UID 5 秒内重复上报忽略（ReaderMode 会连发多次 onTagDiscovered） */
class NfcDebouncer(
    private val windowMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastSeen = HashMap<String, Long>()

    fun accept(uid: String): Boolean {
        val key = NfcToggle.normalizeUid(uid)
        if (key.isEmpty()) return false
        val now = clock()
        val prev = lastSeen[key]
        if (prev != null && now - prev < windowMs) return false
        lastSeen[key] = now
        return true
    }

    fun reset() = lastSeen.clear()
}
