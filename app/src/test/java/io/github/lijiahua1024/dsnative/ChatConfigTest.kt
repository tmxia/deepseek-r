package io.github.lijiahua1024.dsnative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConfigTest {

    @Test
    fun parseEndpointAcceptsHttpHttps() {
        assertEquals("https://api.deepseek.com/anthropic",
            parseChatEndpoint("https://api.deepseek.com/anthropic"))
        assertTrue(parseChatEndpoint("http://192.168.1.10:8080") != null)
    }

    @Test
    fun parseEndpointRejectsNonHttp() {
        assertNull(parseChatEndpoint("ftp://api.example.com"))
        assertNull(parseChatEndpoint("api.example.com"))
        assertNull(parseChatEndpoint(""))
    }

    @Test
    fun parseEndpointRejectsCredentialsAndFragment() {
        assertNull(parseChatEndpoint("https://user:pass@api.example.com"))
        assertNull(parseChatEndpoint("https://api.example.com#section"))
    }

    @Test
    fun chatBaseUrlStripsProtocolSuffix() {
        assertEquals("https://api.deepseek.com/anthropic",
            chatBaseUrl("https://api.deepseek.com/anthropic/messages", ChatApiFormat.MESSAGES))
        assertEquals("https://api.example.com",
            chatBaseUrl("https://api.example.com/chat/completions", ChatApiFormat.CHAT))
    }

    @Test
    fun resolveEmptyEndpointReturnsEmpty() {
        assertEquals("", resolveChatEndpoint("   ", ChatApiFormat.MESSAGES))
    }

    @Test
    fun resolveInvalidEndpointReturnsNull() {
        assertNull(resolveChatEndpoint("not-a-url", ChatApiFormat.MESSAGES))
    }

    @Test
    fun resolveMessagesAddsV1Prefix() {
        val resolved = resolveChatEndpoint("https://api.deepseek.com/anthropic", ChatApiFormat.MESSAGES)
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", resolved)
    }

    @Test
    fun resolveChatUsesPlainPath() {
        val resolved = resolveChatEndpoint("https://api.example.com", ChatApiFormat.CHAT)
        assertEquals("https://api.example.com/chat/completions", resolved)
    }

    @Test
    fun resolveReusesPreviousFullEndpointWhenBaseUnchanged() {
        val previous = ChatConfig(
            model = "",
            endpoint = "https://gw.example.com/anthropic/custom/v1/messages",
            format = ChatApiFormat.MESSAGES,
            apiKey = "k",
        )
        val resolved = resolveChatEndpoint("https://gw.example.com/anthropic/custom",
            ChatApiFormat.MESSAGES, previous)
        assertEquals(previous.endpoint, resolved)
    }

    @Test
    fun readChatConfigFallsBackOnCorrupt() {
        assertEquals(ChatConfig(), readChatConfig(null))
        assertEquals(ChatConfig(), readChatConfig(""))
        assertEquals(ChatConfig(), readChatConfig("{not json"))
    }

    @Test
    fun readChatConfigParsesStored() {
        val stored = """{"model":"deepseek-x","endpoint":"https://gw.example.com/v1/messages","format":"chat","apiKey":"secret"}"""
        val cfg = readChatConfig(stored)
        assertEquals("deepseek-x", cfg.model)
        assertEquals(ChatApiFormat.CHAT, cfg.format)
        assertEquals("secret", cfg.apiKey)
    }

    @Test
    fun nonDefaultDetection() {
        assertFalse(chatConfigNonDefault(ChatConfig()))
        assertTrue(chatConfigNonDefault(ChatConfig(model = "deepseek-x")))
        assertTrue(chatConfigNonDefault(ChatConfig(endpoint = "https://gw.example.com")))
        assertTrue(chatConfigNonDefault(ChatConfig(format = ChatApiFormat.RESPONSES)))
    }
}
