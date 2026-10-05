package com.slawa99.pockettv

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

interface PocketBackend {
    fun inspect(): ModuleInfo
    fun poll(): TestSnapshot
    fun command(command: String, argument: String = ""): String
    fun launch(profile: String): String
    fun log(): String
}

class RootBackend(private val context: Context) : PocketBackend {
    private val bridge by lazy {
        File(context.filesDir, "pocket_bridge.sh").also { target ->
            context.assets.open("pocket_bridge.sh").use { source ->
                target.outputStream().use { source.copyTo(it) }
            }
        }
    }
    override fun inspect(): ModuleInfo = PocketProtocol.module(run("inspect", timeout = 65))
    override fun poll(): TestSnapshot = PocketProtocol.test(run("poll", timeout = 25))
    override fun command(command: String, argument: String): String {
        require(command in setOf("start", "stop", "restart", "apply", "cancel-test"))
        if (command == "apply") require(PocketProtocol.safeName(argument))
        return run(command, *if (argument.isEmpty()) emptyArray() else arrayOf(argument), timeout = 300)
    }
    override fun launch(profile: String): String {
        require(profile in setOf("youtube", "all"))
        return run("launch-test", profile, UUID.randomUUID().toString(), timeout = 45, native = NativeRuntime.prepare(context))
    }
    override fun log(): String = run("log", timeout = 30)

    private fun run(vararg args: String, timeout: Long, native: NativeInputs? = null): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Root operation on UI thread" }
        val environment = native?.let {
            "POCKET_TV_CURL_SOURCE=${PocketProtocol.shellQuote(it.curl.absolutePath)} " +
                "POCKET_TV_CA_SOURCE=${PocketProtocol.shellQuote(it.certificates.absolutePath)} "
        }.orEmpty()
        val cmd = environment + "sh ${PocketProtocol.shellQuote(bridge.absolutePath)} " + args.joinToString(" ") { PocketProtocol.shellQuote(it) }
        val process = try { ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start() }
        catch (e: Exception) { throw IllegalStateException("Не удалось вызвать su. Нужен Magisk и разрешение root для Pocket TV. ${e.message}", e) }
        val output = ByteArrayOutputStream()
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            if (output.size() < 2_000_000) output.write(buffer, 0, minOf(count, 2_000_000 - output.size()))
                        }
                    }
                }
            } catch (_: Exception) { /* Process termination closes the stream. */ }
        }, "pocket-root-output").apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException("Команда ${args.firstOrNull()} не ответила за $timeout с. Проверьте запрос Magisk и журнал. Обновите состояние перед повтором.")
            }
            reader.join(1500)
            val text = synchronized(output) { output.toString("UTF-8") }.trim()
            if (process.exitValue() != 0) throw IllegalStateException(text.ifEmpty {
                "Нет root-доступа или команда Pocket завершилась с кодом ${process.exitValue()}. Разрешите Pocket TV суперпользователя в Magisk."
            })
            return text
        } finally { process.outputStream.close() }
    }
}
