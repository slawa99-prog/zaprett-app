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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import com.topjohnwu.superuser.Shell
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
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
    var diagnostic = mutableStateOf("")
        private set

    private data class ProbeResult(val domain: String, val reached: Boolean, val problem: String = "")

    init {
        loadStrategies()
        checkHosts()
    }

    fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .callTimeout(prefs.getLong("probe_timeout", 6000L).coerceAtLeast(6000L), TimeUnit.MILLISECONDS)
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

    private suspend fun testDomain(client: OkHttpClient, domain: String): ProbeResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://$domain/generate_204").build()
        try {
            client.newCall(request).execute().use {
                // Any HTTP response proves DNS, TCP and TLS worked. A 403/404 is not a DPI failure.
                ProbeResult(domain, true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val cause = when (error) {
                is UnknownHostException -> context.getString(R.string.selection_dns_error)
                is SocketTimeoutException -> context.getString(R.string.selection_timeout_error)
                is SSLException -> context.getString(R.string.selection_tls_error)
                else -> error.javaClass.simpleName
            }
            ProbeResult(domain, false, cause)
        }
    }

    private suspend fun probe(client: OkHttpClient, domains: List<String>): List<ProbeResult> = try {
        coroutineScope {
            val limit = Semaphore(4)
            domains.map { domain -> async { limit.withPermit { testDomain(client, domain) } } }.awaitAll()
        }
    } finally {
        // Reusing a TLS connection after changing nfqws would invalidate the comparison.
        client.connectionPool.evictAll()
    }

    private suspend fun countReachable(client: OkHttpClient, index: Int, domains: List<String>): Float {
        val reachable = probe(client, domains).filter { it.reached }.map { it.domain }
        strategyStates[index].domains = reachable
        return reachable.size.toFloat() / domains.size
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
        .mapNotNull { host ->
            when (host) {
                // Apex domains of CDN services are not reliable HTTPS test endpoints.
                "googlevideo.com", "yt.be" -> null
                "youtube.com" -> "www.youtube.com"
                "ytimg.com" -> "i.ytimg.com"
                "ggpht.com" -> "yt3.ggpht.com"
                else -> host
            }
        }
        .distinct()
        .sortedByDescending { host ->
            host == "www.youtube.com" || host == "youtubei.googleapis.com" ||
                host == "i.ytimg.com" || host == "yt3.ggpht.com" || host == "youtu.be"
        }
        .take(12)

    private suspend fun rootCommand(command: String) = withContext(Dispatchers.IO) {
        val result = Shell.cmd("zaprett $command 2>&1").exec()
        if (!result.isSuccess) {
            throw IllegalStateException(result.out.joinToString("\n").ifBlank { "zaprett $command failed" })
        }
    }

    private suspend fun rootRunning(): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd("zaprett status 2>&1").exec()
        if (!result.isSuccess) throw IllegalStateException(
            result.out.joinToString("\n").ifBlank { "zaprett status failed" }
        )
        result.out.any { it.trim() == "zaprett is working" }
    }

    private suspend fun checkRootService() = withContext(Dispatchers.IO) {
        if (!Shell.getShell().isRoot) {
            throw IllegalStateException(context.getString(R.string.selection_root_required))
        }
        if (!Shell.cmd("command -v zaprett >/dev/null 2>&1").exec().isSuccess) {
            throw IllegalStateException(context.getString(R.string.selection_module_required))
        }
    }

    private fun describeError(stage: String, error: Exception): String {
        Log.e("StrategySelection", stage, error)
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return context.getString(R.string.selection_error_step, stage, detail)
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
        diagnostic.value = ""
        var selected: StrategyCheckResult? = null
        val serviceType = getServiceType(prefs)
        var originalPath = ""
        var currentPath = ""
        var originalStrategyKnown = false
        var wasRunning = false
        var initialStatusKnown = false
        var stage = context.getString(R.string.selection_preparing)
        try {
            loadStrategies()
            if (autoApply && serviceType != ServiceType.nfqws) {
                throw IllegalStateException(context.getString(R.string.selection_nfqws_required))
            }
            if (serviceType != ServiceType.byedpi) checkRootService()
            originalPath = getActiveStrategy(prefs).getOrNull()?.manifestPath.orEmpty()
            currentPath = originalPath
            originalStrategyKnown = true
            val targets = probeTargets()
            if (targets.isEmpty() || strategyStates.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.selection_no_targets))
            }
            if (serviceType != ServiceType.byedpi) {
                stage = context.getString(R.string.selection_checking_service)
                wasRunning = rootRunning()
                initialStatusKnown = true
                if (wasRunning) rootCommand("stop")
            }
            stage = context.getString(R.string.selection_checking_baseline)
            val baseline = if (serviceType == ServiceType.byedpi) emptyList() else probe(buildHttpClient(), targets)
            val baselineCount = baseline.count { it.reached }
            val firstProblem = baseline.firstOrNull { !it.reached }?.problem.orEmpty()
            if (serviceType != ServiceType.byedpi) {
                diagnostic.value = context.getString(R.string.selection_baseline, baselineCount, targets.size) +
                    if (firstProblem.isEmpty()) "" else " · $firstProblem"
            }
            for (index in strategyStates.indices) {
                val current = strategyStates[index]
                stage = current.name
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
                        val score = countReachable(buildHttpClient(), index, targets)
                        strategyStates[index] = current.copy(progress = score, status = StrategyTestingStatus.Completed)
                    } finally {
                        context.startService(Intent(context, ByeDpiVpnService::class.java).apply { action = "STOP_VPN" })
                        delay(200L)
                    }
                } else {
                    try {
                        enableStrategy(current.path, prefs)
                        currentPath = current.path
                        if (getActiveStrategy(prefs).getOrNull()?.manifestPath != current.path) {
                            throw IllegalStateException("Could not save strategy: ${current.name}")
                        }
                        rootCommand("start")
                        waitForRootService()
                        val score = countReachable(buildHttpClient(), index, targets)
                        strategyStates[index] = current.copy(progress = score, status = StrategyTestingStatus.Completed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        strategyStates[index] = current.copy(
                            status = StrategyTestingStatus.Failed,
                            problem = describeError(stage, error).take(240)
                        )
                    } finally {
                        if (rootRunning()) rootCommand("stop")
                    }
                }
            }
            if (autoApply && serviceType != ServiceType.byedpi) {
                selected = strategyStates.filter {
                    it.status == StrategyTestingStatus.Completed && it.progress * targets.size > baselineCount
                }
                    .maxByOrNull { it.progress }
            }
            if (selected == null && autoApply) {
                diagnostic.value += "\n" + if (baselineCount == targets.size) {
                    context.getString(R.string.selection_baseline_reachable)
                } else {
                    context.getString(R.string.selection_no_improvement)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = describeError(stage, error)
            _errorFlow.value = message
            diagnostic.value = message
            strategyStates.indices.forEach { index ->
                val item = strategyStates[index]
                if (item.status == StrategyTestingStatus.Waiting || item.status == StrategyTestingStatus.Testing) {
                    strategyStates[index] = item.copy(
                        status = StrategyTestingStatus.Failed,
                        problem = context.getString(R.string.selection_skipped)
                    )
                }
            }
        } finally {
          withContext(NonCancellable) {
            // Restore the original configuration on failure, and the original running state
            // after a manual comparison. An automatically selected strategy starts immediately.
            val finalPath = selected?.path ?: originalPath
            try {
                if (serviceType != ServiceType.byedpi && initialStatusKnown && rootRunning()) {
                    rootCommand("stop")
                }
                if (originalStrategyKnown && currentPath != finalPath) {
                    if (finalPath.isEmpty()) disableStrategy(currentPath, prefs)
                    else enableStrategy(finalPath, prefs)
                }
                if (serviceType != ServiceType.byedpi && initialStatusKnown && (wasRunning || selected != null)) {
                    rootCommand("start")
                    waitForRootService()
                }
            } catch (error: Exception) {
                selected = null
                val message = describeError(context.getString(R.string.selection_restoring), error)
                _errorFlow.value = listOf(_errorFlow.value, message).filter { it.isNotBlank() }.joinToString("\n")
                diagnostic.value = _errorFlow.value
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
