package cn.linkvault

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionTelegramTest {
    private val item = Bookmark(url = "https://example.com/article", canonical = "https://example.com/article",
        title = "原始标题", summary = "原始网页摘要", tags = "科学")

    @Test fun summaryUsesFirstAssistantAndLeavesOriginalContentUntouched() {
        val record = AnalysisRecord("hash", "system", "quick", 100, listOf(
            AnalysisMessage("user", "请分析"),
            AnalysisMessage("assistant", "# 总结\n**原文结论**"),
            AnalysisMessage("user", "追问"),
            AnalysisMessage("assistant", "追问答案不能作为摘要")
        ), suggestedTitle = "AI 建议标题")
        assertEquals("AI 摘要 · 原文结论", collectionSummary(item, record))
        assertEquals("原始标题", item.title)
        assertEquals("原始网页摘要", item.summary)
    }

    @Test fun absentOrEmptyAiSummaryFallsBackToOriginal() {
        assertEquals("原始网页摘要", collectionSummary(item, null))
        val record = AnalysisRecord("hash", "system", "quick", 100,
            listOf(AnalysisMessage("assistant", "# 标题"), AnalysisMessage("assistant", "追问答案")))
        assertEquals("原始网页摘要", collectionSummary(item, record))
    }

    @Test fun runningFailedAndPendingStatesRemainDistinct() {
        val record = AnalysisRecord("hash", "system", "quick", 100, emptyList(), suggestedTags = listOf("阅读"))
        assertEquals("AI · 正在分析…", collectionAiStatus(item, record, "正在分析…", "失败"))
        assertEquals("AI · 失败，打开详情重试", collectionAiStatus(item, record, null, "失败"))
        assertEquals("AI · 标题 / 标签建议待确认", collectionAiStatus(item, record, null, null))
    }

    @Test fun narrowTagRowAlwaysReservesAllTagsEntrance() {
        assertEquals(0, collectionVisibleTagCount(listOf(180, 120, 90), 96, 6) { 64 })
        assertEquals(1, collectionVisibleTagCount(listOf(80, 80, 80), 154, 6) { 64 })
    }
}
