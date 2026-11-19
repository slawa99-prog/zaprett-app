package com.slawa99.pockettv

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateSecurityTest {
    @Test fun releaseSignatureRejectsChangedPayload() {
        val envelope = File("src/main/assets/module-update-stable.json").readText()
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(envelope)!!.groupValues[1]
        val payload = Base64.getDecoder().decode(field("payload"))
        val signature = Base64.getDecoder().decode(field("signature"))
        val key = Base64.getDecoder().decode(File("src/main/assets/update-public-key.txt").readText().trim())
        assertTrue(UpdateSecurity.verify(payload, signature, key))
        payload[0] = (payload[0].toInt() xor 1).toByte()
        assertFalse(UpdateSecurity.verify(payload, signature, key))
    }
    private fun fixture(extraPath: String? = null, id: String = "zapret"): Pair<File, ModuleRelease> {
        val f = File.createTempFile("pocket-update", ".zip")
        ZipOutputStream(f.outputStream()).use { z ->
            val entries = linkedMapOf("module.prop" to "id=$id\nversionCode=72\nauthor=sevcator\n", "customize.sh" to "# fixture", "system/bin/zapret" to "# fixture")
            if (extraPath != null) entries[extraPath] = "invalid"
            entries.forEach { (name, value) -> z.putNextEntry(ZipEntry(name)); z.write(value.toByteArray()); z.closeEntry() }
        }
        return f to ModuleRelease("v72", 72, "https://github.com/sevcator/zapret-pocket/releases/download/72/zapret-pocket.zip", UpdateSecurity.hash(f), f.length(), 5, "")
    }
    @Test fun zipMustMatchDigestAndModuleIdentity() {
        val (f, r) = fixture()
        try {
            UpdateSecurity.validateZip(f, r)
            assertThrows(IllegalStateException::class.java) { UpdateSecurity.validateZip(f, r.copy(sha256 = "0".repeat(64))) }
            assertThrows(IllegalStateException::class.java) { UpdateSecurity.validateZip(f, r.copy(versionCode = 73)) }
        } finally { f.delete() }
        val (wrong, release) = fixture(id = "another-module")
        try { assertThrows(IllegalStateException::class.java) { UpdateSecurity.validateZip(wrong, release) } } finally { wrong.delete() }
    }
    @Test fun traversalAndExternalDownloadAreRejected() {
        for (path in listOf("../escape", "/absolute", "dir/../../escape", "dir\\escape")) {
            val (f, r) = fixture(path)
            try { assertThrows(IllegalStateException::class.java) { UpdateSecurity.validateZip(f, r) } } finally { f.delete() }
        }
        assertFalse(UpdateSecurity.officialUrl("https://github.com/other/module/releases/download/72/zapret-pocket.zip", 72))
        assertFalse(UpdateSecurity.officialUrl("http://github.com/sevcator/zapret-pocket/releases/download/72/zapret-pocket.zip", 72))
    }
    @Test fun previousCatalogDoesNotValidateNewStrategies() {
        val old = TestSnapshot(catalogId = "old", moduleVersion = "v71")
        assertFalse(old.matches(ModuleInfo(catalogId = "new", version = "v72")))
        assertFalse(TestSnapshot().matches(ModuleInfo()))
        assertTrue(old.matches(ModuleInfo(catalogId = "old")))
    }
}
