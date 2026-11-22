package com.slawa99.pockettv

import android.content.Context
import android.os.Handler
import java.util.UUID
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

internal data class VideoRun(val id: String, val catalog: String, val original: String, val wasOn: Boolean,
    val queue: List<String>, val network: Long, val rows: List<VideoMark> = emptyList(), val phase: String = "ready",
    val owned: String = "", val token: String = "", val message: String = "", val winner: String = "", val clip: Int = 0, val firstClipError: String = "") {
    val passCount get() = VideoRank.winners(rows).size

    fun diagnosticText() = buildString {
        append("$phase · проверено: ${rows.size}/${queue.size} · воспроизвели видео: $passCount")
        if (message.isNotBlank()) append(" · ").append(message)
        if (rows.isNotEmpty()) {
            append("\nРезультаты плеера по порядку проверки:")
            rows.forEachIndexed { i, row ->
                append("\n").append(i + 1).append(". ").append(row.name).append(" — ")
                if (row.passed) append("20 с без остановок; время до старта видео: ${row.startMs} мс")
                else {
                    append(row.description())
                    if (row.detail.isNotBlank()) append(" · ").append(row.detail)
                }
            }
        }
    }

    fun encode() = JSONObject().put("id", id).put("cat", catalog).put("orig", original).put("on", wasOn)
        .put("q", JSONArray(queue)).put("net", network).put("p", phase).put("o", owned).put("t", token)
        .put("clip", clip).put("firstClipError", firstClipError).put("msg", message).put("win", winner).put("rows", JSONArray().also { arr -> rows.forEach {
            arr.put(JSONArray().put(it.name).put(it.status).put(it.startMs).put(it.detail)) } }).toString()
    companion object {
        fun decode(s: String?): VideoRun? {
            if (s.isNullOrEmpty()) return null
            val o = JSONObject(s); val q = o.getJSONArray("q"); val a = o.getJSONArray("rows")
            return VideoRun(o.getString("id"), o.getString("cat"), o.getString("orig"), o.getBoolean("on"),
                (0 until q.length()).map(q::getString), o.getLong("net"), (0 until a.length()).map { i ->
                    val row = a.getJSONArray(i); VideoMark(row.getString(0),row.getString(1),row.getLong(2),row.optString(3))
                }, o.getString("p"), o.optString("o"), o.optString("t"), o.optString("msg"), o.optString("win"),o.optInt("clip",0),o.optString("firstClipError"))
        }
    }
}

internal data class VideoTicket(val token: String, val name: String, val index: Int, val total: Int, val network: Long, val clip: Int, val passes: Int)

