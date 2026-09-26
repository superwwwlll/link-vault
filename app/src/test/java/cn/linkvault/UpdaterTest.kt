package cn.linkvault

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自更新的关键契约是「下载下来的东西必须与清单对得上」。
 * 这里用本地桩服务把这条路径完整跑一遍，不依赖外网、也不会因为 GitHub 那边变动而悄悄失效。
 *
 * 桩服务用 ServerSocket 手写，不用 com.sun.net.httpserver —— 后者不在 Android 的类库里，
 * 单元测试虽然跑在 JVM 上，但编译期只有 android.jar。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdaterTest {

    private class Stub(body: ByteArray, val declaredLength: Long = body.size.toLong(), val status: Int = 200) {
        val payload: ByteArray = body
    }

    private inner class StubServer(routes: Map<String, Stub>) {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val base: String get() = "http://127.0.0.1:$port"

        private val worker = Thread {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@use
                        var line: String?
                        do { line = reader.readLine() } while (line != null && line.isNotEmpty())
                        val path = requestLine.split(' ').getOrNull(1) ?: "/"
                        val route = routes[path]
                        val output = client.getOutputStream()
                        if (route == null) {
                            output.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            output.write(
                                ("HTTP/1.0 ${route.status} OK\r\nContent-Type: application/octet-stream\r\n" +
                                    "Content-Length: ${route.declaredLength}\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            output.write(route.payload)
                        }
                        output.flush()
                    }
                }
            }
        }.apply { isDaemon = true }

        fun start() = apply { worker.start() }
        fun close() { runCatching { socket.close() } }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val goodSha = "cf6730d43123b9772acea9fc518d2b009fe725969e4f6daaeaf640e176337992"

    private fun manifest(
        pkg: String = "cn.linkvault",
        name: String = "1.2.2",
        code: String = "5",
        size: String = "9103513",
        digest: String = goodSha
    ) = """{"app":"链藏","package":"$pkg","versionName":"$name","versionCode":$code,""" +
        """"minSdk":26,"file":"lian-cang-debug.apk","size":$size,"sha256":"$digest",""" +
        """"signedWith":"5105679d9dab4656582dd7e6c3b85c2e40a2a1bd09146ac3474e052677ad3e7d",""" +
        """"releasedAt":"2026-09-26T04:26:03Z"}"""

    // ------------------------------------------------------------ 清单解析

    @Test fun parsesAWellFormedManifest() {
        val info = Updater.parseManifest(manifest())
        assertEquals("1.2.2", info.versionName)
        assertEquals(5L, info.versionCode)
        assertEquals(9103513L, info.size)
        assertEquals(goodSha, info.sha256)
        assertEquals("2026-09-26T04:26:03Z", info.releasedAt)
    }

    @Test fun rejectsAManifestForAnotherApplication() {
        // 别人的清单不能拿来当自己的更新源
        assertThrows(IllegalArgumentException::class.java) { Updater.parseManifest(manifest(pkg = "com.evil.app")) }
    }

    @Test fun rejectsMissingOrNonsensicalVersionCode() {
        listOf("0", "-1", "null").forEach { code ->
            assertThrows("versionCode=$code 应该被拒绝", IllegalArgumentException::class.java) {
                Updater.parseManifest(manifest(code = code))
            }
        }
    }

    @Test fun rejectsMissingVersionName() {
        assertThrows(IllegalArgumentException::class.java) { Updater.parseManifest(manifest(name = "  ")) }
    }

    @Test fun rejectsBadDigests() {
        listOf("", "abc", "z".repeat(64), goodSha.dropLast(1), goodSha + "0").forEach { digest ->
            assertThrows("sha256=$digest 应该被拒绝", IllegalArgumentException::class.java) {
                Updater.parseManifest(manifest(digest = digest))
            }
        }
        // 大写允许，会被归一成小写
        assertEquals(goodSha, Updater.parseManifest(manifest(digest = goodSha.uppercase())).sha256)
    }

    @Test fun rejectsAnAbsurdSize() {
        assertThrows(IllegalArgumentException::class.java) { Updater.parseManifest(manifest(size = "999999999999")) }
        assertThrows(IllegalArgumentException::class.java) { Updater.parseManifest(manifest(size = "-5")) }
    }

    @Test fun rejectsMalformedJson() {
        listOf("", "{", "[]", "null", "not json at all").forEach { text ->
            assertThrows(Exception::class.java) { Updater.parseManifest(text) }
        }
    }

    @Test fun onlyAHigherVersionCodeCountsAsAnUpdate() {
        val info = Updater.parseManifest(manifest(code = "5"))
        assertTrue(Updater.isNewer(info, 4L))
        assertFalse("同版本不算有更新", Updater.isNewer(info, 5L))
        assertFalse("降级不算有更新", Updater.isNewer(info, 6L))
    }

    // ------------------------------------------------------------ 下载与校验

    @Test fun downloadsOnlyWhenTheContentMatchesTheManifest() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val digest = sha256Of(payload)
        val manifestBytes = manifest(digest = digest).toByteArray()
        val server = StubServer(
            mapOf("/apk" to Stub(payload), "/latest.json" to Stub(manifestBytes))
        ).start()
        try {
            val target = File.createTempFile("lv-update", ".apk")
            var progress = 0L
            val result = Updater.download("${server.base}/apk", target, digest) { done, _ -> progress = done }
            assertEquals(payload.size.toLong(), result.length())
            assertEquals("进度回调应该走到最后", payload.size.toLong(), progress)
            assertArrayEquals(payload, result.readBytes())
            result.delete()

            // 校验和不符必须丢弃，且不能把文件留在磁盘上
            val bad = File.createTempFile("lv-update-bad", ".apk")
            assertThrows(IllegalArgumentException::class.java) {
                Updater.download("${server.base}/apk", bad, "0".repeat(64)) { _, _ -> }
            }
            assertFalse("校验失败的文件必须被删掉", bad.exists())

            // 清单走的是同一条网络路径
            assertEquals(5L, Updater.fetchManifest("${server.base}/latest.json").versionCode)
            assertThrows(IllegalArgumentException::class.java) { Updater.fetchManifest("${server.base}/nope") }
        } finally {
            server.close()
        }
    }

    @Test fun refusesSomethingThatClaimsToBeEnormous() {
        val payload = ByteArray(64)
        val server = StubServer(mapOf("/big" to Stub(payload, declaredLength = Updater.MAX_APK + 1))).start()
        try {
            val target = File.createTempFile("lv-update-big", ".apk")
            assertThrows(IllegalArgumentException::class.java) {
                Updater.download("${server.base}/big", target, sha256Of(payload)) { _, _ -> }
            }
            assertFalse(target.exists())
        } finally {
            server.close()
        }
    }

    @Test fun reportsHttpErrorsInsteadOfWritingGarbage() {
        val server = StubServer(emptyMap()).start()
        try {
            val target = File.createTempFile("lv-update-404", ".apk")
            assertThrows(IllegalArgumentException::class.java) {
                Updater.download("${server.base}/missing", target, goodSha) { _, _ -> }
            }
            assertFalse(target.exists())
        } finally {
            server.close()
        }
    }
}
