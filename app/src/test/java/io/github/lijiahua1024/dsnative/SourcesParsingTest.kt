package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.ui.session.ToolStatus
import io.github.lijiahua1024.dsnative.ui.session.ToolStep
import io.github.lijiahua1024.dsnative.ui.session.searchSources
import io.github.lijiahua1024.dsnative.ui.session.toolByIdMap
import io.github.lijiahua1024.dsnative.ui.session.toolStep
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesParsingTest {

    @Test
    fun supportsDeepSearchAndImageSearchAndSiteIcon() {
        val blocks = listOf(
            ReplyBlock.Tool(buildJsonObject {
                put("type", "server_tool_use")
                put("id", "tu_img")
                put("kind", "image_search")
                put("name", "deepseek_web_search")
                putJsonObject("input") {
                    put("action", "image_search")
                    putJsonArray("queries") { add("cat photo") }
                }
            }),
            ReplyBlock.Tool(buildJsonObject {
                put("type", "web_search_tool_result")
                put("tool_use_id", "tu_img")
                put("kind", "image_search")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "web_search_result")
                        put("url", "https://www.example.com/a")
                        put("title", "Cat A")
                        put("site_icon", "https://www.example.com/favicon.ico")
                    })
                }
            }),
            ReplyBlock.Tool(buildJsonObject {
                put("type", "server_tool_use")
                put("id", "tu_deep")
                put("kind", "deep_image_search")
                put("name", "deepseek_web_search")
                putJsonObject("input") {
                    put("action", "search")
                    putJsonArray("queries") { add("q1"); add("q2") }
                }
            }),
            ReplyBlock.Tool(buildJsonObject {
                put("type", "web_search_tool_result")
                put("tool_use_id", "tu_deep")
                put("kind", "deep_image_search")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "web_search_result")
                        put("url", "https://news.org/b")
                        put("title", "Cat B")
                    })
                }
            }),
        )

        val sources = searchSources(blocks)
        assertEquals(2, sources.size)
        assertEquals("https://www.example.com/favicon.ico", sources[0].siteIcon)

        val step1 = toolStep(blocks[0] as ReplyBlock.Tool, toolByIdMap(blocks), sources, streaming = false) as ToolStep.Search
        assertEquals(ToolStatus.DONE, step1.status)
        assertEquals("image_search", step1.kind)
        assertEquals(1, step1.sources.size)

        val step2 = toolStep(blocks[2] as ReplyBlock.Tool, toolByIdMap(blocks), sources, streaming = false) as ToolStep.Search
        assertEquals(ToolStatus.DONE, step2.status)
        assertEquals("deep_image_search", step2.kind)
        assertEquals(listOf("q1", "q2"), step2.queries)
    }

    @Test
    fun detectsWebSearchToolResultErrorAsFailed() {
        val use = ReplyBlock.Tool(buildJsonObject {
            put("type", "server_tool_use")
            put("id", "tu_err")
            put("kind", "search")
            putJsonObject("input") {
                put("action", "search")
                putJsonArray("queries") { add("fail") }
            }
        })
        val res = ReplyBlock.Tool(buildJsonObject {
            put("type", "web_search_tool_result")
            put("tool_use_id", "tu_err")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "web_search_tool_result_error")
                    put("error_code", "unavailable")
                })
            }
        })
        val blocks = listOf(use, res)
        val step = toolStep(use, toolByIdMap(blocks), searchSources(blocks), streaming = false) as ToolStep.Search
        assertEquals(ToolStatus.FAILED, step.status)
        assertTrue(step.sources.isEmpty())
    }

    @Test
    fun parsesReadLinkWipAndDoneWithoutPollutingSearchSources() {
        val use = ReplyBlock.Tool(buildJsonObject {
            put("type", "server_tool_use")
            put("id", "tu_rl")
            put("kind", "read_link")
            putJsonObject("input") {
                put("action", "read_link")
                put("url", "https://example.org/doc")
            }
        })
        val wipStep = toolStep(use, toolByIdMap(listOf(use)), emptyList(), streaming = true) as ToolStep.ReadLink
        assertEquals(ToolStatus.WIP, wipStep.status)
        assertEquals("https://example.org/doc", wipStep.url)

        val res = ReplyBlock.Tool(buildJsonObject {
            put("type", "web_fetch_tool_result")
            put("tool_use_id", "tu_rl")
            put("kind", "read_link")
            putJsonObject("content") {
                put("type", "web_fetch_result")
                put("url", "https://example.org/doc")
                putJsonObject("content") { put("title", "Doc Title") }
            }
        })
        val doneBlocks = listOf(use, res)
        assertTrue(searchSources(doneBlocks).isEmpty())
        val doneStep = toolStep(use, toolByIdMap(doneBlocks), emptyList(), streaming = false) as ToolStep.ReadLink
        assertEquals(ToolStatus.DONE, doneStep.status)
        assertEquals("Doc Title", doneStep.title)
    }
}
