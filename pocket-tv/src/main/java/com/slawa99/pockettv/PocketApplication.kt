package com.slawa99.pockettv

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

data class HelpReport(val title: String = "Поможем восстановить YouTube", val detail: String =
    "Проверим интернет и состояние обхода, затем предложим следующий шаг.", val ready: Boolean = false, val offline: Boolean = false,
    val phase: String = "check")

open class PocketApplication : Application() {
    companion object {
        internal var backendFactory: ((Application) -> PocketBackend)? = null
        private const val STATE_CHECK = "Проверяем состояние обхода…"
    }
    val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // Cancellation must not queue behind a slow read-only status request.
    private val cancelWorker = Executors.newSingleThreadExecutor()
    private val updateChecker = Executors.newSingleThreadExecutor()
    private lateinit var history: HistoryStore
    private val prefs by lazy { getSharedPreferences("friendly", MODE_PRIVATE) }
    private val backend by lazy { backendFactory?.invoke(this) ?: RootBackend(this) }
    internal val video: VideoTournament by lazy {
        VideoTournament(this,worker,main,{ action, run, target ->
            val began = android.os.SystemClock.elapsedRealtime()
            fun logStep(result: String) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - began
                main.post { appendLog("Видеотест: $action · $target · ${elapsed} мс · $result") }
            }
            try { backend.videoTransition(action,run.id,run.catalog,run.original,target).also(::logStep) }
            catch (e: Exception) { logStep(failureDetails(e)); throw e }
        },{ refreshState ->
            notifyChanged()
            if (refreshState) refresh()
        })
    }
    private var retiringPlayer: android.os.IBinder? = null
    internal fun acceptVideoReply(b: android.os.Bundle) {
        val token=b.getString("token").orEmpty()
        if (video.run?.token!=token || video.run?.phase!="playing") return
        val life=b.getBinder("player_process")
        if (life!=null && life.isBinderAlive) {
            retiringPlayer=life
            try { life.linkToDeath({ main.post {
                if (retiringPlayer===life) { retiringPlayer=null; notifyChanged() }
            } },0) } catch (_:android.os.RemoteException) { retiringPlayer=null }
            main.postDelayed({
                if (retiringPlayer===life) {
                    retiringPlayer=null
                    video.stop("Предыдущий плеер не закрылся. Можно продолжить видеотест.",true)
                    notifyChanged()
                }
            },6000)
        }
        video.result(token,b.getString("code").orEmpty(),b.getLong("ms"),b.getString("reason").orEmpty())
    }
    internal val canTickVideo get() = retiringPlayer==null && busy.isEmpty() && !polling && !cancelInFlight && !updateRequested &&
        connected && !snapshot.active && !update.blocksChanges
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
    @Volatile private var stateEpoch = 0
    var cancelInFlight = false; private set
    private val tried = linkedSetOf<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    val changesAllowed get() = !video.locksControls && busy.isEmpty() && !cancelInFlight && !updateRequested && !update.blocksChanges && connected && !snapshot.active
    val canCancel get() = snapshot.active && !snapshot.cancelRequested && !cancelInFlight &&
        snapshot.status !in setOf("restoring", "cancelling") && !updateRequested && !update.installing &&
        (busy.isEmpty() || busy == STATE_CHECK)
    val awaitingVideo get() = prefs.getBoolean("awaiting_video", false) &&
        prefs.getString("video_strategy", "") == module.selected && prefs.getString("video_catalog", "") == module.catalogId
    val automaticFastStart get() = prefs.getBoolean("automatic_fast_start", true)
    private var foregroundFastCheck = false
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
        val verified = video.run?.takeIf { video.matches(snapshot,module) }?.let { VideoRank.winners(it.rows).map { row -> row.name } }.orEmpty()
        return (verified + ranked + module.strategies).distinct().firstOrNull { it != module.selected && it !in tried && it in module.strategies }
    }
    internal open fun videoProviderAsset() = "video_provider.js"
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == packageName + ":videoprobe") {
            android.webkit.WebView.setDataDirectorySuffix("sv_video_probe")
            return
        }
        history = HistoryStore(this); snapshot = history.load(); release = updater.cached()
    }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun appendLog(text: String) { operationLog = (operationLog + "\n" + text).takeLast(50000) }
    private fun failureDetails(e: Exception) = (e as? RootCommandFailure)?.diagnostic ?: e.message.orEmpty()
    fun clearError() { error = ""; notifyChanged() }
    @Synchronized private fun persist(s: TestSnapshot, epoch: Int = stateEpoch) {
        if (epoch == stateEpoch && s.id.isNotEmpty()) history.save(s)
    }
    private fun accept(s: TestSnapshot) {
        if (s.id.isEmpty()) return
        if (snapshot.active && !s.active || s.cancelRequested || s.status in setOf("cancelling", "restoring")) notice = s.headline()
        snapshot = s
    }
    fun refresh() {
        val epoch = stateEpoch
        operation(STATE_CHECK, finished = {
            if (epoch != stateEpoch && !connected && !cancelInFlight) refresh()
        }) {
            val started = System.nanoTime()
            try {
                val state = backend.readState(); persist(state.test, epoch)
                main.post {
                    if (epoch != stateEpoch) return@post
                    update = state.update; state.module?.let { module = it; connected = true }; accept(state.test)
                    state.module?.let { reconcileFastStart(it, snapshot, update) }
                    appendLog("Состояние: ${(System.nanoTime() - started) / 1_000_000} мс")
                    if (update.phase == "completed") notice = "Обновление завершено. Каталог стратегий обновлён."
                }
            } catch (e: Exception) {
                main.post { if (epoch == stateEpoch) { connected = false; error = e.message.orEmpty(); appendLog(failureDetails(e)) } }
            }
        }
    }
    private fun cancelTest() {
        if (!canCancel) return
        val epoch = ++stateEpoch
        cancelInFlight = true; error = ""; notice = "Отправляем команду остановки…"; notifyChanged()
        cancelWorker.execute {
            val started = System.nanoTime()
            try {
                val s = backend.cancelTest(); persist(s, epoch)
                main.post {
                    if (epoch == stateEpoch) {
                        accept(s); notice = s.headline()
                        appendLog("Команда остановки принята: ${(System.nanoTime() - started) / 1_000_000} мс")
                    }
                }
            } catch (e: Exception) {
                main.post { error = e.message ?: "Не удалось остановить подбор"; notice = ""; appendLog(failureDetails(e)) }
            } finally { main.post {
                cancelInFlight = false; notifyChanged()
                if (!connected && busy.isEmpty()) refresh()
            } }
        }
    }
    fun control(command: String, argument: String = "", helpAfter: Boolean = false) {
        if (command == "cancel-test") { cancelTest(); return }
        operation(when (command) {
            "apply" -> "Включаем стратегию $argument…"
            "start" -> "Включаем обход…"
            "stop" -> "Выключаем обход…"
            else -> "Перезапускаем обход…"
        }) {
            val output = backend.command(command, argument)
            val state = backend.readState(); val m = state.module ?: error("Не удалось прочитать состояние модуля")
            val s = state.test; persist(s)
            main.post {
                appendLog(output); module = m; update = state.update; accept(s); connected = true
                notice = "Обход: ${serviceLabel(m.service)}. Стратегия: ${m.selected}"
                if (helpAfter) {
                    helpAlternatives = false
                    help = HelpReport("Теперь проверьте видео", "Откройте YouTube или SmartTube, включите ролик и вернитесь сюда. Сообщите, получилось ли посмотреть видео.", true, phase = "video")
                }
            }
        }
    }
    private fun ensureFastStart(source: String): FastStartResult {
        if (!automaticFastStart) return FastStartResult.OPTED_OUT
        var detail = ""
        val result = try { backend.ensureAutomaticFastStart() }
            catch (e: Exception) { detail = ": " + (e.message ?: "нет ответа"); FastStartResult.FAILED }
        // Synchronous on the serial worker: the background process can die just after jobFinished.
        prefs.edit().putString("fast_start_last", "$source — ${result.label}$detail").commit()
        if (result == FastStartResult.APPLIED || result == FastStartResult.ALREADY_ON) main.post {
            if (connected && automaticFastStart && module.fastStart == "off") {
                module = module.copy(fastStart = "on"); notifyChanged()
            }
        }
        return result
    }

    internal fun afterReplacement(stamp: Long, active: AtomicBoolean, finish: (Boolean) -> Unit) {
        worker.execute {
            var retry = false
            try {
                if (active.get() && automaticFastStart) {
                    val previous = if (prefs.getLong("fast_job_stamp", -1L) == stamp)
                        prefs.getInt("fast_job_attempts", 0) else 0
                    if (previous < FastStartJob.MAX_ATTEMPTS) {
                        val attempt = previous + 1
                        prefs.edit().putLong("fast_job_stamp", stamp).putInt("fast_job_attempts", attempt).commit()
                        retry = ensureFastStart("После обновления APK") == FastStartResult.DEFERRED &&
                            attempt < FastStartJob.MAX_ATTEMPTS
                    }
                }
            } finally { main.post { finish(retry) } }
        }
    }

    private fun reconcileFastStart(m: ModuleInfo, test: TestSnapshot, u: ModuleUpdateState) {
        if (!automaticFastStart || foregroundFastCheck || !m.bootEnabled || m.fastStart != "off" ||
            test.active || u.blocksChanges || updateRequested) return
        foregroundFastCheck = true
        // Publish the ordinary status immediately; migration never blocks initial screen rendering.
        worker.execute {
            try {
                if (ensureFastStart("При открытом SV Pocket") == FastStartResult.DEFERRED)
                    main.post { FastStartJob.schedule(this) }
            } finally { main.post { foregroundFastCheck = false } }
        }
    }
    fun setFastStart(enabled: Boolean) {
        if (!changesAllowed || module.fastStart !in setOf("on", "off")) return
        val mode = if (enabled) "on" else "off"
        operation("Сохраняем режим запуска…") {
            // Save an explicit opt-out before touching Pocket. A later APK must respect it.
            check(prefs.edit().putBoolean("automatic_fast_start", enabled).commit()) { "Не удалось сохранить выбор режима запуска." }
            backend.command("fast-start", mode)
            val m = backend.inspect()
            check(m.fastStart == mode) { "Не удалось подтвердить режим. Проверьте состояние модуля." }
            main.post {
                module = m
                notice = if (enabled) "Быстрый запуск включён. Он подействует со следующего запуска обхода."
                         else "Обычный запуск восстановлен."
            }
        }
    }
    fun launch(profile: String) = operation("Готовим подбор стратегий…") {
        val id = java.util.UUID.randomUUID().toString()
        video.armed = if (profile == "youtube") id else ""
        val output = backend.launchWithId(profile,id)
        val state = backend.poll(); persist(state)
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
        tried.clear(); helpAlternatives = false; help = HelpReport("Рабочая стратегия сохранена", "${module.selected}\nВы подтвердили воспроизведение видео. При смене сети проверьте его ещё раз.", true, phase = "done")
        notice = "Сохранена рабочая стратегия: ${module.selected}"; notifyChanged()
    }
    fun videoFailed() {
        helpAlternatives = true
        if (selectedConfirmed) prefs.edit().remove("working_name").remove("working_catalog").apply()
        tried.add(module.selected); prefs.edit().putBoolean("awaiting_video", false).apply()
        help = HelpReport("Попробуем другую стратегию", "Выберите следующую стратегию или верните последнюю рабочую. Если подходящих результатов нет, запустите полный подбор.", true, phase = "alternatives")
        notice = "Вы сообщили: видео не работает со стратегией ${module.selected}"
        notifyChanged()
    }
    fun diagnose() = operation("Проверяем интернет…") {
        val epoch = stateEpoch
        val network = getSystemService(ConnectivityManager::class.java)
        val caps = network.getNetworkCapabilities(network.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            main.post { helpAlternatives = false; help = HelpReport("Нет подключения к интернету", "Подключите Wi-Fi или сетевой кабель, затем нажмите «Проверить ещё раз».", offline = true) }
            return@operation
        }
        val verified = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || probeInternet()
        main.post { busy = "Проверяем состояние обхода…"; notifyChanged() }
        try {
            val state = backend.readState(); val u = state.update
            if (u.blocksChanges) {
                main.post { update = u; help = HelpReport("Сначала завершите обновление", "Откройте «Обновление обхода». После установки потребуется перезагрузка.") }
                return@operation
            }
            val m = state.module ?: error("Не удалось прочитать состояние модуля")
            val s = state.test; persist(s, epoch)
            main.post {
                if (epoch != stateEpoch) return@post
                update = u; module = m; connected = true; accept(s); helpAlternatives = false
                help = if (s.active) HelpReport("Сейчас идёт подбор", "Дождитесь его окончания или остановите подбор. Во время проверки видео может не работать.")
                    else HelpReport(if (m.service == "running") "Обход включён" else "Обход нужно включить",
                        (if (verified) "Подключение к интернету подтверждено.\n" else "Сеть подключена, но проверочные сайты не ответили. Проверьте, открываются ли другие сервисы.\n") +
                        (if (m.service == "running") "Попробуйте перезапустить обход, затем откройте видео." else "Нажмите «Включить обход», затем проверьте видео."), true, phase = "repair")
            }
        } catch (e: Exception) {
            main.post { if (epoch == stateEpoch) { connected = false; help = HelpReport("Не удалось проверить состояние обхода", e.message ?: "Проверьте разрешение суперпользователя для SV Pocket."); appendLog(failureDetails(e)) } }
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
        if (busy.isNotEmpty() || cancelInFlight || updateRequested || polling || !connected && !snapshot.active && !update.installing) return
        polling = true
        val epoch = stateEpoch
        val wasActive = snapshot.active
        val wasInstalling = update.installing
        worker.execute {
            try {
                val state = backend.readState(includeModule = false)
                val u = state.update; val s = state.test; persist(s, epoch)
                val inspectAfter = epoch == stateEpoch && (wasActive && !s.active || wasInstalling && !u.installing) && !u.installing
                // Normal progress needs one UI update. For the final result
                // keep the early notification before the slower catalog inspection.
                if (!inspectAfter) {
                    main.post {
                        polling = false
                        if (epoch == stateEpoch) { update = u; accept(s) }
                        notifyChanged()
                    }
                    return@execute
                }
                main.post {
                    if (epoch == stateEpoch) { update = u; accept(s); notifyChanged() }
                }
                val m = if (epoch == stateEpoch) backend.inspect() else null
                main.post { if (epoch == stateEpoch && m != null) { module = m; connected = true; reconcileFastStart(m, snapshot, update) }; polling = false; notifyChanged() }
            } catch (e: Exception) { main.post { polling = false; if (epoch == stateEpoch) { error = "Не удалось обновить состояние: ${e.message}"; appendLog(failureDetails(e)) }; notifyChanged() } }
        }
    }
    private fun operation(label: String, updateOperation: Boolean = false, finished: () -> Unit = {}, block: () -> Unit) {
        if (busy.isNotEmpty() || cancelInFlight || updateRequested && !updateOperation || video.locksControls && label != STATE_CHECK) return
        busy = label; error = ""; notice = ""; notifyChanged()
        worker.execute {
            try { block() }
            catch (e: Exception) { main.post { error = e.message ?: e.javaClass.simpleName; appendLog("$label\n${failureDetails(e)}") } }
            finally { main.post { busy = ""; if (updateOperation) updateRequested = false; notifyChanged(); finished() } }
        }
    }
    fun diagnosticText(): String = "SV Pocket ${BuildConfig.VERSION_NAME}\nPocket ${module.version}\nСервис: ${module.service}\nСтратегия: ${module.selected}\nСессия: ${snapshot.id}\n${snapshot.headline()}\n${snapshot.completed}/${snapshot.total}\nВидеотест: ${video.run?.let { "${it.phase} · ${it.rows.size}/${it.queue.size} · ${it.message}" } ?: "не запускался"}\nОбновление: ${update.phase} v${update.version}\nБыстрый запуск: ${if (automaticFastStart) "автоматически" else "обычный"}; ${prefs.getString("fast_start_last", "автопроверка ещё не выполнялась")}\n\n$error\n\n$operationLog\n\n${update.detail}\n\n${fullLog.ifEmpty { snapshot.tail }}"
    fun serviceLabel(status: String) = when (status) { "running" -> "включён"; "stopped" -> "выключен"; "degraded" -> "нужен перезапуск"; else -> "состояние неизвестно" }
}
