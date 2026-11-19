package com.slawa99.pockettv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

data class ModuleRelease(val version: String, val versionCode: Int, val zipUrl: String,
    val sha256: String, val size: Long, val minAppVersion: Int, val notes: String)

data class ModuleUpdateState(val phase: String = "idle", val version: Int = 0, val detail: String = "", val staged: Boolean = false) {
    val installing get() = phase == "installing"
    val pending get() = phase == "pending_reboot"
    val blocksChanges get() = installing || pending || staged
    companion object {
        fun parse(text: String): ModuleUpdateState {
            fun field(key: String) = text.lineSequence().firstOrNull { it.startsWith("@@$key ") }?.substringAfter(' ').orEmpty()
            return ModuleUpdateState(field("UPDATE_STATE").ifEmpty { "idle" }, field("UPDATE_VERSION").toIntOrNull() ?: 0,
                text.substringAfter("@@UPDATE_LOG\n", "").takeLast(12000), field("UPDATE_STAGED") == "1")
        }
    }
}

/** Signature validation is independent of TLS and the hosting account. */
object UpdateSecurity {
    fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        Signature.getInstance("SHA256withRSA").run {
            initVerify(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey)))
            update(payload); verify(signature)
        }
    fun officialUrl(url: String, version: Int): Boolean =
        url == "https://github.com/sevcator/zapret-pocket/releases/download/$version/zapret-pocket.zip"
    fun hash(file: File) = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun validateZip(file: File, release: ModuleRelease) {
        check(file.length() == release.size && hash(file) == release.sha256) { "Файл обновления повреждён. Скачайте его заново." }
        ZipFile(file).use { zip ->
            val names = hashSetOf<String>()
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                check(names.add(e.name) && names.size <= 20000 && !e.name.startsWith('/') &&
                    '\\' !in e.name && e.name.split('/').none { it == ".." } && e.name.none { it.isISOControl() }) {
                    "Некорректная структура обновления. Установка отменена."
                }
                check(e.size >= 0) { "Не удалось проверить размер обновления." }
                total += e.size
                check(total <= 512L * 1024 * 1024) { "Обновление превышает допустимый размер." }
            }
            val prop = zip.getEntry("module.prop") ?: error("В архиве нет модуля Pocket.")
            check(prop.size in 1..16384) { "Некорректное описание модуля." }
            val p = Properties().apply { zip.getInputStream(prop).use { load(it) } }
            check(p.getProperty("id") == "zapret" && p.getProperty("versionCode")?.toIntOrNull() == release.versionCode &&
                p.getProperty("author", "").contains("sevcator", ignoreCase = true)) { "Архив не соответствует версии Pocket. Установка отменена." }
            check(zip.getEntry("customize.sh") != null && zip.getEntry("system/bin/zapret") != null) { "Архив Pocket неполный." }
        }
    }
}

class ModuleUpdates(private val context: Context) {
    companion object {
        const val CATALOG_URL = "https://raw.githubusercontent.com/slawa99-prog/zaprett-app/feature/pocket-tv/pocket-tv/updates/stable.json"
    }
    private val cache = File(context.filesDir, "module-update-catalog.json")
    fun parse(envelope: String): ModuleRelease {
        val json = JSONObject(envelope)
        val payload = Base64.getDecoder().decode(json.getString("payload"))
        val signature = Base64.getDecoder().decode(json.getString("signature"))
        val key = Base64.getDecoder().decode(context.assets.open("update-public-key.txt").bufferedReader().use { it.readText() }.trim())
        check(UpdateSecurity.verify(payload, signature, key)) { "Не удалось проверить подлинность обновления. Установка отменена." }
        val p = JSONObject(String(payload, Charsets.UTF_8))
        check(p.getInt("schema") == 1 && p.getString("moduleId") == "zapret") { "Обновите SV Pocket для поддержки этого формата обновлений." }
        val r = ModuleRelease(p.getString("version"), p.getInt("versionCode"), p.getString("zipUrl"),
            p.getString("sha256"), p.getLong("size"), p.getInt("minAppVersionCode"), p.optString("notes"))
        check(r.versionCode > 0 && r.version == "v${r.versionCode}" && r.minAppVersion > 0 &&
            r.size in 1..(256L * 1024 * 1024) && Regex("[a-f0-9]{64}").matches(r.sha256) &&
            UpdateSecurity.officialUrl(r.zipUrl, r.versionCode)) { "Некорректные данные обновления." }
        return r
    }
    fun cached(): ModuleRelease? = try { parse(cache.readText()) } catch (_: Exception) {
        try { parse(context.assets.open("module-update-stable.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { null }
    }
    fun check(): ModuleRelease {
        val c = connection(CATALOG_URL)
        val body = try {
            c.inputStream.use { input ->
                val data = input.readBytesLimited(65536)
                String(data, Charsets.UTF_8)
            }
        } finally { c.disconnect() }
        val release = parse(body)
        // A replayed old catalog must not replace a newer authenticated catalog.
        check(release.versionCode >= (cached()?.versionCode ?: 0)) { "Сервер вернул старый каталог. Сохранена предыдущая версия." }
        val atomic = android.util.AtomicFile(cache)
        val out = atomic.startWrite()
        try { out.write(body.toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        return release
    }
    fun download(release: ModuleRelease, progress: (Int) -> Unit): File {
        check(release.minAppVersion <= BuildConfig.VERSION_CODE) { "Сначала обновите приложение SV Pocket." }
        val directory = File(context.cacheDir, "module-updates").apply { mkdirs() }
        val file = File(directory, "pocket-${release.versionCode}.zip")
        if (file.isFile && file.length() == release.size && UpdateSecurity.hash(file) == release.sha256) {
            UpdateSecurity.validateZip(file, release); progress(100); return file
        }
        val part = File(directory, "download.part")
        val c = connection(release.zipUrl)
        try {
            c.inputStream.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var received = 0L
                var percent = -1
                val deadline = android.os.SystemClock.elapsedRealtime() + 15 * 60 * 1000
                while (true) {
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Скачивание заняло слишком много времени. Повторите позже." }
                    val n = input.read(buffer); if (n < 0) break
                    received += n
                    check(received <= release.size) { "Размер файла не совпадает с каталогом." }
                    output.write(buffer, 0, n)
                    val next = (received * 100 / release.size).toInt()
                    if (next != percent) { percent = next; progress(next) }
                }
                output.fd.sync()
            } }
            UpdateSecurity.validateZip(part, release)
            check(part.renameTo(file)) { "Не удалось сохранить обновление." }
            return file
        } finally { c.disconnect(); part.delete() }
    }
    private fun connection(initial: String): HttpsURLConnection {
        var url = URL(initial)
        repeat(6) {
            val host = url.host.lowercase()
            check(url.protocol == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
                (host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com"))) {
                "Сервер обновлений перенаправил на неизвестный адрес."
            }
            val c = url.openConnection() as HttpsURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "SV-Pocket/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = try { c.responseCode } catch (e: Exception) { c.disconnect(); throw e }
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location"); c.disconnect()
                check(!location.isNullOrBlank()) { "Неверный ответ сервера обновлений." }
                url = URL(url, location)
            } else {
                if (code != 200) { c.disconnect(); error("Сервер обновлений недоступен (HTTP $code). Повторите позже.") }
                return c
            }
        }
        error("Слишком много перенаправлений при скачивании.")
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) { val n = read(buf); if (n < 0) break; check(out.size() + n <= limit) { "Каталог обновлений слишком большой." }; out.write(buf, 0, n) }
        return out.toByteArray()
    }
}
