package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportExportTest {

    private val validJson = """
        {
          "version": 1,
          "exportedAt": "2026-01-15T10:30:00+08:00",
          "activities": [
            {
              "key": "study", "name": "学习", "color": "#E53935",
              "children": [
                { "key": "english", "name": "英语", "color": "#EF5350",
                  "children": [
                    { "key": "translate", "name": "翻译", "color": "#E57373" }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesThreeLevels() {
        val result = ImportExport.parse(validJson)
        assertTrue(result is ParseResult.Ok)
        val nodes = (result as ParseResult.Ok).nodes
        assertEquals(3, nodes.size)
        assertEquals("study", nodes[0].path)
        assertEquals("study/english/translate", nodes[2].path)
        assertEquals(3, nodes[2].depth)
        assertEquals("study/english", nodes[2].parentPath)
        assertEquals(0xFFE53935.toInt(), nodes[0].colorArgb)
    }

    @Test
    fun rejectsFourLevels() {
        val json = """
            {"version":1,"activities":[
              {"key":"a","name":"A","children":[
                {"key":"b","name":"B","children":[
                  {"key":"c","name":"C","children":[
                    {"key":"d","name":"D"}
                  ]}
                ]}
              ]}
            ]}
        """.trimIndent()
        val result = ImportExport.parse(json)
        assertTrue(result is ParseResult.Error)
        assertTrue((result as ParseResult.Error).message.contains("3 层"))
    }

    @Test
    fun rejectsInvalidColor() {
        val json = """{"version":1,"activities":[{"key":"a","name":"A","color":"#GGGGGG"}]}"""
        val result = ImportExport.parse(json)
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun rejectsMissingName() {
        val json = """{"version":1,"activities":[{"key":"a"}]}"""
        val result = ImportExport.parse(json)
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun rejectsDuplicatePath() {
        val json = """
            {"version":1,"activities":[
              {"key":"a","name":"A"},
              {"key":"a","name":"A2"}
            ]}
        """.trimIndent()
        val result = ImportExport.parse(json)
        assertTrue(result is ParseResult.Error)
        assertTrue((result as ParseResult.Error).message.contains("重复"))
    }

    @Test
    fun rejectsUnknownVersion() {
        val result = ImportExport.parse("""{"version":2,"activities":[{"key":"a","name":"A"}]}""")
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun rejectsMalformedJson() {
        assertTrue(ImportExport.parse("{not json") is ParseResult.Error)
    }

    @Test
    fun exportThenParseRoundTripsPaths() {
        val types = listOf(
            ActivityTypeEntity(id = 1, name = "学习", colorArgb = 0xFFE53935.toInt(), path = "学习"),
            ActivityTypeEntity(
                id = 2, parentId = 1, name = "英语", colorArgb = 0xFFEF5350.toInt(),
                depth = 1, path = "学习/英语"
            ),
        )
        val doc = ImportExport.buildExportDoc(types)
        val text = ImportExport.serialize(doc)
        val parsed = ImportExport.parse(text)
        assertTrue(parsed is ParseResult.Ok)
        val nodes = (parsed as ParseResult.Ok).nodes
        assertEquals(listOf("学习", "学习/英语"), nodes.map { it.path })
    }
}
