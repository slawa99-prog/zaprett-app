package com.cherret.zaprett.utils

import com.cherret.zaprett.data.StorageData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Generated strategies live outside the main catalog, but use the same manifest format. */
object PersonalStrategyFiles {
    private val json = Json { encodeDefaults = true }
    private val manifestDir get() = getManifestsPath().resolve("strategies/nfqws/personal")
    private val strategyDir get() = getZaprettPath().resolve("files/strategies/nfqws/personal")

    fun installed(): Array<StorageData> = getValidManifests(manifestDir)

    fun create(seed: StorageData, variant: PersonalStrategyMutator.Variant): StorageData {
        require(File(seed.file).isFile) { "Selected strategy is unavailable" }
        manifestDir.mkdirs()
        strategyDir.mkdirs()
        val id = "personal-${UUID.randomUUID().toString().take(8)}"
        val file = strategyDir.resolve("$id.txt")
        val manifest = manifestDir.resolve("$id.json")
        val data = StorageData(
            schema = 1,
            id = id,
            name = "Личная: ${variant.label}",
            version = "1.0.0",
            author = "Zaprett Auto",
            description = "Вариант на основе ${seed.name}",
            dependencies = seed.dependencies,
            file = file.absolutePath
        )
        writeAtomically(file, variant.content.toByteArray(Charsets.UTF_8))
        try {
            writeAtomically(manifest, json.encodeToString(data).toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        data.manifestPath = manifest.absolutePath
        return data
    }

    /** Keep the current run and an active older strategy so service restarts still work. */
    fun removeOldExcept(keepPaths: Set<String>) {
        val safeDirectory = strategyDir.canonicalFile
        manifestDir.listFiles()?.filter {
            it.name.startsWith("personal-") && it.extension == "json" && it.absolutePath !in keepPaths
        }?.forEach { manifest ->
            val artifact = parseManifestFromFile(manifest).getOrNull()?.file?.let(::File)
            if (artifact?.canonicalFile?.parentFile == safeDirectory) artifact.delete()
            manifest.delete()
        }
    }

    private fun writeAtomically(target: File, content: ByteArray) {
        val temp = File.createTempFile(".personal-", ".tmp", target.parentFile)
        try {
            temp.writeBytes(content)
            check(temp.renameTo(target)) { "Could not save ${target.name}" }
        } finally {
            temp.delete()
        }
    }
}
