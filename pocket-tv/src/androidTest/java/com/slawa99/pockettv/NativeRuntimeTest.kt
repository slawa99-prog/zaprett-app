package com.slawa99.pockettv

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NativeRuntimeTest {
    @Test fun installedApkContainsRunnableCurlAndAndroidCertificates() {
        val inputs = NativeRuntime.prepare(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(inputs.certificates.readText().contains("-----BEGIN CERTIFICATE-----"))
        val process = ProcessBuilder(inputs.curl.absolutePath, "--version").redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.contains("curl 8.22.0"))
        assertTrue(output, output.contains("https"))
    }
}
