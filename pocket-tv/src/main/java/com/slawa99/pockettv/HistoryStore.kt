package com.slawa99.pockettv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "last-test.json"))
    fun save(s: TestSnapshot) {
        val rows = JSONArray()
        s.results.forEach { r -> rows.put(JSONObject().put("name", r.name).put("status", r.status)
            .put("h", r.httpOk).put("ht", r.httpTotal).put("a", r.tls12Ok).put("at", r.tls12Total)
            .put("b", r.tls13Ok).put("bt", r.tls13Total)) }
        val json = JSONObject().put("id", s.id).put("status", s.status).put("total", s.total)
            .put("current", s.current).put("started", s.started).put("profile", s.profile)
            .put("tail", s.tail).put("exit", s.exitCode).put("restore", s.restore).put("failure", s.failure).put("results", rows)
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun load(): TestSnapshot = try {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val a = json.getJSONArray("results")
        val rows = (0 until a.length()).map { i -> a.getJSONObject(i).let { r ->
            ProbeResult(r.getString("name"), r.getString("status"), r.getInt("h"), r.getInt("ht"),
                r.getInt("a"), r.getInt("at"), r.getInt("b"), r.getInt("bt")) } }
        TestSnapshot(json.getString("id"), json.getString("status"), json.getInt("total"),
            json.optString("current"), json.optLong("started"), json.optString("profile", "youtube"), rows,
            json.optString("tail"), json.optString("exit"), json.optString("restore"), json.optString("failure"))
    } catch (_: Exception) { TestSnapshot() }
}
