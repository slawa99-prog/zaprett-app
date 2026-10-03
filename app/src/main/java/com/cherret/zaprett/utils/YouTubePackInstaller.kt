package com.cherret.zaprett.utils

import android.content.Context
import android.content.SharedPreferences
import com.cherret.zaprett.data.RepoManifest
import com.cherret.zaprett.data.StorageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A fixed YouTube catalog shipped with the APK; installing it never needs a network request. */
object YouTubePackInstaller {
    private const val base = "https://raw.githubusercontent.com/CherretGit/zaprett-repo/refs/heads/main"
    private val strategies = listOf(
        "strategy-youtubefix-alt", "strategy-alt", "strategy-alt2", "strategy-alt3",
        "strategy-alt4", "strategy-general", "strategy-simple-fake",
        "strategy-general-simple-fake", "strategy-ultimatefix-alt", "strategy-fake-tls-auto-alt",
        "strategy-alt5", "strategy-alt6", "strategy-alt7", "strategy-alt8",
        "strategy-alt10", "strategy-alt11", "strategy-fake-tls-auto-alt2",
        "strategy-fake-tls-auto-alt3", "strategy-general-fake-tls-auto",
        "strategy-general-fake-tls-auto-alt", "strategy-general-fake-tls-auto-alt-2",
        "strategy-general-fake-tls-auto-alt-3", "strategy-simple-fake-alt",
        "strategy-general-simple-fake-alt", "strategy-ultimatefix-alt-v2",
        "strategy-ultimatefix-alt-v3", "strategy-ultimatefix-alt-v4",
        "strategy-ultimatefix-universal", "strategy-ultimatefix-universal-v2",
        "strategy-ultimatefix-universal-v3"
    )
    private val bins = listOf(
        "quic_initial_www_google_com", "tls_clienthello_www_google_com",
        "tls_clienthello_4pda_to", "tls_clienthello_max_ru"
    )
    private val json = Json { ignoreUnknownKeys = true }

    const val preferenceKey = "youtube_pack_installed_v2"
    val totalItems: Int get() = bins.size + strategies.size + 1

    suspend fun install(context: Context, prefs: SharedPreferences, onProgress: (Int, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            var done = 0
            for (id in bins) {
                installItem(context, "bin", id, getAllBin())
                onProgress(++done, totalItems)
            }
            for (id in strategies) {
                installItem(context, "strategies/nfqws", id, getAllNfqwsStrategies())
                onProgress(++done, totalItems)
            }
            val youtubeList = installItem(context, "lists/include", "list-youtube", getAllLists())
            onProgress(++done, totalItems)
            if (getHostListMode(prefs) == com.cherret.zaprett.data.ListType.whitelist &&
                getActiveLists(prefs).none { it.id == youtubeList.id }
            ) {
                enableList(youtubeList.manifestPath, prefs)
                check(getActiveLists(prefs).any { it.id == youtubeList.id }) {
                    "Не удалось включить список YouTube"
                }
            }
            prefs.edit().putBoolean(preferenceKey, true).apply()
        }

    private fun installItem(context: Context, folder: String, id: String, installed: Array<StorageData>): StorageData {
        installed.firstOrNull { it.id == id }?.let { return it }
        val manifest = json.decodeFromString<RepoManifest>(
            readAsset(context, "manifests/$folder/$id.json").decodeToString()
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
        val name = manifest.artifact.url.substringAfterLast('/')
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && !name.startsWith('.')) {
            "Некорректное имя файла $id"
        }
        val bytes = readAsset(context, "files/$folder/$name")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(hash.equals(manifest.artifact.sha256, ignoreCase = true)) {
            "Контрольная сумма $id не совпала"
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

    private fun readAsset(context: Context, path: String): ByteArray =
        context.assets.open("youtube_pack/$path").use { it.readBytes() }
}
