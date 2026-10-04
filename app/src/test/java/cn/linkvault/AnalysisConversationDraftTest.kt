package cn.linkvault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisConversationDraftTest {
    private val summary = AnalysisMessage("assistant", "原总结")
    private val question = AnalysisMessage("user", "下一步？")
    private val answer = AnalysisMessage("assistant", "先核实证据。")

    @Test fun onlyNewCompletedPairClearsDraft() {
        assertTrue(analysisReplyWasSaved(listOf(summary, question, answer), 1, question.text))
        assertFalse(analysisReplyWasSaved(listOf(summary), 1, question.text))
        assertFalse(analysisReplyWasSaved(listOf(summary, question), 1, question.text))
        assertFalse(analysisReplyWasSaved(listOf(summary, question, answer.copy(text = "")), 1, question.text))
    }

    @Test fun repeatedQuestionInOldHistoryDoesNotClearNewDraft() {
        val old = listOf(summary, question, answer)
        assertFalse(analysisReplyWasSaved(old, old.size, question.text))
        assertTrue(analysisReplyWasSaved(old + question + answer, old.size, question.text))
    }

    @Test fun unrelatedRecordReplacementOrReplyDoesNotClearDraft() {
        assertFalse(analysisReplyWasSaved(listOf(summary, question.copy(text = "不同问题"), answer), 1, question.text))
        assertFalse(analysisReplyWasSaved(listOf(summary, question, question), 1, question.text))
        assertFalse(analysisReplyWasSaved(listOf(summary, question, answer), 0, question.text))
    }
}
