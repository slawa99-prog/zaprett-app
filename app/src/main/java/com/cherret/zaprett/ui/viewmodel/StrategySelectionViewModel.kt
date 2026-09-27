package com.cherret.zaprett.ui.viewmodel

import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.cherret.zaprett.R
import com.cherret.zaprett.byedpi.ByeDpiVpnService
import com.cherret.zaprett.data.ServiceStatus
import com.cherret.zaprett.data.ServiceType
import com.cherret.zaprett.data.StrategyCheckResult
import com.cherret.zaprett.data.StrategyTestingStatus
import com.cherret.zaprett.utils.disableStrategy
import com.cherret.zaprett.utils.enableStrategy
import com.cherret.zaprett.utils.getActiveLists
import com.cherret.zaprett.utils.getAllStrategies
import com.cherret.zaprett.utils.getActiveStrategy
import com.cherret.zaprett.utils.getServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import com.topjohnwu.superuser.Shell
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class StrategySelectionViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = application.getSharedPreferences("settings", MODE_PRIVATE)
    val context = application
    private val _requestVpnPermission = MutableStateFlow(false)
    val requestVpnPermission = _requestVpnPermission.asStateFlow()
    private val _errorFlow = MutableStateFlow("")
    val errorFlow = _errorFlow.asStateFlow()
    val strategyStates = mutableStateListOf<StrategyCheckResult>()
    var noHostsCard = mutableStateOf(false)
        private set
    var isTesting = mutableStateOf(false)
        private set

    init {
        loadStrategies()
        checkHosts()
    }

    fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .callTimeout(prefs.getLong("probe_timeout", 1000L), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (getServiceType(prefs) == ServiceType.byedpi) {
            val ip = prefs.getString("ip", "127.0.0.1") ?: "127.0.0.1"
            val port = prefs.getString("port", "1080")?.toIntOrNull() ?: 1080
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(ip, port))
            builder.proxy(proxy)
        }
        return builder.build()
    }

    fun loadStrategies() {
        val strategyList = getAllStrategies(prefs)
        strategyStates.clear()
        strategyList.forEach { manifest ->
            strategyStates += StrategyCheckResult(
                path = manifest.manifestPath,
                name = manifest.name,
                status = StrategyTestingStatus.Waiting,
                progress = 0f,
                domains = emptyList()
            )
        }
    }

    suspend fun testDomain(domain : String) : Boolean  = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://$domain").build()
        try {
            buildHttpClient().newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun countReachable(index: Int, urls: List<String>): Float = coroutineScope {
        if (urls.isEmpty()) return@coroutineScope 0f
        val results: List<String> = urls.map { url ->
            async { if (testDomain(url)) url else null }
        }.awaitAll().filterNotNull()
        strategyStates[index].domains = results
        (results.size.toFloat() / urls.size.toFloat()).coerceIn(0f, 1f)
    }

    suspend fun readActiveListsLines(): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        getActiveLists(prefs).forEach { path ->
            runCatching {
                File(path.file).useLines { lines ->
                    lines.forEach { line ->
                        result += line
                    }
                }
            }.onFailure {
                Log.e("Error", "Occured error when creating list for check")
            }
        }
        result
    }
    // The active list can contain comments and thousands of hosts. A short, valid sample keeps
    // the test bounded and avoids treating comments as failed HTTPS probes.
    private suspend fun probeTargets(): List<String> = readActiveListsLines()
        .map { it.substringBefore('#').trim().lowercase() }
        .filter { it.matches(Regex("(?:[a-z0-9-]+\\.)+[a-z]{2,}")) }
        .distinct()
        .take(20)

    private suspend fun rootCommand(command: String) = withContext(Dispatchers.IO) {
        val result = Shell.cmd("zaprett $command 2>&1").exec()
        if (!result.isSuccess) {
            throw IllegalStateException(result.out.joinToString("\n").ifBlank { "zaprett $command failed" })
        }
    }

    private suspend fun rootRunning(): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd("zaprett status").exec()
        if (!result.isSuccess) throw IllegalStateException(result.out.joinToString("\n"))
        result.out.any { it.trim() == "zaprett is working" }
    }

    private suspend fun waitForRootService() {
        repeat(20) {
            if (rootRunning()) return
            delay(250L)
        }
        throw IllegalStateException("zaprett did not start")
    }

    /** Returns the applied strategy name, or null when nothing was applied. */
    suspend fun performTest(autoApply: Boolean = false): String? {
        if (isTesting.value) return null
        isTesting.value = true
        _errorFlow.value = ""
        var selected: StrategyCheckResult? = null
        val serviceType = getServiceType(prefs)
        val originalPath = getActiveStrategy(prefs).getOrNull()?.manifestPath.orEmpty()
        var currentPath = originalPath
        var wasRunning = false
        var initialStatusKnown = false
        try {
            val targets = probeTargets()
            if (targets.isEmpty() || strategyStates.isEmpty()) {
                _errorFlow.value = context.getString(R.string.selection_no_targets)
                return null
            }
            if (serviceType != ServiceType.byedpi) {
                wasRunning = rootRunning()
                initialStatusKnown = true
                if (wasRunning) rootCommand("stop")
            }
            for (index in strategyStates.indices) {
                val current = strategyStates[index]
                strategyStates[index] = current.copy(status = StrategyTestingStatus.Testing)
                if (serviceType == ServiceType.byedpi) {
                    // Preserve the existing VPN test path for devices without root.
                    enableStrategy(current.path, prefs)
                    currentPath = current.path
                    if (ByeDpiVpnService.status == ServiceStatus.Connected) {
                        context.startService(Intent(context, ByeDpiVpnService::class.java).apply { action = "STOP_VPN" })
                        delay(300L)
                    }
                    _requestVpnPermission.value = true
                    val connected = withTimeoutOrNull(10_000L) {
                        while (ByeDpiVpnService.status != ServiceStatus.Connected) delay(100L)
                        true
                    } ?: false
                    if (!connected) throw IllegalStateException("VPN did not start")
                    delay(150L)
                    try {
                        val score = countReachable(index, targets)
                        strategyStates[index] = current.copy(progress = score, status = StrategyTestingStatus.Completed)
                    } finally {
                        context.startService(Intent(context, ByeDpiVpnService::class.java).apply { action = "STOP_VPN" })
                        delay(200L)
                    }
                } else {
                    enableStrategy(current.path, prefs)
                    currentPath = current.path
                    if (getActiveStrategy(prefs).getOrNull()?.manifestPath != current.path) {
                        throw IllegalStateException("Could not save strategy: ${current.name}")
                    }
                    try {
                        rootCommand("start")
                        waitForRootService()
                        val score = countReachable(index, targets)
                        strategyStates[index] = current.copy(progress = score, status = StrategyTestingStatus.Completed)
                    } finally {
                        rootCommand("stop")
                    }
                }
            }
            if (autoApply && serviceType != ServiceType.byedpi) {
                selected = strategyStates.filter { it.status == StrategyTestingStatus.Completed && it.progress > 0f }
                    .maxByOrNull { it.progress }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _errorFlow.value = error.message ?: context.getString(R.string.error_unknown)
        } finally {
          withContext(NonCancellable) {
            // Restore the original configuration on failure, and the original running state
            // after a manual comparison. An automatically selected strategy starts immediately.
            val finalPath = selected?.path ?: originalPath
            try {
                if (serviceType != ServiceType.byedpi && initialStatusKnown && rootRunning()) {
                    rootCommand("stop")
                }
                if (currentPath != finalPath) {
                    if (finalPath.isEmpty()) disableStrategy(currentPath, prefs)
                    else enableStrategy(finalPath, prefs)
                }
                if (serviceType != ServiceType.byedpi && initialStatusKnown && (wasRunning || selected != null)) {
                    rootCommand("start")
                    waitForRootService()
                }
            } catch (error: Exception) {
                selected = null
                _errorFlow.value = error.message ?: context.getString(R.string.error_unknown)
                // If activating the winner failed, put the previous strategy and service back.
                if (serviceType != ServiceType.byedpi && initialStatusKnown) {
                    try {
                        if (rootRunning()) rootCommand("stop")
                        if (originalPath.isEmpty()) disableStrategy(currentPath, prefs)
                        else enableStrategy(originalPath, prefs)
                        if (wasRunning) rootCommand("start")
                    } catch (restoreError: Exception) {
                        _errorFlow.value += "\n" + (restoreError.message ?: "Could not restore service")
                    }
                }
            }
            val sorted = strategyStates.sortedByDescending { it.progress }
            strategyStates.clear()
            strategyStates.addAll(sorted)
            isTesting.value = false
          }
        }
        return selected?.name
    }

    fun checkHosts() {
        if (getActiveLists(prefs).isEmpty() || getAllStrategies(prefs).isEmpty()) noHostsCard.value = true
        Log.d("getActiveLists.isEmpty || getAllStrategies.isEmpty", getActiveLists(prefs).isEmpty().toString())
    }
    fun startVpn() {
        ContextCompat.startForegroundService(context, Intent(context, ByeDpiVpnService::class.java).apply { action = "START_VPN" })
    }
    fun clearVpnPermissionRequest() {
        _requestVpnPermission.value = false
    }
    fun clearError() {
        _errorFlow.value = ""
    }
}
