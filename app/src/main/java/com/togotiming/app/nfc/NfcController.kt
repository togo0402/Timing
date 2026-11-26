package com.togotiming.app.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.togotiming.app.data.SOURCE_NFC
import com.togotiming.app.data.SettingsStore
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.data.TimingRepository
import com.togotiming.app.domain.NfcDebouncer
import com.togotiming.app.domain.NfcDecision
import com.togotiming.app.domain.NfcToggle
import com.togotiming.app.timer.TimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NfcEvent {
    data class AskSwitch(val activeRecord: TimeRecordEntity, val targetTypeId: Long) : NfcEvent
}

/**
 * NFC 读取：
 * - 前台：enableReaderMode + 5s 同 UID 防抖；
 * - 后台：系统通过 NDEF URI + AAR 拉起 App，Intent 里取 Tag UID；
 * - 绑定页：只上报 UID，不触发 toggle。
 */
class NfcController(
    private val context: Context,
    private val repository: TimingRepository,
    private val settings: SettingsStore,
    private val timerController: TimerController,
    private val scope: CoroutineScope,
) {
    private val debouncer = NfcDebouncer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activity: Activity? = null
    private var readerEnabled = false
    private var bindingSink: ((String) -> Unit)? = null

    private val _events = MutableSharedFlow<NfcEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NfcEvent> = _events.asSharedFlow()

    private val _pendingSwitch = MutableStateFlow<NfcEvent.AskSwitch?>(null)
    val pendingSwitch: StateFlow<NfcEvent.AskSwitch?> = _pendingSwitch.asStateFlow()

    private val _lastUid = MutableStateFlow<String?>(null)
    val lastUid: StateFlow<String?> = _lastUid.asStateFlow()

    /** 绑定页显示：标签是否可写、能否后台拉起 */
    private val _lastTagInfo = MutableStateFlow<String?>(null)
    val lastTagInfo: StateFlow<String?> = _lastTagInfo.asStateFlow()

    fun clearTagInfo() {
        _lastTagInfo.value = null
    }

    fun attach(activity: Activity) {
        this.activity = activity
    }

    fun adapter(): NfcAdapter? = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        ?: NfcAdapter.getDefaultAdapter(context)

    fun isSupported(): Boolean = adapter() != null

    fun isEnabled(): Boolean = adapter()?.isEnabled == true

    fun enableReaderMode() {
        val act = activity ?: return
        val nfc = NfcAdapter.getDefaultAdapter(act) ?: return
        if (!nfc.isEnabled) return
        if (readerEnabled) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        try {
            nfc.enableReaderMode(act, { tag -> onTagDiscovered(tag) }, flags, null)
            readerEnabled = true
        } catch (_: Exception) {
        }
    }

    fun disableReaderMode() {
        val act = activity ?: return
        if (!readerEnabled) return
        try {
            NfcAdapter.getDefaultAdapter(act)?.disableReaderMode(act)
        } catch (_: Exception) {
        }
        readerEnabled = false
    }

    /** 绑定页进入时设置，离开时置 null */
    fun setBindingSink(sink: ((String) -> Unit)?) {
        bindingSink = sink
    }

    private fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        val writing = bindingSink != null
        scope.launch {
            if (writing) {
                val result = writeNdef(tag, uid)
                _lastTagInfo.value = when (result) {
                    NdefWriteResult.OK -> "可写标签：已写入 NDEF + AAR，后台可拉起"
                    NdefWriteResult.READ_ONLY -> "只读标签：只能前台使用，后台无法拉起（需换可写 NTAG）"
                    NdefWriteResult.NO_NDEF -> "无 NDEF 的卡（身份证/门禁卡等）：只能前台使用"
                    NdefWriteResult.FAILED -> "写入失败：请重试或换一张标签"
                }
                when (result) {
                    NdefWriteResult.OK -> toast("已写入标签，后台碰触可拉起 App")
                    NdefWriteResult.READ_ONLY ->
                        toast("标签只读（可能被其他 App 锁定），后台只会打开原应用；请换可写标签")
                    NdefWriteResult.NO_NDEF -> toast("该卡不支持 NDEF（身份证/门禁卡），只能前台使用")
                    NdefWriteResult.FAILED -> toast("写入失败，请重试或换一张标签")
                }
            }
            handleUid(uid)
        }
    }

    private enum class NdefWriteResult { OK, READ_ONLY, NO_NDEF, FAILED }

    /** 把 timing://nfc/toggle?uid=... + AAR 写入标签，让后台/冷启动也能被系统拉起 */
    private fun writeNdef(tag: Tag, uid: String): NdefWriteResult {
        val message = NdefMessage(
            NdefRecord.createUri("timing://nfc/toggle?uid=$uid"),
            NdefRecord.createApplicationRecord(context.packageName),
        )
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            if (!ndef.isWritable) return NdefWriteResult.READ_ONLY
            return try {
                ndef.connect()
                ndef.writeNdefMessage(message)
                NdefWriteResult.OK
            } catch (_: Exception) {
                NdefWriteResult.FAILED
            } finally {
                runCatching { ndef.close() }
            }
        }
        val formatable = NdefFormatable.get(tag) ?: return NdefWriteResult.NO_NDEF
        return try {
            formatable.connect()
            formatable.format(message)
            NdefWriteResult.OK
        } catch (_: Exception) {
            NdefWriteResult.FAILED
        } finally {
            runCatching { formatable.close() }
        }
    }

    /** NDEF / TECH / TAG 分发：AAR 或 TECH_DISCOVERED 拉起 App 后处理。返回是否消费 */
    fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) {
            return false
        }
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val uidFromTag = tag?.id?.joinToString("") { "%02X".format(it) }
        val uidFromUri = try {
            intent.data?.getQueryParameter("uid")
        } catch (_: Exception) {
            null
        }
        val uid = uidFromTag ?: uidFromUri
        if (uid.isNullOrBlank()) {
            toast("无法读取标签 UID，请重试")
            return true
        }
        scope.launch { handleUid(uid) }
        return true
    }

    suspend fun handleUid(rawUid: String) {
        val uid = NfcToggle.normalizeUid(rawUid)
        if (uid.isEmpty()) return
        val sink = bindingSink
        if (sink != null) {
            _lastUid.value = uid
            mainHandler.post { sink(uid) }
            return
        }
        if (!debouncer.accept(uid)) return
        if (!settings.nfcEnabled.value) {
            toast("NFC 总开关已关闭")
            return
        }
        val binding = repository.bindingByUid(uid)
        if (binding == null) {
            toast("该标签还没有绑定活动")
            return
        }
        if (!binding.enabled) {
            toast("该标签的绑定已停用")
            return
        }
        val type = repository.typeById(binding.typeId)
        val active = repository.activeNow()
        when (val decision = NfcToggle.decide(true, binding, type, active)) {
            NfcDecision.Ignore -> Unit
            NfcDecision.StopCurrent -> {
                timerController.stop()
                toast("${type?.name ?: "活动"} 结束")
            }
            is NfcDecision.Start -> {
                val name = repository.typeById(decision.typeId)?.name ?: "活动"
                timerController.start(decision.typeId, SOURCE_NFC)
                toast("$name 开始")
            }
            is NfcDecision.AskSwitch -> {
                _pendingSwitch.value = NfcEvent.AskSwitch(decision.activeRecord, decision.targetTypeId)
            }
        }
    }

    fun confirmSwitch(typeId: Long) {
        _pendingSwitch.value = null
        scope.launch {
            try {
                val name = repository.typeById(typeId)?.name ?: "活动"
                timerController.start(typeId, SOURCE_NFC)
                toast("已切换到 $name")
            } catch (e: Exception) {
                toast(e.message ?: "切换失败")
            }
        }
    }

    fun cancelSwitch() {
        _pendingSwitch.value = null
        toast("保持当前计时")
    }

    private fun toast(text: String) {
        mainHandler.post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
