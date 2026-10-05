package com.slawa99.pockettv

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class PocketApplication : Application() {
    companion object {
        // Instrumentation injects an in-memory backend; no Intent or exported test entry point.
        internal var backendFactory: ((Application) -> PocketBackend)? = null
    }
    val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var history: HistoryStore
    private val backend by lazy { backendFactory?.invoke(this) ?: RootBackend(this) }
    var module = ModuleInfo(); private set
    var snapshot = TestSnapshot(); private set
    var busy = ""; private set
    var error = ""; private set
    var notice = ""; private set
    var operationLog = ""; private set
    var fullLog = ""; private set
    var connected = false; private set
    private var polling = false
    private val listeners = mutableSetOf<() -> Unit>()
    override fun onCreate() {
        super.onCreate()
        history = HistoryStore(this)
        snapshot = history.load()
    }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun appendLog(text: String) { operationLog = (operationLog + "\n" + text).takeLast(50000) }
    fun clearError() { error = ""; notifyChanged() }
    fun refresh() = operation("Проверка Pocket и root-доступа…") {
        val module = backend.inspect()
        val state = backend.poll()
        persist(state)
        main.post { this.module = module; connected = true; accept(state) }
    }
    private fun persist(s: TestSnapshot) { if (s.id.isNotEmpty()) history.save(s) }
    private fun accept(s: TestSnapshot) {
        if (s.id.isNotEmpty()) {
            snapshot = s
            if (!s.active) notice = s.headline()
        }
    }
    fun control(command: String, argument: String = "") = operation(when (command) {
        "apply" -> "Включение $argument и перезапуск сервиса…"
        "start" -> "Запуск сервиса Pocket…"
        "stop" -> "Остановка сервиса Pocket…"
        "cancel-test" -> "Запрос остановки подбора…"
        else -> "Перезапуск сервиса Pocket…"
    }) {
        val output = backend.command(command, argument)
        val module = backend.inspect()
        val state = backend.poll()
        persist(state)
        main.post {
            appendLog(output); this.module = module; accept(state); connected = true
            notice = if (command == "cancel-test") "Остановка запрошена. Дождитесь восстановления сервиса."
                else if (command == "apply") "Выбрана ${module.selected}. Состояние сервиса: ${serviceLabel(module.service)}"
                else "Команда выполнена. Состояние сервиса: ${serviceLabel(module.service)}"
        }
    }
    fun launch(profile: String) = operation("Запуск полного каталога Pocket…") {
        val output = backend.launch(profile)
        val state = backend.poll()
        persist(state)
        main.post {
            appendLog(output); accept(state)
            notice = if (state.active) "Подбор продолжится и при закрытии приложения." else state.headline()
        }
    }
    fun readLog() = operation("Чтение журнала…") {
        val text = backend.log()
        main.post { fullLog = text; notice = "Журнал обновлён" }
    }
    fun poll() {
        if (busy.isNotEmpty() || polling || !connected) return
        polling = true
        worker.execute {
            try {
                val state = backend.poll()
                persist(state)
                val finished = snapshot.active && !state.active
                val module = if (finished) backend.inspect() else null
                main.post {
                    accept(state)
                    if (module != null) this.module = module
                    polling = false; notifyChanged()
                }
            } catch (e: Exception) { main.post {
                polling = false
                error = "Не удалось обновить прогресс: ${e.message}. Сама проверка может продолжаться."
                notifyChanged()
            } }
        }
    }
    private fun operation(label: String, block: () -> Unit) {
        if (busy.isNotEmpty()) return
        busy = label; error = ""; notice = ""; notifyChanged()
        worker.execute {
            try { block() }
            catch (e: Exception) { main.post { error = e.message ?: e.javaClass.simpleName; appendLog("$label\n$error") } }
            finally { main.post { busy = ""; notifyChanged() } }
        }
    }
    fun diagnosticText(): String = "Pocket TV 1.0-test2\nPocket ${module.version}\nСервис: ${module.service}\nСтратегия: ${module.selected}\nСессия: ${snapshot.id}\n${snapshot.headline()}\n${snapshot.completed}/${snapshot.total}\n\n$error\n\n$operationLog\n\n${fullLog.ifEmpty { snapshot.tail }}"
    fun serviceLabel(status: String) = when (status) {
        "running" -> "работает"
        "stopped" -> "остановлен"
        "degraded" -> "запущен частично — нужен перезапуск"
        else -> "неизвестно"
    }
}
