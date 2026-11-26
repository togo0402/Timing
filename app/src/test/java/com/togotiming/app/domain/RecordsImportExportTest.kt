package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsImportExportTest {

    private val root = ActivityTypeEntity(
        id = 1,
        name = "学习",
        colorArgb = 0xFFE53935.toInt(),
        path = "学习",
        targetMinutes = 90,
    )

    private val child = ActivityTypeEntity(
        id = 2,
        parentId = 1,
        name = "英语",
        colorArgb = 0xFFEF5350.toInt(),
        depth = 1,
        path = "学习/英语",
        targetMinutes = 30,
    )

    private fun records(): List<TimeRecordEntity> = listOf(
        // 跨区间起点：裁剪为 [1000, 1500)
        TimeRecordEntity(id = 10, typeId = 1, startAt = 500, endAt = 1500, comment = "早"),
        // 跨区间终点：裁剪为 [1800, 2000)
        TimeRecordEntity(id = 11, typeId = 2, startAt = 1800, endAt = 2500, targetMinutes = 45),
        // 完全在区间之后：裁剪后为 0，跳过
        TimeRecordEntity(id = 12, typeId = 1, startAt = 2000, endAt = 3000),
        // 完全在区间之前：裁剪后为 0，跳过
        TimeRecordEntity(id = 13, typeId = 1, startAt = 0, endAt = 900),
        // 进行中：只导出已结束记录，跳过
        TimeRecordEntity(id = 14, typeId = 1, startAt = 1200, endAt = null),
    )

    @Test
    fun exportClipsAndSkipsInvalidRecords() {
        val doc = RecordsImportExport.buildExportDoc(
            from = 1000,
            to = 2000,
            typeIds = null,
            allTypes = listOf(root, child),
            records = records(),
        )
        assertEquals(1000L, doc.from)
        assertEquals(2000L, doc.to)
        assertEquals(listOf(1000L, 1800L), doc.records.map { it.startAt })
        assertEquals(listOf(1500L, 2000L), doc.records.map { it.endAt })
        assertEquals(listOf("学习", "学习/英语"), doc.types.map { it.path })
        assertEquals(90, doc.types.first().targetMinutes)
        assertEquals(45, doc.records[1].targetMinutes)
        assertEquals("早", doc.records[0].comment)
    }

    @Test
    fun exportFiltersByTypeIds() {
        val doc = RecordsImportExport.buildExportDoc(
            from = 1000,
            to = 2000,
            typeIds = setOf(child.id),
            allTypes = listOf(root, child),
            records = records(),
        )
        assertEquals(1, doc.records.size)
        assertEquals("学习/英语", doc.records.single().typePath)
        assertEquals(1, doc.types.size)
    }

    @Test
    fun emptyTypeIdsMeansAll() {
        val doc = RecordsImportExport.buildExportDoc(
            from = 1000,
            to = 2000,
            typeIds = emptySet(),
            allTypes = listOf(root, child),
            records = records(),
        )
        assertEquals(2, doc.records.size)
    }

    @Test
    fun serializeThenParseRoundTrips() {
        val doc = RecordsImportExport.buildExportDoc(
            from = 1000,
            to = 2000,
            typeIds = null,
            allTypes = listOf(root, child),
            records = records(),
        )
        val text = RecordsImportExport.serialize(doc)
        assertTrue(text.contains("\"kind\": \"timing_records\""))
        val parsed = RecordsImportExport.parse(text)
        assertTrue(parsed is RecordParseResult.Ok)
        val back = (parsed as RecordParseResult.Ok).doc
        assertEquals(doc.records, back.records)
        assertEquals(doc.types, back.types)
        val summary = RecordsImportExport.summaryOf(back)
        assertEquals(2, summary.recordCount)
        assertEquals(2, summary.typeCount)
    }

    @Test
    fun rejectsWrongKindOrVersionOrRange() {
        val wrongKind = """
            {"version":1,"kind":"activities","from":1000,"to":2000,
             "types":[],"records":[]}
        """.trimIndent()
        val kindResult = RecordsImportExport.parse(wrongKind)
        assertTrue(kindResult is RecordParseResult.Error)
        assertTrue((kindResult as RecordParseResult.Error).message.contains("kind"))

        val wrongVersion = """
            {"version":2,"kind":"timing_records","from":1000,"to":2000,
             "types":[],"records":[]}
        """.trimIndent()
        assertTrue(RecordsImportExport.parse(wrongVersion) is RecordParseResult.Error)

        val missingKind = """
            {"version":1,"from":1000,"to":2000,"types":[],"records":[]}
        """.trimIndent()
        val missingKindResult = RecordsImportExport.parse(missingKind)
        assertTrue(missingKindResult is RecordParseResult.Error)
        assertTrue((missingKindResult as RecordParseResult.Error).message.contains("kind"))

        // 分类导入导出文件（activities 树）应给出「缺少 kind」而不是笼统解析失败
        val categoryFile = """
            {"version":1,"activities":[{"key":"a","name":"A"}]}
        """.trimIndent()
        val categoryResult = RecordsImportExport.parse(categoryFile)
        assertTrue(categoryResult is RecordParseResult.Error)
        assertTrue((categoryResult as RecordParseResult.Error).message.contains("kind"))

        val badRange = """
            {"version":1,"kind":"timing_records","from":2000,"to":1000,
             "types":[],"records":[]}
        """.trimIndent()
        assertTrue(RecordsImportExport.parse(badRange) is RecordParseResult.Error)
    }

    @Test
    fun rejectsBadTypesButAllowsEmptyRecords() {
        val emptyRecords = """
            {"version":1,"kind":"timing_records","from":1000,"to":2000,
             "types":[],"records":[]}
        """.trimIndent()
        assertTrue(RecordsImportExport.parse(emptyRecords) is RecordParseResult.Ok)

        val duplicateType = """
            {"version":1,"kind":"timing_records","from":1000,"to":2000,
             "types":[{"path":"学习","name":"学习"},{"path":"学习","name":"重复"}],
             "records":[]}
        """.trimIndent()
        val duplicateResult = RecordsImportExport.parse(duplicateType)
        assertTrue(duplicateResult is RecordParseResult.Error)
        assertTrue((duplicateResult as RecordParseResult.Error).message.contains("重复"))

        val tooDeep = """
            {"version":1,"kind":"timing_records","from":1000,"to":2000,
             "types":[{"path":"a/b/c/d","name":"d"}],"records":[]}
        """.trimIndent()
        assertTrue(RecordsImportExport.parse(tooDeep) is RecordParseResult.Error)
    }

    @Test
    fun malformedJsonIsRejected() {
        assertTrue(RecordsImportExport.parse("{not json") is RecordParseResult.Error)
    }
}
