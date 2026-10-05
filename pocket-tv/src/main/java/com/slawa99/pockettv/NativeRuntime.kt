package com.slawa99.pockettv

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class NativeInputs(val curl: File, val certificates: File)

object NativeRuntime {
    fun prepare(context: Context): NativeInputs {
        // PackageManager selects the matching ABI and installs this executable read-only.
        val curl = File(context.applicationInfo.nativeLibraryDir, "libpocketcurl.so")
        check(curl.isFile) { "В APK отсутствует curl для этой приставки. Установите полную сборку Pocket TV." }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val issuers = factory.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }.distinct()
        check(issuers.isNotEmpty()) { "Android не предоставил доверенные сертификаты для HTTPS-проверок." }
        // Android certificate directory names use a different hash from OpenSSL.
        // Export trusted certificates as PEM; TLS verification stays enabled.
        val certificates = File(context.filesDir, "pocket-ca-bundle.pem")
        certificates.bufferedWriter().use { out ->
            issuers.forEach { certificate ->
                out.appendLine("-----BEGIN CERTIFICATE-----")
                Base64.encodeToString(certificate.encoded, Base64.NO_WRAP).chunked(64).forEach(out::appendLine)
                out.appendLine("-----END CERTIFICATE-----")
            }
        }
        return NativeInputs(curl, certificates)
    }
}
