package com.slawa99.pockettv

internal data class VideoMark(val name: String, val status: String, val startMs: Long = 0, val detail: String = "") {
    val passed get() = status == "pass"
    fun description() = when (status) {
        "pass" -> "Видео: 20 с без остановок · старт за " + java.lang.String.format(java.util.Locale.US, "%.1f", startMs / 1000.0) + " с"
        "stall" -> "Видео остановилось или буферизовалось"
        "player_error" -> "Плеер не воспроизвёл видео с этой стратегией (ошибка 5)"
        "no_service" -> "Сервис не запустился с этой стратегией"
        else -> "Видео не начало воспроизводиться за отведённое время"
    }
}

internal sealed interface VideoDecision {
    data object Continue : VideoDecision
    data class Apply(val winner: VideoMark) : VideoDecision
    data class KeepOriginal(val passes: Int) : VideoDecision
}

internal object VideoRank {
    const val GROUP = 5
    const val MIN_PASSES = 3
    fun candidates(s: TestSnapshot, m: ModuleInfo): List<String> =
        if (s.status != "completed" || s.profile != "youtube" || s.restore == "failed" || !s.matches(m)) emptyList()
        else s.ranked.filter { it.status == "OK" && it.total > 0 && it.name in m.strategies && PocketProtocol.safeName(it.name) }
            .map { it.name }.distinct()
    // Rows follow the saved network ranking. Stable sorting retains that order on a tie.
    fun winners(rows: List<VideoMark>) = rows.filter { it.passed }.distinctBy { it.name }.sortedBy { it.startMs }

    fun decide(rows: List<VideoMark>, count: Int): VideoDecision {
        val exhausted = rows.size >= count
        if (!exhausted && (rows.isEmpty() || rows.size % GROUP != 0)) return VideoDecision.Continue
        val passed = winners(rows)
        return when {
            passed.size >= MIN_PASSES -> VideoDecision.Apply(passed.first())
            exhausted -> VideoDecision.KeepOriginal(passed.size)
            else -> VideoDecision.Continue
        }
    }
}

// Wall time AND the media playhead must both advance. A first frame, a seek,
// a buffering state, cached metadata or a frozen PLAYING state is not a pass.
internal class VideoMeter(val began: Long) {
    private var positionAt = -1L
    private var lastPosition = -1.0
    private var first = -1L
    private var moved = -1L
    private var mediaSeconds = 0.0
    var startup = 0L; private set
    val seconds: Int get() = mediaSeconds.toInt().coerceIn(0, 20)
    fun sample(now: Long, state: Int, pos: Double): String? {
        if (!pos.isFinite() || pos < 0) return null
        // Some WebViews report the same timestamp on multiple polls, then deliver
        // a one-second jump. Compare with wall time since the last *change*, not
        // since the last poll. A real seek still cannot earn playback credit.
        if (positionAt < 0) { positionAt = now; lastPosition = pos }
        val dt = now - positionAt
        val delta = pos - lastPosition
        if (delta > 0.001 || delta < -0.001) {
            if (state == 1 && dt > 0 && delta > 0 && delta <= dt / 1000.0 + 0.75) {
                if (first < 0) { first = now; startup = now - began }
                else mediaSeconds += delta
                moved = now
            } else if (first >= 0 && (delta < -0.25 || delta > dt.coerceAtLeast(0) / 1000.0 + 0.75)) {
                return "stall"
            }
            lastPosition = pos; positionAt = now
        }
        if (first >= 0 && (now - moved >= 2500 || state in listOf(0, 2, 3, 5))) return "stall"
        if (first >= 0 && now - first >= 20_000 && mediaSeconds >= 20.0) return "pass"
        if (first < 0 && now - began >= 35_000) return "timeout"
        return if (now - began >= 65_000) "stall" else null
    }
}
