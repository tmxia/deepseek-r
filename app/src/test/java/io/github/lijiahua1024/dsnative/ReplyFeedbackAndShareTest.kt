package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.ui.session.canSubmitEditedMessage
import io.github.lijiahua1024.dsnative.ui.session.formatShareTranscript
import io.github.lijiahua1024.dsnative.ui.session.selectedShareGroupCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFeedbackAndShareTest {

    @Test
    fun shareTranscriptAndGroupCountFollowOfficialSelectionSemantics() {
        val u1 = Message.User(id = "u1", content = "Hello", createdAt = 1L)
        val a1 = Message.Assistant(
            id = "a1",
            createdAt = 2L,
            status = ReplyStatus.DONE,
            blocks = listOf(ReplyBlock.Thinking("hmm"), ReplyBlock.Text("Hi there!")),
        )
        val u2 = Message.User(id = "u2", content = "2+2?", createdAt = 3L)
        val a2 = Message.Assistant(
            id = "a2",
            createdAt = 4L,
            status = ReplyStatus.DONE,
            blocks = listOf(ReplyBlock.Text("4")),
        )
        val msgs = listOf(u1, a1, u2, a2)

        assertEquals(2, selectedShareGroupCount(msgs, setOf("u1", "a1", "u2", "a2")))
        assertEquals(1, selectedShareGroupCount(msgs, setOf("u1", "a1", "a2")))

        val formatted = formatShareTranscript(msgs, setOf("u1", "a1", "a2"))
        assertTrue(formatted.contains("User:\nHello"))
        assertTrue(formatted.contains("DeepSeek:\nHi there!"))
        assertTrue(formatted.contains("DeepSeek:\n4"))
        assertTrue(!formatted.contains("2+2?"))
        assertTrue(!formatted.contains("hmm"))
    }

    @Test
    fun editMessageSubmitRequiresNonBlankIdleDraft() {
        assertFalse(canSubmitEditedMessage("", emptyList<FileAttachment>(), isStreaming = false))
        assertFalse(canSubmitEditedMessage("   \n\t ", emptyList<FileAttachment>(), isStreaming = false))
        assertFalse(canSubmitEditedMessage("edited", emptyList<FileAttachment>(), isStreaming = true))
        assertTrue(canSubmitEditedMessage("edited question", emptyList<FileAttachment>(), isStreaming = false))
    }

    @Test
    fun editMessageAllowsSubmitWithAttachmentEvenWithoutText() {
        val att = FileAttachment(id = "f1", name = "a.jpg", size = 1L, isImage = true)
        assertTrue(canSubmitEditedMessage("", listOf(att), isStreaming = false))
        assertFalse(canSubmitEditedMessage("", listOf(att), isStreaming = true))
    }
}
