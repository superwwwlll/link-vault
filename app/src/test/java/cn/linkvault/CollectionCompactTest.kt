package cn.linkvault

import org.junit.Assert.*
import org.junit.Test

class CollectionCompactTest {
    @Test fun tagsReserveTheActualOverflowWidth() {
        assertEquals(2, collectionVisibleTagCount(listOf(48, 60, 80, 48), 180, 6) { 48 })
        assertEquals(4, collectionVisibleTagCount(listOf(48, 60, 80, 48), 254, 6) { 48 })
        assertEquals(0, collectionVisibleTagCount(listOf(200, 48), 120, 6) { 48 })
        // 大字号下 +N 本身也会变宽，不能只预留固定胶囊宽度。
        assertEquals(1, collectionVisibleTagCount(listOf(72, 72, 72), 220, 6) { 80 })
        assertEquals(0, collectionVisibleTagCount(emptyList(), 100, 6) { 48 })
    }

    @Test fun excerptSkipsMarkdownHeadingsAndPreservesTheConclusion() {
        assertEquals("真正的结论来自原文。", collectionAiExcerpt("# 快速阅读\n\n**一句话概括：**\n- **真正的结论**来自原文。\n\n后续内容"))
        assertEquals("参考原文", collectionAiExcerpt("[参考原文](https://example.com)"))
        assertEquals(160, collectionAiExcerpt("长".repeat(200)).length)
        assertEquals("", collectionAiExcerpt("# 标题\n```"))
    }

    @Test fun analysisStateHasPriorityAndNeverReplacesBookmarkText() {
        val item = Bookmark(url = "https://x.com/example/status/1", canonical = "https://x.com/example/status/1", title = "原始标题", summary = "网页摘要")
        val record = AnalysisRecord("hash", "system", "quick", 100, listOf(AnalysisMessage("assistant", "分析结论")),
            suggestedTitle = "建议标题")
        assertEquals("AI · 未分析", collectionAiStatus(item, null, null, null))
        assertEquals("AI · 正在分析…", collectionAiStatus(item, record, "正在分析…", "失败"))
        assertEquals("AI · 失败，打开详情重试", collectionAiStatus(item, record, null, "失败"))
        assertEquals("AI · 标题 / 标签建议待确认", collectionAiStatus(item, record, null, null))
        assertEquals("AI · 已总结", collectionAiStatus(item.copy(title = "建议标题"), record, null, null))
        assertEquals("原始标题", item.title)
        assertEquals("网页摘要", item.summary)
    }
}
