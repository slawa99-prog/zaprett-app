package com.slawa99.pockettv

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

data class HelpReport(val title: String = "Поможем восстановить YouTube", val detail: String =
    "Проверим интернет и состояние обхода, затем предложим следующий шаг.", val ready: Boolean = false, val offline: Boolean = false)

class PocketApplication : Application() {
    companion object { internal var backendFactory: ((Application) -> PocketBackend)? = null }
    val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val updateChecker = Executors.newSingleThreadExecutor()
    private lateinit var history: HistoryStore
    private val prefs by lazy { getSharedPreferences("friendly", MODE_PRIVATE) }
    private val backend by lazy { backendFactory?.invoke(this) ?: RootBackend(this) }
    private val updater by lazy { ModuleUpdates(this) }
    var module = ModuleInfo(); private set
    var snapshot = TestSnapshot(); private set
    var busy = ""; private set
    var error = ""; private set
    var notice = ""; private set
    var operationLog = ""; private set
    var fullLog = ""; private set
    var connected = false; private set
    var help = HelpReport(); private set
    var helpAlternatives = false; private set
    var update = ModuleUpdateState(); private set
    var release: ModuleRelease? = null; private set
    var checkingUpdates = false; private set
    var updateMessage = "Каталог, включённый в эту версию приложения"; private set
    var updateProgress = -1; private set
    var updateRequested = false; private set
    private var requestedRelease: ModuleRelease? = null
    private var polling = false
    private val tried = linkedSetOf<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    val changesAllowed get() = busy.isEmpty() && !updateRequested && !update.blocksChanges && connected && !snapshot.active
    val awaitingVideo get() = prefs.getBoolean("awaiting_video", false) &&
        prefs.getString("video_strategy", "") == module.selected && prefs.getString("video_catalog", "") == module.catalogId
    var automaticUpdateCheck: Boolean
        get() = prefs.getBoolean("check_updates", true)
        set(value) { prefs.edit().putBoolean("check_updates", value).apply(); notifyChanged() }
    val confirmedName get() = prefs.getString("working_name", "").orEmpty()
    val confirmedAt get() = prefs.getLong("working_at", 0)
    val canRestore get() = confirmedName in module.strategies && module.catalogId.isNotEmpty() &&
        prefs.getString("working_catalog", "") == module.catalogId
    val selectedConfirmed get() = canRestore && confirmedName == module.selected
    val nextStrategy: String? get() {
        val ranked = if (snapshot.matches(module)) snapshot.ranked.filter { it.fraction > 0 }.map { it.name } else emptyList()
        return (ranked + module.strategies).distinct().firstOrNull { it != module.selected && it !in tried && it in module.strategies }
    }
    override fun onCreate() {
        super.onCreate(); history = HistoryStore(this); snapshot = history.load(); release = updater.cached()
    }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun appendLog(text: String) { operationLog = (operationLog + "\n" + text).takeLast(50000) }
    fun clearError() { error = ""; notifyChanged() }
    private fun persist(s: TestSnapshot) { if (s.id.isNotEmpty()) history.save(s) }
    private fun accept(s: TestSnapshot) { if (s.id.isNotEmpty()) snapshot = s }
    fun refresh() = operation("Проверяем состояние обхода…") {
        try {
            val u = backend.updateStatus()
            main.post { update = u }
            val m = if (!u.installing) backend.inspect() else null
            val s = backend.poll(); persist(s)
            main.post {
                update = u; if (m != null) { module = m; connected = true }; accept(s)
                if (u.phase == "completed") notice = "Обновление завершено. Каталог стратегий обновлён."
            }
        } catch (e: Exception) { main.post { connected = false }; throw e }
    }
    fun control(command: String, argument: String = "", helpAfter: Boolean = false) = operation(when (command) {
        "apply" -> "Включаем стратегию $argument…"
        "start" -> "Включаем обход…"
        "stop" -> "Выключаем обход…"
        "cancel-test" -> "Останавливаем подбор…"
        else -> "Перезапускаем обход…"
    }) {
        val output = backend.command(command, argument)
        val m = backend.inspect(); val s = backend.poll(); persist(s)
        main.post {
            appendLog(output); module = m; accept(s); connected = true
            notice = if (command == "cancel-test") "Дождитесь завершения остановки. Результаты сохранятся."
                else "Обход: ${serviceLabel(m.service)}. Стратегия: ${m.selected}"
            if (helpAfter) {
                helpAlternatives = false
                help = HelpReport("Теперь проверьте видео", "Откройте YouTube или SmartTube, включите ролик и вернитесь сюда. Сообщите, получилось ли посмотреть видео.", true)
            }
        }
    }
    fun launch(profile: String) = operation("Готовим подбор стратегий…") {
        val output = backend.launch(profile); val state = backend.poll(); persist(state)
        main.post {
            tried.clear(); appendLog(output); accept(state)
            notice = if (state.active) "Подбор продолжится и при закрытии приложения." else state.headline()
        }
    }
    fun videoOpened() { prefs.edit().putBoolean("awaiting_video", true).putString("video_strategy", module.selected)
        .putString("video_catalog", module.catalogId).apply(); notifyChanged() }
    fun videoWorks() {
        if (!changesAllowed || module.service != "running" || module.selected.isEmpty()) return
        prefs.edit().putString("working_name", module.selected).putString("working_catalog", module.catalogId)
            .putLong("working_at", System.currentTimeMillis()).putBoolean("awaiting_video", false).apply()
        tried.clear(); helpAlternatives = false; help = HelpReport("Рабочая стратегия сохранена", "${module.selected}\nВы подтвердили воспроизведение видео. При смене сети проверьте его ещё раз.", true)
        notice = "Сохранена рабочая стратегия: ${module.selected}"; notifyChanged()
    }
    fun videoFailed() {
        helpAlternatives = true
        if (selectedConfirmed) prefs.edit().remove("working_name").remove("working_catalog").apply()
        tried.add(module.selected); prefs.edit().putBoolean("awaiting_video", false).apply()
        help = HelpReport("Попробуем другую стратегию", "Выберите следующую стратегию или верните последнюю рабочую. Если подходящих результатов нет, запустите полный подбор.", true)
        notifyChanged()
    }
    fun diagnose() = operation("Проверяем интернет…") {
        val network = getSystemService(ConnectivityManager::class.java)
        val caps = network.getNetworkCapabilities(network.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            main.post { helpAlternatives = false; help = HelpReport("Нет подключения к интернету", "Подключите Wi-Fi или сетевой кабель, затем нажмите «Проверить ещё раз».", offline = true) }
            return@operation
        }
        val verified = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || probeInternet()
        main.post { busy = "Проверяем состояние обхода…"; notifyChanged() }
        try {
            val u = backend.updateStatus()
            if (u.blocksChanges) {
                main.post { update = u; help = HelpReport("Сначала завершите обновление", "Откройте «Обновление обхода». После установки потребуется перезагрузка.") }
                return@operation
            }
            val m = backend.inspect(); val s = backend.poll(); persist(s)
            main.post {
                update = u; module = m; connected = true; accept(s); helpAlternatives = false
                help = if (s.active) HelpReport("Сейчас идёт подбор", "Дождитесь его окончания или остановите подбор. Во время проверки видео может не работать.")
                    else HelpReport(if (m.service == "running") "Обход включён" else "Обход нужно включить",
                        (if (verified) "Подключение к интернету подтверждено.\n" else "Сеть подключена, но проверочные сайты не ответили. Проверьте, открываются ли другие сервисы.\n") +
                        (if (m.service == "running") "Попробуйте перезапустить обход, затем откройте видео." else "Нажмите «Включить обход», затем проверьте видео."), true)
            }
        } catch (e: Exception) {
            main.post { connected = false; help = HelpReport("Не удалось подключить обход", e.message ?: "Проверьте разрешение суперпользователя для SV Pocket."); appendLog(e.message.orEmpty()) }
        }
    }
    private fun probeInternet(): Boolean {
        for (url in listOf("https://connectivitycheck.gstatic.com/generate_204", "https://ya.ru/")) {
            var c: HttpsURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpsURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000; c.instanceFollowRedirects = false
                if (c.responseCode in 200..399) return true
            } catch (_: Exception) { } finally { c?.disconnect() }
        }
        return false
    }
    fun checkUpdates(automatic: Boolean = false) {
        if (checkingUpdates || updateRequested || (automatic && (!automaticUpdateCheck || backendFactory != null))) return
        val now = System.currentTimeMillis(); val previous = prefs.getLong("check_attempt", 0)
        if (automatic && now >= previous && now - previous < 24 * 60 * 60 * 1000) return
        prefs.edit().putLong("check_attempt", now).apply()
        checkingUpdates = true; updateMessage = "Проверяем обновления…"; notifyChanged()
        updateChecker.execute {
            try { val r = updater.check(); main.post { release = r; updateMessage = "Каталог обновлений проверен" } }
            catch (e: Exception) { main.post { updateMessage = "Не удалось проверить новые версии. ${e.message ?: "Проверьте интернет."}\nМожно повторить позже; доступен сохранённый каталог." } }
            finally { main.post { checkingUpdates = false; notifyChanged() } }
        }
    }
    fun startModuleUpdate(expectedVersion: Int) {
        val r = release ?: return
        if (r.versionCode != expectedVersion) { error = "Каталог обновился. Проверьте новую версию и подтвердите установку ещё раз."; notifyChanged(); return }
        if (!changesAllowed || r.versionCode <= module.versionCode || r.minAppVersion > BuildConfig.VERSION_CODE) return
        requestedRelease = r; updateRequested = true; updateProgress = -1; error = ""; notice = ""; notifyChanged()
        try { startForegroundService(Intent(this, ModuleUpdateService::class.java)) }
        catch (e: Exception) { updateRequested = false; error = "Не удалось начать обновление: ${e.message}"; notifyChanged() }
    }
    fun performModuleUpdate(done: () -> Unit) {
        if (!updateRequested || busy.isNotEmpty()) { done(); return }
        operation("Подготовка обновления…", updateOperation = true, finished = done) {
            val r = requestedRelease ?: error("Нет данных подтверждённого обновления")
            val current = backend.updateStatus()
            check(!current.blocksChanges && !backend.poll().active) { "Сначала завершите текущую операцию." }
            check(r.versionCode > backend.inspect().versionCode) { "Эта версия уже установлена." }
            val file = updater.download(r) { percent -> main.post {
                updateProgress = percent; busy = "Скачиваем обновление: $percent%"; notifyChanged()
            } }
            main.post { updateProgress = -1; busy = "Сохраняем настройки и устанавливаем обновление…"; notifyChanged() }
            val output = backend.installUpdate(file, r); main.post { appendLog(output) }
            val deadline = System.nanoTime() + 10L * 60 * 1_000_000_000
            var state = backend.updateStatus()
            while (state.installing && System.nanoTime() < deadline) {
                val copy = state; main.post { update = copy; notifyChanged() }
                Thread.sleep(1500); state = backend.updateStatus()
            }
            val final = state
            main.post {
                update = final
                notice = when {
                    final.pending -> "Обновление установлено. Перезагрузите приставку для завершения."
                    final.installing -> "Установка продолжается. Её состояние доступно на экране обновлений."
                    else -> "Обновление не завершено. Откройте подробности на экране обновлений."
                }
            }
        }
    }
    fun rebootAfterUpdate() = operation("Перезагружаем приставку…") { backend.reboot() }
    fun readLog() = operation("Читаем журнал…") { val text = backend.log(); main.post { fullLog = text; notice = "Журнал обновлён" } }
    fun poll() {
        if (busy.isNotEmpty() || updateRequested || polling || !connected && !update.installing) return
        polling = true
        worker.execute {
            try {
                val u = backend.updateStatus(); val s = backend.poll(); persist(s)
                val m = if ((snapshot.active && !s.active || update.installing && !u.installing) && !u.installing) backend.inspect() else null
                main.post { update = u; accept(s); if (m != null) { module = m; connected = true }; polling = false; notifyChanged() }
            } catch (e: Exception) { main.post { polling = false; error = "Не удалось обновить состояние: ${e.message}"; notifyChanged() } }
        }
    }
    private fun operation(label: String, updateOperation: Boolean = false, finished: () -> Unit = {}, block: () -> Unit) {
        if (busy.isNotEmpty() || updateRequested && !updateOperation) return
        busy = label; error = ""; notice = ""; notifyChanged()
        worker.execute {
            try { block() }
            catch (e: Exception) { main.post { error = e.message ?: e.javaClass.simpleName; appendLog("$label\n$error") } }
            finally { main.post { busy = ""; if (updateOperation) updateRequested = false; notifyChanged(); finished() } }
        }
    }
    fun diagnosticText(): String = "SV Pocket ${BuildConfig.VERSION_NAME}\nPocket ${module.version}\nСервис: ${module.service}\nСтратегия: ${module.selected}\nСессия: ${snapshot.id}\n${snapshot.headline()}\n${snapshot.completed}/${snapshot.total}\nОбновление: ${update.phase} v${update.version}\n\n$error\n\n$operationLog\n\n${update.detail}\n\n${fullLog.ifEmpty { snapshot.tail }}"
    fun serviceLabel(status: String) = when (status) { "running" -> "включён"; "stopped" -> "выключен"; "degraded" -> "нужен перезапуск"; else -> "состояние неизвестно" }
}
