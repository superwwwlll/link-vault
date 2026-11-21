package cn.linkvault

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读字体是 OpenSI Serif 的拉丁字形加 Noto Serif SC 的汉字，不是 Claude 官方字体。 */
class OpenSiFontTest {
    @Test fun readingFontKeepsOpenSiLatinAndNotoHan() {
        val file = listOf(
            File("src/main/res/font/reading_serif.ttf"),
            File("app/src/main/res/font/reading_serif.ttf")
        ).first { it.isFile }
        val bytes = file.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("504496e58074e379735661a0bacfa2f06224ab885d7482acc3d375474cf5ea0b", sha)
        assertTrue(bytes.containsUtf16("OpenSI Serif"))
        assertTrue(bytes.containsUtf16("Noto Serif SC"))
        assertTrue(bytes.containsUtf16("Not Claude"))
        val chars = cmap(bytes)
        listOf('A', 'g', '2', '藏', '，', '。').forEach { ch ->
            assertTrue("$ch U+${ch.code.toString(16)}", ch.code in chars)
        }
        val licenses = listOf(
            File("src/main/assets/fonts"),
            File("app/src/main/assets/fonts")
        ).first { it.isDirectory }
        assertTrue(File(licenses, "OFL-OpenSI-Serif.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(licenses, "OFL-NotoSerifSC.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(licenses, "OpenSI-NOTICE.txt").readText().contains("OpenSI Mono is not bundled"))
    }

    private fun ByteArray.containsUtf16(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_16BE)
        return indexOfSlice(needle) >= 0
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (i in 0..size - needle.size) {
            var ok = true
            for (j in needle.indices) if (this[i + j] != needle[j]) { ok = false; break }
            if (ok) return i
        }
        return -1
    }

    private fun cmap(data: ByteArray): Set<Int> {
        val tables = mutableMapOf<String, Int>()
        val numTables = u16(data, 4)
        var p = 12
        repeat(numTables) {
            val tag = data.copyOfRange(p, p + 4).toString(Charsets.US_ASCII)
            tables[tag] = u32(data, p + 8)
            p += 16
        }
        val start = tables["cmap"] ?: error("no cmap")
        val num = u16(data, start + 2)
        val chars = mutableSetOf<Int>()
        for (i in 0 until num) {
            val rec = start + 4 + i * 8
            val offset = start + u32(data, rec + 4)
            when (u16(data, offset)) {
                4 -> chars += format4(data, offset)
                12 -> chars += format12(data, offset)
            }
        }
        return chars
    }

    private fun format4(data: ByteArray, pos: Int): Set<Int> {
        val seg = u16(data, pos + 6) / 2
        val end = pos + 14
        val ends = IntArray(seg) { u16(data, end + it * 2) }
        val startsAt = end + seg * 2 + 2
        val starts = IntArray(seg) { u16(data, startsAt + it * 2) }
        val deltas = IntArray(seg) { i16(data, startsAt + seg * 2 + it * 2) }
        val rangeAt = startsAt + seg * 4
        val ranges = IntArray(seg) { u16(data, rangeAt + it * 2) }
        val chars = mutableSetOf<Int>()
        for (k in 0 until seg) {
            var cp = starts[k]
            while (cp <= ends[k] && cp != 0xFFFF) {
                val gid = if (ranges[k] == 0) (cp + deltas[k]) and 0xFFFF else {
                    val roff = rangeAt + k * 2 + ranges[k] + (cp - starts[k]) * 2
                    val raw = u16(data, roff)
                    if (raw == 0) 0 else (raw + deltas[k]) and 0xFFFF
                }
                if (gid != 0) chars += cp
                cp++
            }
        }
        return chars
    }

    private fun format12(data: ByteArray, pos: Int): Set<Int> {
        val groups = u32(data, pos + 12)
        val chars = mutableSetOf<Int>()
        var p = pos + 16
        repeat(groups) {
            val start = u32(data, p)
            val end = u32(data, p + 4)
            p += 12
            for (cp in start..end) chars += cp
        }
        return chars
    }

    private fun u16(data: ByteArray, at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
    private fun i16(data: ByteArray, at: Int) = u16(data, at).let { if (it >= 0x8000) it - 0x10000 else it }
    private fun u32(data: ByteArray, at: Int) =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
}
