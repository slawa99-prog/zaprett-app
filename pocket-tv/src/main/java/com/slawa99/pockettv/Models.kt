package com.slawa99.pockettv

data class ModuleInfo(
    val version: String = "", val service: String = "unknown", val selected: String = "",
    val strategies: List<String> = emptyList(), val compatible: Boolean = false,
    val error: String = "", val bootEnabled: Boolean = false,
    val versionCode: Int = 0, val catalogId: String = ""
)

data class BackendState(val module: ModuleInfo?, val test: TestSnapshot, val update: ModuleUpdateState)

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
    val restore: String = "", val failure: String = "",
    val moduleVersion: String = "", val catalogId: String = "",
    val cancelRequested: Boolean = false, val restoreStep: String = ""
) {
    fun matches(module: ModuleInfo) = catalogId.isNotEmpty() && catalogId == module.catalogId
    val active get() = status in setOf("starting", "running", "restoring", "cancelling")
    val completed get() = results.size
    val ranked get() = results.sortedWith(compareByDescending<ProbeResult> { it.fraction }
        .thenByDescending { it.ok }.thenBy { it.name.lowercase() })
    fun headline(): String = if (active && cancelRequested && status != "restoring") "Останавливаем сетевые проверки…" else when (status) {
        "starting" -> "Подготовка: запуск тестера Pocket"
        "running" -> "Проверяется: ${current.ifEmpty { "подготовка сети" }}"
        "restoring" -> if (restoreStep == "start") "Проверки закончены. Включаем прежний обход…"
            else "Проверки закончены. Убираем временные настройки…"
        "cancelling" -> "Останавливаем сетевые проверки…"
        "completed" -> "Подбор завершён. Выберите стратегию из результатов"
        "cancelled" -> "Подбор остановлен. Готовые результаты сохранены"
        "interrupted" -> "Подбор прерван перезагрузкой или остановкой процесса"
        "failed" -> "Подбор остановлен: " + failure.ifEmpty {
            if (tail.contains("curl not found or not functional")) "Pocket не смог запустить curl. Обновите SV Pocket и повторите подбор"
            else "ошибка тестера Pocket. Откройте журнал"
        }
        else -> "Подбор ещё не запускался"
    }
}

object PocketProtocol {
    private val resultLine = Regex("^@@RESULT (OK|SKIP) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (.+)$")
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    fun safeName(name: String): Boolean = name.isNotBlank() && name.length <= 240 &&
        name != "." && name != ".." && name.none { it == '/' || it.isISOControl() }
    fun state(output: String): BackendState {
        val sections = Regex("^@@SECTION (UPDATE|MODULE|TEST|END)$", RegexOption.MULTILINE).findAll(output).toList()
        require(sections.map { it.groupValues[1] } == listOf("UPDATE", "MODULE", "TEST", "END")) {
            "Неполный ответ состояния Pocket. Обновите состояние."
        }
        fun section(i: Int) = output.substring(sections[i].range.last + 1, sections[i + 1].range.first).trim()
        val info = section(1)
        return BackendState(if (info.isEmpty()) null else module(info), test(section(2)), ModuleUpdateState.parse(section(0)))
    }
    fun module(output: String): ModuleInfo {
        val lines = output.lines()
        fun field(key: String) = lines.firstOrNull { it.startsWith("@@$key ") }?.substringAfter(' ').orEmpty()
        return ModuleInfo(field("VERSION"), field("SERVICE"), field("SELECTED"),
            lines.filter { it.startsWith("@@STRATEGY ") }.map { it.substringAfter(' ') }.filter(::safeName).distinct().sorted(),
            field("COMPATIBLE") == "1", field("ERROR"), field("BOOT") == "1",
            field("VERSION_CODE").toIntOrNull() ?: field("VERSION").removePrefix("v").toIntOrNull() ?: 0,
            field("CATALOG_ID"))
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
            field("EXIT"), field("RESTORE"), field("ERROR").take(220), field("MODULE_VERSION"), field("CATALOG_ID"),
            field("CANCEL_REQUESTED") == "1", field("RESTORE_STEP"))
    }
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
