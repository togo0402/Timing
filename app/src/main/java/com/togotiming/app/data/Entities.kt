package com.togotiming.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val SOURCE_MANUAL = "manual"
const val SOURCE_NFC = "nfc"
const val SOURCE_WIDGET = "widget"

/**
 * 活动类型（分类），最多 3 层。叶子节点才允许计时 / 绑定 NFC。
 * path 冗余存储、全库唯一，用于导入匹配，例如 "学习/英语/翻译"。
 */
@Entity(
    tableName = "activity_types",
    indices = [Index(value = ["path"], unique = true), Index("parentId")]
)
data class ActivityTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val parentId: Long? = null,
    val name: String,
    val iconRes: String? = null,
    val colorArgb: Int,
    /** 若颜色由父色派生，记录当时的父色，便于父色级联更新；手改过则为 null */
    val colorSeed: Int? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    /** 0..2 */
    val depth: Int = 0,
    val path: String,
    /** 默认每日目标（分钟），开始计时时可覆盖 */
    val targetMinutes: Int = 60,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 时间块。endAt == null 表示进行中；全库同一时刻最多一条进行中记录。
 */
@Entity(
    tableName = "time_records",
    indices = [Index("typeId"), Index("startAt"), Index("endAt")]
)
data class TimeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val typeId: Long,
    val startAt: Long,
    val endAt: Long? = null,
    val comment: String? = null,
    val source: String = SOURCE_MANUAL,
    /** 本次计时的目标（分钟），null = 使用分类默认目标 */
    val targetMinutes: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * NFC 标签绑定。tagUid 为完整大写十六进制、无分隔符。
 */
@Entity(
    tableName = "nfc_bindings",
    indices = [Index(value = ["tagUid"], unique = true)]
)
data class NfcBindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tagUid: String,
    val typeId: Long,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
