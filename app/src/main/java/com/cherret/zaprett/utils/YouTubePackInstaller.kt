package com.cherret.zaprett.utils

import android.content.SharedPreferences
import com.cherret.zaprett.data.RepoManifest
import com.cherret.zaprett.data.StorageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Installs a small, curated selection from the same repository used by the manual download UI. */
object YouTubePackInstaller {
    private const val base = "https://raw.githubusercontent.com/CherretGit/zaprett-repo/refs/heads/main"
    private val strategies = listOf(
        "strategy-youtubefix-alt", "strategy-alt", "strategy-alt2", "strategy-alt3",
        "strategy-alt4", "strategy-general", "strategy-simple-fake",
        "strategy-general-simple-fake", "strategy-ultimatefix-alt", "strategy-fake-tls-auto-alt"
    )
    private val bins = listOf("quic_initial_www_google_com", "tls_clienthello_www_google_com")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    const val preferenceKey = "youtube_pack_installed_v1"

    suspend fun install(prefs: SharedPreferences, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val total = bins.size + strategies.size + 1
        var done = 0
        for (id in bins) {
            installItem("bin", id, getAllBin())
            onProgress(++done, total)
        }
        for (id in strategies) {
            installItem("strategies/nfqws", id, getAllNfqwsStrategies())
            onProgress(++done, total)
        }
        val youtubeList = installItem("lists/include", "list-youtube", getAllLists())
        onProgress(++done, total)
        if (getHostListMode(prefs) == com.cherret.zaprett.data.ListType.whitelist &&
            getActiveLists(prefs).none { it.id == youtubeList.id }
        ) {
            enableList(youtubeList.manifestPath, prefs)
            check(getActiveLists(prefs).any { it.id == youtubeList.id }) { "Не удалось включить список YouTube" }
        }
        prefs.edit().putBoolean(preferenceKey, true).apply()
    }

    private fun installItem(folder: String, id: String, installed: Array<StorageData>): StorageData {
        installed.firstOrNull { it.id == id }?.let { return it }
        val manifest = json.decodeFromString<RepoManifest>(
            read("$base/manifests/$folder/$id.json").decodeToString()
        )
        require(manifest.id == id && manifest.artifact.url.startsWith("$base/files/$folder/")) {
            "Некорректный манифест $id"
        }
        val dependencies = manifest.dependencies.map { url ->
            val dependencyId = url.substringAfterLast('/').removeSuffix(".json")
            require(url == "$base/manifests/bin/$dependencyId.json" && dependencyId in bins) {
                "Неизвестная зависимость стратегии $id"
            }
            getAllBin().first { it.id == dependencyId }.manifestPath
        }
        val bytes = read(manifest.artifact.url)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(hash.equals(manifest.artifact.sha256, ignoreCase = true)) { "Контрольная сумма $id не совпала" }

        val name = manifest.artifact.url.substringAfterLast('/')
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && !name.startsWith('.')) {
            "Некорректное имя файла $id"
        }
        val directory = getZaprettPath().resolve("files/$folder").apply { mkdirs() }
        val target = availableFile(directory, name)
        val manifestDirectory = getManifestsPath().resolve(folder).apply { mkdirs() }
        val manifestTarget = availableFile(manifestDirectory, "${target.nameWithoutExtension}.json")
        val data = StorageData(
            manifest.schema, manifest.id, manifest.name, manifest.version, manifest.author,
            manifest.description, dependencies, target.path
        )
        writeNewFile(target, bytes)
        try {
            writeNewFile(manifestTarget, json.encodeToString(data).toByteArray())
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        data.manifestPath = manifestTarget.path
        return data
    }

    private fun availableFile(folder: File, name: String): File {
        val target = folder.resolve(name)
        if (!target.exists()) return target
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        return generateSequence(1) { it + 1 }
            .map { folder.resolve("${stem}-auto$it${if (extension.isEmpty()) "" else ".$extension"}") }
            .first { !it.exists() }
    }

    private fun writeNewFile(target: File, bytes: ByteArray) {
        val temporary = File.createTempFile(".youtube-pack-", ".tmp", target.parentFile)
        try {
            temporary.writeBytes(bytes)
            check(temporary.renameTo(target)) { "Не удалось сохранить ${target.name}" }
        } finally {
            temporary.delete()
        }
    }

    private fun read(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Не удалось загрузить набор YouTube: HTTP ${response.code}" }
            response.body.bytes()
        }
    }
}
