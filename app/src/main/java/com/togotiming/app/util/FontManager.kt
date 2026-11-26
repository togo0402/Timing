package com.togotiming.app.util

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** 字体导入：SAF 选择 .ttf/.otf，≤5MB，存 filesDir/fonts/<sha1>.<ext> */
object FontManager {

    private const val MAX_SIZE = 5L * 1024 * 1024
    private val allowedExt = setOf("ttf", "otf")

    fun fontsDir(context: Context): File = File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    fun import(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val name = queryDisplayName(context, uri) ?: "font.ttf"
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in allowedExt) { "只支持 .ttf / .otf 字体文件" }

        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取字体文件")
        require(bytes.size <= MAX_SIZE) { "字体文件超过 5MB 限制" }
        require(bytes.size > 100) { "字体文件为空或损坏" }

        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val target = File(fontsDir(context), "$sha1.$ext")
        target.writeBytes(bytes)

        // 校验字体能否加载，损坏则删除并报错
        try {
            Typeface.createFromFile(target)
        } catch (e: Exception) {
            target.delete()
            throw IllegalArgumentException("字体文件损坏，无法加载")
        }
        return target.absolutePath
    }

    fun load(path: String?): Typeface? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        return try {
            Typeface.createFromFile(f)
        } catch (e: Exception) {
            null
        }
    }

    fun list(context: Context): List<File> =
        fontsDir(context).listFiles()?.filter { it.extension.lowercase() in allowedExt }?.sortedBy { it.name }
            ?: emptyList()

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
