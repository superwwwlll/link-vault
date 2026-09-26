package cn.linkvault

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日期展示。
 *
 * 只在主线程（Compose 渲染与 ViewModel 回调）使用；复用格式化器，避免列表每次重组都新建对象。
 */
object Stamp {
    private val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private val monthDay = SimpleDateFormat("M月d日", Locale.CHINA)
    private val withYear = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
    private const val DAY = 86_400_000L

    /** 今天的收藏显示「今天」，今年内省略年份，跨年才补上——原来的实现会让去年的收藏看起来像刚存的。 */
    fun date(time: Long, now: Long = System.currentTimeMillis()): String {
        val key = dayKey.format(Date(time))
        if (key == dayKey.format(Date(now))) return "今天"
        if (key == dayKey.format(Date(now - DAY))) return "昨天"
        val year = withYear.format(Date(time)).take(4)
        return if (year == withYear.format(Date(now)).take(4)) monthDay.format(Date(time)) else withYear.format(Date(time))
    }

    /** 时间戳超过多少天算「备份过期」。 */
    const val STALE_DAYS = 30L

    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0L) return "从未备份"
        val days = ((now - time) / DAY).coerceAtLeast(0L)
        return when {
            days <= 0L -> "就在刚刚"
            days == 1L -> "昨天"
            days < 30L -> "$days 天前"
            days < 365L -> "${days / 30} 个月前"
            else -> "${days / 365} 年前"
        }
    }

    fun isStale(time: Long, now: Long = System.currentTimeMillis()): Boolean =
        time <= 0L || (now - time) / DAY >= STALE_DAYS

    /** 备份文件名用的时间戳。 */
    fun fileNameStamp(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(time))
}