internal class VideoTournament(context: Context, private val worker: Executor, private val main: Handler,
    private val command: (String, VideoRun, String) -> String, private val changed: (Boolean) -> Unit) {
    private val pref = context.getSharedPreferences("video_tournament", Context.MODE_PRIVATE)
    var run: VideoRun? = VideoRun.decode(pref.getString("run", null)); private set
    private var pending = false
    private var stopping = ""
    private var paused = false
    private var recoveryTried = false
    private var host: ((VideoTicket) -> Boolean)? = null
    val locksControls get() = run?.phase in setOf("ready","switch","playing","commit","restore","recover")
    var armed: String
        get() = pref.getString("armed", "").orEmpty()
        set(value) { check(pref.edit().putString("armed", value).commit()) }
    init {
        run?.takeIf { locksControls }?.let {
            val msg = "Видеотест прервался. Можно продолжить с сохранёнными результатами."
            store(it.copy(phase = if (it.owned.isEmpty()) "paused" else "recover", message = msg, token = ""))
        }
    }
    private fun store(v: VideoRun) {
        check(pref.edit().putString("run",v.encode()).commit()) { "Не удалось сохранить ход видеотеста" }
        run = v
    }
    fun attach(h: (VideoTicket) -> Boolean) { host = h }
    fun detach() {
        host = null
        if (locksControls && run?.phase in setOf("ready","switch")) stop("Проверка видео приостановлена: экран закрыт.", true)
    }
    fun matches(s: TestSnapshot, m: ModuleInfo) = run?.let { it.id == s.id && it.catalog == m.catalogId } == true
    fun armSaved(s: TestSnapshot, m: ModuleInfo, network: Long) {
        val v = run
        if (v?.phase == "paused" && matches(s,m) && v.network == network && m.selected == v.original) {
            store(v.copy(phase="ready", owned="", message="", token="")); changed(false)
        } else { armed = s.id; changed(false) }
    }
    fun retryRestore() { recoveryTried = false; changed(false) }
    fun tick(s: TestSnapshot, m: ModuleInfo, available: Boolean, visible: Boolean, network: Long) {
        if (pending) return
        var v = run
        if (v?.phase == "recover") {
            if (available && !recoveryTried) { recoveryTried = true; startRestore(v, true, v.message) }
            return
        }
        if (!available || !visible || host == null) return
        if (!locksControls && armed.isNotEmpty() && armed == s.id && !s.active) {
            val candidates = VideoRank.candidates(s,m)
            val why = when {
                s.status != "completed" -> { armed = ""; return }
                !s.matches(m) -> "Каталог изменился. Нужен новый подбор."
                m.versionCode !in 71..74 -> "Для видеотеста нужен проверенный Pocket 71–74."
                candidates.isEmpty() -> "Нет проверенных кандидатов для видеотеста."
                !PocketProtocol.safeName(m.selected) || m.selected !in m.strategies || m.service !in setOf("running","stopped") ->
                    "Сначала выберите исходную стратегию и проверьте состояние обхода."
                network == 0L -> "Нет активной сети для проверки видео."
                else -> ""
            }
            v = VideoRun(s.id,m.catalogId,m.selected,m.service=="running",candidates,network,
                phase=if(why.isEmpty()) "ready" else "stopped", message=why)
            store(v); armed = ""; changed(false)
        }
        v = run ?: return
        if (v.phase != "ready") return
        if (!matches(s,m) || s.active || network == 0L || network != v.network) {
            stop("Сеть или каталог изменились. Запустите подбор для этого подключения.", false); return
        }
        when (val decision = VideoRank.decide(v.rows, v.queue.size)) {
            is VideoDecision.KeepOriginal -> {
                val why = if (decision.passes == 0) "Ни одна проверенная стратегия не воспроизвела ролик."
                    else "Проверены все кандидаты. Видео воспроизвели: ${decision.passes}; для автоматического выбора нужно ${VideoRank.MIN_PASSES}. Успешную стратегию можно выбрать вручную."
                stop(why, false)
                return
            }
            is VideoDecision.Apply -> {
                val winner = decision.winner.name
                change("commit", v.copy(phase="commit",owned=winner), winner) { outcome ->
                    if (outcome == "committed" && stopping.isEmpty()) {
                        store(run!!.copy(phase="done",winner=winner,owned="",token="",message="Видеотест завершён. Включена $winner.")); changed(true)
                    } else handleFailure(outcome)
                }
                return
            }
            VideoDecision.Continue -> Unit
        }
        val name = v.queue[v.rows.size]
        val trial = v.copy(phase="switch",owned=name,token=UUID.randomUUID().toString())
        change("try", trial, name) { outcome ->
            if (stopping.isNotEmpty()) finishStop()
            else if (outcome == "no_service") record(VideoMark(name,"no_service"))
            else if (outcome != "ready") handleFailure(outcome)
            else {
                store(run!!.copy(phase="playing"))
                val launched = host?.invoke(VideoTicket(trial.token,name,trial.rows.size+1,trial.queue.size,trial.network,trial.clip,trial.passCount)) == true
                if (!launched) stop("Видеоплеер не открыт. Вернитесь к видеотесту.", true)
                else changed(false)
            }
        }
    }
    private fun change(action: String, v: VideoRun, name: String, done: (String) -> Unit) {
        store(v); pending = true; changed(false)
        worker.execute {
            val outcome = try { command(action,v,name) } catch(e:Exception) { "error: " + (e.message ?: "нет ответа модуля").take(380) }
            main.post { pending = false; done(outcome) }
        }
    }
    private fun record(mark: VideoMark) {
        val v=run ?: return
        store(v.copy(rows=v.rows + mark,phase="ready",token="")); changed(false)
        pauseIfHidden(v.id)
    }
    private fun pauseIfHidden(id: String) {
        // Activity results can reach us just before MainActivity resumes. If Home won
        // that race, don't leave the last trial running unattended.
        main.postDelayed({ if(host==null && run?.phase=="ready" && run?.id==id)
            stop("Видеотест приостановлен при выходе из приложения.",true) },2500)
    }
    fun result(token: String, status: String, start: Long, reason: String) {
        val v=run ?: return
        if (v.phase!="playing" || token!=v.token) return
        when {
            status=="clip_unavailable" && v.clip==0 && v.rows.none { it.passed } -> {
                // Retry the SAME candidate from a fresh process; the rejected clip is not
                // a negative score and its startup latency must not affect the comparison.
                store(v.copy(phase="ready",clip=1,firstClipError=reason,token="",message="Пробуем резервный ролик YouTube."))
                changed(false);pauseIfHidden(v.id)
            }
            status=="clip_unavailable" -> {
                val detail=if(v.firstClipError.isBlank()) reason else "основной: ${v.firstClipError}; резервный: $reason"
                stop("YouTube не разрешил воспроизведение контрольного ролика ($detail). Стратегия не оценена.",false)
            }
            status=="pass" && start in 1..35_000 -> record(VideoMark(v.owned,status,start))
            status in setOf("stall","timeout","player_error") -> record(VideoMark(v.owned,status,detail=reason.take(140)))
            else -> stop(reason.ifBlank { "Видеотест остановлен." },status=="pause")
        }
    }
    private fun handleFailure(s: String) {
        if (s=="changed") {
            stopping=""; store(run!!.copy(phase="stopped",owned="",token="",message="Настройки модуля изменились вне видеотеста. Сохранён внешний выбор.")); changed(true)
        } else stop(if (s.startsWith("error: ")) s.removePrefix("error: ") else "Не удалось подтвердить переключение стратегии.", false)
    }
    fun stop(reason: String, pause: Boolean) {
        if (!locksControls || run?.phase in setOf("restore","recover")) return
        stopping=reason; paused=pause
        if (!pending) finishStop() else changed(false)
    }
    private fun finishStop() {
        val v=run ?: return
        val msg=stopping; stopping=""
        startRestore(v,paused,msg)
    }
    private fun startRestore(v: VideoRun, pause: Boolean, why: String) {
        fun terminal() {
            store(run!!.copy(phase=if (pause) "paused" else "stopped", owned="",token="",message=why + " Сохранена прежняя настройка."))
            changed(true)
        }
        if (v.owned.isEmpty()) { terminal(); return }
        change(if (v.wasOn) "restore-on" else "restore-off",v.copy(phase="restore",token="",message=why),v.owned) { result ->
            when (result) {
                "restored" -> terminal()
                "changed" -> handleFailure(result)
                else -> {
                    recoveryTried=true
                    store(run!!.copy(phase="recover",message="Не удалось восстановить обход. Проверьте Magisk и повторите восстановление. " + result.removePrefix("error: ")))
                    changed(false)
                }
            }
        }
    }
}
