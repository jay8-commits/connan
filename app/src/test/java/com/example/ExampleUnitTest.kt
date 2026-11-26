package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun stringsXmlHasAppName() {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        )
        val stringsFile = candidates.firstOrNull { it.exists() }
        requireNotNull(stringsFile) { "strings.xml not found" }
        val xml = stringsFile.readText()
        assertTrue(xml.contains("<string name=\"app_name\">ToonForge Tagalog</string>"))
    }
}
