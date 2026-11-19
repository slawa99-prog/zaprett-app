package com.slawa99.pockettv

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal class RootCommandFailure(message: String, val diagnostic: String) : IllegalStateException(message)

internal fun videoReply(action: String, target: String, output: String): String {
    val expected = when (action) {
        "try" -> setOf("ready", "no_service", "changed")
        "commit" -> setOf("committed", "changed")
        "restore-on", "restore-off" -> setOf("restored", "changed")
        else -> error("Неизвестная операция видеотеста.")
    }
    val record = output.lineSequence().filter { it.startsWith("@@VIDEO ") }.singleOrNull()
    return record?.removePrefix("@@VIDEO ")?.takeIf(expected::contains)
        ?: throw RootCommandFailure("Модуль не подтвердил переключение стратегии $target. Подробности в журнале.",
            "Видео: $action, $target; некорректный ответ Pocket:\n" + output.takeLast(1600))
}

/** Process I/O is separate from Android so EOF and timeout behaviour is tested. */
internal class RootCommand(private val start: (String) -> Process = {
    ProcessBuilder("su", "-c", it).redirectErrorStream(true).start()
}) {
    fun run(command: String, name: String, timeout: Long): String {
        val process = try { start(command) }
        catch (e: Exception) { throw IllegalStateException("Не удалось вызвать su. Проверьте Magisk и разрешение root для SV Pocket. ${e.message}", e) }
        // Every command is non-interactive. Leaving this pipe open lets a shell
        // or helper wait indefinitely for input that the app will never send.
        process.outputStream.close()
        val output = ByteArrayOutputStream()
        fun captured() = synchronized(output) { output.toString("UTF-8") }.trim()
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
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            val partial = captured()
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            throw RootCommandFailure(timeoutMessage(name, timeout, partial),
                "Root command: $name; timeout: $timeout s\n${partial.takeLast(16000)}")
        }
        reader.join(1500)
        val text = captured()
        if (process.exitValue() != 0) {
            val detail = text.lineSequence().filterNot { it.startsWith("@@") }.joinToString("\n").trim().takeLast(3000)
            throw RootCommandFailure(detail.ifEmpty {
                "Root-команда $name завершилась с кодом ${process.exitValue()}. Проверьте разрешение SV Pocket в Magisk."
            }, "Root command: $name; exit: ${process.exitValue()}\n${text.takeLast(16000)}")
        }
        return text
    }

    companion object {
        fun timeoutMessage(command: String, seconds: Long, partial: String): String {
            val phase = partial.lineSequence().lastOrNull { it.startsWith("@@PHASE ") }?.substringAfter(' ')
            val step = when (phase) {
                "root" -> "root-доступ получен"
                "update" -> "чтение состояния обновления"
                "module" -> "чтение данных модуля"
                "service" -> "чтение процессов модуля"
                "catalog" -> "чтение каталога стратегий"
                "results" -> "чтение сохранённых результатов"
                "complete" -> "ожидание завершения root-команды после получения данных"
                else -> "ожидание запуска root-команды; ответ от моста ещё не получен"
            }
            return "Проверка не завершилась за $seconds с. Этап: $step. " +
                if (phase == null) "Проверьте запрос разрешения SV Pocket в Magisk. Подробности: журнал ($command)."
                else "Подробности сохранены в журнале ($command)."
        }
    }
}
