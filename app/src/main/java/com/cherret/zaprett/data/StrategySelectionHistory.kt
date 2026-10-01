package com.cherret.zaprett.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The last completed selection, kept on the device across activity and process restarts. */
object StrategySelectionHistory {
    private const val KEY = "strategy_selection_history_v1"

    data class Snapshot(
        val serviceType: ServiceType,
        val results: List<StrategyCheckResult>,
        val checked: Int,
        val summary: String,
        val diagnostic: String
    )

    fun load(prefs: SharedPreferences, serviceType: ServiceType): Snapshot? = runCatching {
        val raw = prefs.getString(KEY, null) ?: return@runCatching null
        val json = JSONObject(raw)
        if (json.optInt("version") != 1 || json.optString("serviceType") != serviceType.name) return@runCatching null
        val rows = json.getJSONArray("results")
        val results = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val domainsJson = row.getJSONArray("domains")
                val domains = (0 until domainsJson.length()).map { domainsJson.getString(it) }
                add(StrategyCheckResult(
                    path = row.getString("path"),
                    name = row.getString("name"),
                    progress = row.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                    domains = domains,
                    status = when (row.optString("status")) {
                        StrategyTestingStatus.Completed.name -> StrategyTestingStatus.Completed
                        StrategyTestingStatus.Failed.name -> StrategyTestingStatus.Failed
                        else -> StrategyTestingStatus.Waiting
                    },
                    problem = row.optString("problem"),
                    checkedDomains = row.optInt("checkedDomains").coerceAtLeast(0)
                ))
            }
        }
        Snapshot(serviceType, results, json.optInt("checked"), json.optString("summary"), json.optString("diagnostic"))
    }.getOrNull()

    fun save(prefs: SharedPreferences, snapshot: Snapshot): Boolean {
        val json = JSONObject()
            .put("version", 1)
            .put("serviceType", snapshot.serviceType.name)
            .put("checked", snapshot.checked)
            .put("summary", snapshot.summary)
            .put("diagnostic", snapshot.diagnostic)
        val rows = JSONArray()
        snapshot.results.forEach { item ->
            val domains = JSONArray()
            item.domains.forEach { domains.put(it) }
            rows.put(JSONObject()
                .put("path", item.path)
                .put("name", item.name)
                .put("progress", item.progress.toDouble())
                .put("domains", domains)
                .put("status", item.status.name)
                .put("problem", item.problem)
                .put("checkedDomains", item.checkedDomains))
        }
        return prefs.edit().putString(KEY, json.put("results", rows).toString()).commit()
    }
}
