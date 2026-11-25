package com.shieldstream.tv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PinyinEngineTest {

    private fun loadSampleDictionary() {
        val sample = """
            S	gu	谷,古,故
            S	yu	雨,鱼,语
            S	xia	下,夏
            S	yuan	元,远
            W	gu	谷
            W	guyu	谷雨,古玉
            W	guyuan	古元,故园
            W	guyuanshu	古猿属
            W	xiayu	夏雨
        """.trimIndent()
        PinyinEngine.loadFromStream(ByteArrayInputStream(sample.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `single syllable returns characters before matching words`() {
        loadSampleDictionary()

        val result = PinyinEngine.candidatesFromLoaded("gu")

        assertEquals(listOf("谷", "古", "故", "谷雨", "古玉"), result)
    }

    @Test
    fun `multi syllable returns exact words then prefix matches then trailing syllable`() {
        loadSampleDictionary()

        val result = PinyinEngine.candidatesFromLoaded("guyu")

        assertTrue(result.indexOf("谷雨") < result.indexOf("古元"))
        assertTrue(result.indexOf("古元") < result.indexOf("雨"))
        assertTrue("雨" in result)
    }

    @Test
    fun `greedy segmentation composes phrase when exact word is absent`() {
        loadSampleDictionary()

        val result = PinyinEngine.candidatesFromLoaded("xiagu")

        assertTrue("下谷" in result)
    }

    @Test
    fun `input normalization ignores case and non letters and hits cache consistently`() {
        loadSampleDictionary()

        val first = PinyinEngine.candidatesFromLoaded("GuYu")
        val second = PinyinEngine.candidatesFromLoaded("gu-yu1")

        assertEquals(first, second)
    }

    @Test
    fun `hasChinese detects CJK characters`() {
        assertTrue(PinyinEngine.hasChinese("谷"))
        assertTrue(PinyinEngine.hasChinese("小巷人家2024"))
        assertFalse(PinyinEngine.hasChinese("guyu"))
        assertFalse(PinyinEngine.hasChinese(""))
    }
}
