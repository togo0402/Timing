package com.togotiming.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.togotiming.app.R
import com.togotiming.app.appContainer
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.domain.StatsAggregator
import com.togotiming.app.domain.TimeMath
import com.togotiming.app.timer.TimerActionReceiver
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

class ListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ListRemoteViewsFactory(applicationContext, intent)
}

/** 一块磁贴的数据：一级节点（根节点）的今日累计 */
private data class TileItem(
    val type: ActivityTypeEntity,
    val millis: Long,
    val percent: Float,
    val active: Boolean,
    val leaf: Boolean,
)

/** 磁贴尺寸（px）与当前尺寸下能放几行 */
private data class TileGrid(
    val columnWidthPx: Int,
    val tileHeightPx: Int,
    val rows: Int,
) {
    val maxItems: Int get() = rows * 2
}

/**
 * W3 磁贴 Factory：2 列 GridView，每块磁贴是一个 Canvas 渲染的 Bitmap。
 *
 * 点击语义：
 * - 一级节点本身是叶子（没有未归档子节点）→ fill-in EXTRA_TYPE_ID + 模板 ACTION_START_TYPE，
 *   由 TimerActionReceiver 负责「开始 / 再次点击停止」；
 * - 一级节点还有子节点 → 该块用独立的 PendingIntent 打开 App，避免对父节点开始计时（repository 会拒绝）。
 */
class ListRemoteViewsFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId: Int =
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
    private val scale = WidgetScale(context)
    private val openAppIntent: PendingIntent by lazy {
        WidgetIntents.openAppForTile(context, widgetId.coerceAtLeast(0))
    }

    private var tiles: List<TileItem> = emptyList()
    private var grid = TileGrid(160, 80, 2)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        grid = computeGrid()
        tiles = runCatching { loadTiles(grid.maxItems) }.getOrDefault(emptyList())
    }

    /** 按当前 widget 尺寸推算 2 列网格的列宽、行高与可放行数（与 widget_list.xml 的 padding 保持一致） */
    private fun computeGrid(): TileGrid {
        val density = context.resources.displayMetrics.density
        val fallbackWidthPx = (250f * density).toInt()
        val fallbackHeightPx = (140f * density).toInt()
        val manager = AppWidgetManager.getInstance(context)
        val (widgetWidthPx, widgetHeightPx) = if (manager != null && widgetId >= 0) {
            WidgetSizes.px(context, manager, widgetId, 250, 140)
        } else {
            fallbackWidthPx to fallbackHeightPx
        }

        // 对应 res/layout/widget_list.xml：左右 10dp、磁贴间距 8dp、标题 26dp、底部 10dp、顶部 2dp
        val horizontalPadding = (10f * density).toInt()
        val spacing = (8f * density).toInt()
        val headerHeight = (26f * density).toInt()
        val gridTop = (2f * density).toInt()
        val gridBottom = (10f * density).toInt()

        val columnWidth = ((widgetWidthPx - 2 * horizontalPadding - spacing) / 2).coerceAtLeast(96)
        val available = (widgetHeightPx - headerHeight - gridTop - gridBottom).coerceAtLeast(72)
        val idealTileHeight = (62f * density).toInt().coerceAtLeast(72)
        val rows = (available.toFloat() / idealTileHeight.toFloat()).roundToInt().coerceIn(1, 4)
        val tileHeight = ((available - spacing * (rows - 1)) / rows)
            .coerceAtLeast((44f * density).toInt())
            .coerceAtMost((160f * density).toInt())
        return TileGrid(columnWidth, tileHeight, rows)
    }

    private fun loadTiles(maxItems: Int): List<TileItem> = runBlocking {
        val repository = context.appContainer.repository
        val now = System.currentTimeMillis()
        val from = TimeMath.dayStart(now)
        val to = TimeMath.nextDayStart(now)
        val records = repository.recordsIn(from, to)
        val types = repository.allTypes(includeArchived = true)
        val map = types.associateBy { it.id }

        // 只展示一级节点（子节点时长累加到父节点）
        val totals = StatsAggregator.byRoot(records, types, from, to, now)
        if (totals.isEmpty()) return@runBlocking emptyList()

        val activeRecord = repository.activeNow()
        val activeRootId = activeRecord?.let { record ->
            map[record.typeId]?.let { StatsAggregator.rootOf(it, map).id }
        }
        val childCounts = HashMap<Long, Int>()
        types.forEach { type ->
            if (type.archived) return@forEach
            val parentId = type.parentId ?: return@forEach
            childCounts[parentId] = (childCounts[parentId] ?: 0) + 1
        }
        val grandTotal = totals.sumOf { it.millis }.coerceAtLeast(1L)
        totals.take(maxItems).map { total ->
            TileItem(
                type = total.type,
                millis = total.millis,
                percent = total.millis.toFloat() / grandTotal.toFloat(),
                active = total.type.id == activeRootId,
                leaf = (childCounts[total.type.id] ?: 0) == 0,
            )
        }
    }

    override fun getCount(): Int = tiles.size

    override fun getViewAt(position: Int): RemoteViews {
        val layout = RemoteViews(context.packageName, R.layout.widget_tile)
        val tile = tiles.getOrNull(position) ?: return layout
        layout.setImageViewBitmap(
            R.id.widget_tile_image,
            WidgetRenderer.renderTile(
                scale = scale,
                name = tile.type.name,
                millis = tile.millis,
                percent = tile.percent,
                eventColor = tile.type.colorArgb,
                active = tile.active,
                widthPx = grid.columnWidthPx,
                heightPx = grid.tileHeightPx,
            ),
        )
        layout.setContentDescription(
            R.id.widget_tile_image,
            context.getString(
                R.string.widget_tile_a11y,
                tile.type.name,
                TimeMath.formatDuration(tile.millis),
                (tile.percent * 100f).roundToInt().coerceIn(0, 100),
            ),
        )
        if (tile.leaf) {
            // 叶子：开始 / 切换（由 widget 的 PendingIntent 模板 ACTION_START_TYPE 处理）
            layout.setOnClickFillInIntent(
                R.id.widget_tile_root,
                Intent().putExtra(TimerActionReceiver.EXTRA_TYPE_ID, tile.type.id),
            )
        } else {
            // 非叶子：打开 App 选择子分类
            layout.setOnClickPendingIntent(R.id.widget_tile_image, openAppIntent)
        }
        return layout
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        tiles.getOrNull(position)?.type?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun onDestroy() {
        tiles = emptyList()
    }
}
