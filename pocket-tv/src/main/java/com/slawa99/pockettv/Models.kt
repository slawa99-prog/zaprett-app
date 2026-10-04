package com.slawa99.pockettv

data class ModuleInfo(
    val version: String = "", val service: String = "unknown", val selected: String = "",
    val strategies: List<String> = emptyList(), val compatible: Boolean = false,
    val error: String = "", val bootEnabled: Boolean = false
)

data class ProbeResult(
    val name: String, val status: String, val httpOk: Int, val httpTotal: Int,
    val tls12Ok: Int, val tls12Total: Int, val tls13Ok: Int, val tls13Total: Int
) {
    val ok get() = httpOk + tls12Ok + tls13Ok
    val total get() = httpTotal + tls12Total + tls13Total
    val fraction get() = if (status == "OK" && total > 0) ok.toDouble() / total else -1.0
    val percent get() = if (fraction >= 0) (fraction * 100).toInt() else 0
    fun description(): String = if (status == "SKIP") "Пропущена: движок или конфигурация недоступны"
        else if (total == 0) "Нет поддерживаемых проверок — результат неизвестен"
        else "Сетевые проверки: $ok из $total ($percent%)  ·  HTTP $httpOk/$httpTotal  ·  TLS 1.2 $tls12Ok/$tls12Total  ·  TLS 1.3 $tls13Ok/$tls13Total"
}

data class TestSnapshot(
    val id: String = "", val status: String = "idle", val total: Int = 0,
    val current: String = "", val started: Long = 0, val profile: String = "youtube",
    val results: List<ProbeResult> = emptyList(), val tail: String = "", val exitCode: String = "",
    val restore: String = ""
) {
    val active get() = status in setOf("starting", "running", "restoring", "cancelling")
    val completed get() = results.size
    val ranked get() = results.sortedWith(compareByDescending<ProbeResult> { it.fraction }
        .thenByDescending { it.ok }.thenBy { it.name.lowercase() })
    fun headline(): String = when (status) {
        "starting" -> "Подготовка: запуск тестера Pocket"
        "running" -> "Проверяется: ${current.ifEmpty { "подготовка сети" }}"
        "restoring" -> "Проверки закончены. Восстановление сервиса…"
        "cancelling" -> "Остановка и восстановление сервиса…"
        "completed" -> "Подбор завершён. Выберите стратегию из результатов"
        "cancelled" -> "Подбор остановлен. Готовые результаты сохранены"
        "interrupted" -> "Подбор прерван перезагрузкой или остановкой процесса"
        "failed" -> "Подбор завершился с ошибкой. Откройте журнал"
        else -> "Подбор ещё не запускался"
    }
}

object PocketProtocol {
    private val resultLine = Regex("^@@RESULT (OK|SKIP) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (.+)$")
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    fun safeName(name: String): Boolean = name.isNotBlank() && name.length <= 240 &&
        name != "." && name != ".." && name.none { it == '/' || it.isISOControl() }
    fun module(output: String): ModuleInfo {
        val lines = output.lines()
        fun field(key: String) = lines.firstOrNull { it.startsWith("@@$key ") }?.substringAfter(' ').orEmpty()
        return ModuleInfo(field("VERSION"), field("SERVICE"), field("SELECTED"),
            lines.filter { it.startsWith("@@STRATEGY ") }.map { it.substringAfter(' ') }.filter(::safeName).distinct().sorted(),
            field("COMPATIBLE") == "1", field("ERROR"), field("BOOT") == "1")
    }
    fun test(output: String): TestSnapshot {
        val main = output.substringBefore("@@LOGTAIL\n").replace(ansi, "")
        val lines = main.lines()
        fun field(key: String) = lines.lastOrNull { it.startsWith("@@$key ") }?.substringAfter(' ').orEmpty()
        val results = linkedMapOf<String, ProbeResult>()
        for (line in lines) {
            val match = resultLine.matchEntire(line.trimEnd('\r')) ?: continue
            val g = match.groupValues
            val n = (2..7).map { g[it].toIntOrNull() ?: -1 }
            if (!safeName(g[8]) || n.any { it !in 0..100000 } || n[0] > n[1] || n[2] > n[3] || n[4] > n[5]) continue
            results[g[8]] = ProbeResult(g[8], g[1], n[0], n[1], n[2], n[3], n[4], n[5])
        }
        val total = maxOf(field("TOTAL").toIntOrNull() ?: 0, results.size)
        return TestSnapshot(field("ID"), field("STATE").ifEmpty { "idle" }, total,
            field("TESTING"), field("STARTED").toLongOrNull() ?: 0, field("PROFILE").ifEmpty { "youtube" },
            results.values.toList(), output.substringAfter("@@LOGTAIL\n", "").replace(ansi, "").takeLast(16000),
            field("EXIT"), field("RESTORE"))
    }
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
