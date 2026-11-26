package com.togotiming.app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 颜色体系（语义锁死，不得自由发挥）：
 * - 一级 12 色调色板，按序循环；
 * - 子色按 HSL 递进公式从父色派生，新增子节点不改变已有子节点颜色；
 * - 允许颜色重复。
 */
object ColorPalette {

    val ROOT_COLORS = intArrayOf(
        0xFFE53935.toInt(), // 红
        0xFFFB8C00.toInt(), // 橙
        0xFFFDD835.toInt(), // 黄
        0xFF7CB342.toInt(), // 青柠
        0xFF43A047.toInt(), // 绿
        0xFF00ACC1.toInt(), // 青
        0xFF1E88E5.toInt(), // 蓝
        0xFF3949AB.toInt(), // 靛
        0xFF8E24AA.toInt(), // 紫
        0xFFD81B60.toInt(), // 粉
        0xFF6D4C41.toInt(), // 棕
        0xFF546E7A.toInt(), // 蓝灰
    )

    fun rootColor(index: Int): Int {
        val n = ROOT_COLORS.size
        return ROOT_COLORS[((index % n) + n) % n]
    }

    /** 父色 + 第 i 个子节点（i 从 0 开始）→ 子色 */
    fun childColor(parentArgb: Int, index: Int): Int {
        val hsl = rgbToHsl(parentArgb)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]
        val s2 = (s - 0.05f * index).coerceIn(0.55f, 1.00f)
        val l2 = (l + (index - 1) * 0.10f).coerceIn(0.30f, 0.75f)
        return hslToColor(h, s2, l2)
    }

    /** 返回 [h(0..360), s(0..1), l(0..1)] */
    fun rgbToHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxV = max(r, max(g, b))
        val minV = min(r, min(g, b))
        val l = (maxV + minV) / 2f
        var h = 0f
        var s = 0f
        if (abs(maxV - minV) > 1e-6f) {
            val d = maxV - minV
            s = if (l > 0.5f) d / (2f - maxV - minV) else d / (maxV + minV)
            h = when (maxV) {
                r -> ((g - b) / d + if (g < b) 6f else 0f)
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } * 60f
        }
        return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun hslToColor(h: Float, s: Float, l: Float, alpha: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f / 360f
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        val r: Float
        val g: Float
        val b: Float
        if (ss == 0f) {
            r = ll; g = ll; b = ll
        } else {
            val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
            val p = 2f * ll - q
            r = hue2rgb(p, q, hh + 1f / 3f)
            g = hue2rgb(p, q, hh)
            b = hue2rgb(p, q, hh - 1f / 3f)
        }
        return (alpha and 0xFF shl 24) or
            ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
            ((b * 255f).toInt().coerceIn(0, 255))
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** 接受 "#RRGGBB" / "RRGGBB"（大小写不限），非法返回 null */
    fun parseHex(input: String): Int? {
        val s = input.trim().removePrefix("#")
        if (s.length != 6) return null
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (0xFF000000.toInt()) or s.toLong(16).toInt()
    }

    /** "#RRGGBB" 大写 */
    fun toHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

    fun contrastText(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        return if (y > 160f) 0xFF1F2430.toInt() else 0xFFFFFFFF.toInt()
    }

    /** 用于浅色背景上的可读文字 */
    fun readableOn(argb: Int): Int = contrastText(argb)
}
