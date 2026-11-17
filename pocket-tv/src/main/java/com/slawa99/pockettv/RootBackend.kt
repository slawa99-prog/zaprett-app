package com.slawa99.pockettv

import android.content.Context
import java.io.File
import java.util.UUID

interface PocketBackend {
    fun inspect(): ModuleInfo
    fun poll(): TestSnapshot
    fun command(command: String, argument: String = ""): String
    fun launch(profile: String): String
    fun log(): String
    fun updateStatus(): ModuleUpdateState = ModuleUpdateState()
    fun readState(includeModule: Boolean = true): BackendState {
        val update = updateStatus()
        return BackendState(if (includeModule && !update.installing) inspect() else null, poll(), update)
    }
    fun cancelTest(): TestSnapshot { command("cancel-test"); return poll() }
    fun installUpdate(file: File, release: ModuleRelease): String = error("Обновление недоступно")
    fun reboot(): String = error("Перезагрузка недоступна")
}

class RootBackend(private val context: Context) : PocketBackend {
    private val rootCommand = RootCommand()
    private val bridge by lazy {
        File(context.filesDir, "pocket_bridge.sh").also { target ->
            context.assets.open("pocket_bridge.sh").use { source ->
                target.outputStream().use { source.copyTo(it) }
            }
        }
    }
    override fun inspect(): ModuleInfo = PocketProtocol.module(run("inspect", timeout = 25))
    override fun poll(): TestSnapshot = PocketProtocol.test(run("poll", timeout = 25))
    override fun command(command: String, argument: String): String {
        require(command in setOf("start", "stop", "restart", "apply", "cancel-test"))
        if (command == "apply") require(PocketProtocol.safeName(argument))
        return run(command, *if (argument.isEmpty()) emptyArray() else arrayOf(argument), timeout = 300)
    }
    override fun launch(profile: String): String {
        require(profile in setOf("youtube", "all"))
        return run("launch-test", profile, UUID.randomUUID().toString(), timeout = 45, native = NativeRuntime.prepare(context))
    }
    override fun log(): String = run("log", timeout = 30)
    override fun updateStatus() = ModuleUpdateState.parse(run("update-status", timeout = 25))
    override fun readState(includeModule: Boolean) = PocketProtocol.state(run("state", if (includeModule) "full" else "quick", timeout = 25))
    override fun cancelTest() = PocketProtocol.test(run("cancel-test", timeout = 15))
    override fun installUpdate(file: File, release: ModuleRelease): String = run(
        "install-update", file.absolutePath, release.versionCode.toString(), release.sha256, timeout = 65)
    override fun reboot(): String = run("reboot-after-update", timeout = 15)

    private fun run(vararg args: String, timeout: Long, native: NativeInputs? = null): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Root operation on UI thread" }
        val environment = native?.let {
            "POCKET_TV_CURL_SOURCE=${PocketProtocol.shellQuote(it.curl.absolutePath)} " +
                "POCKET_TV_CA_SOURCE=${PocketProtocol.shellQuote(it.certificates.absolutePath)} "
        }.orEmpty()
        val cmd = environment + "/system/bin/sh ${PocketProtocol.shellQuote(bridge.absolutePath)} " + args.joinToString(" ") { PocketProtocol.shellQuote(it) }
        return rootCommand.run(cmd, args.firstOrNull().orEmpty(), timeout)
    }
}
