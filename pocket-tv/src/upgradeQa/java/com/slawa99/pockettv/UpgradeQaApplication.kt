package com.slawa99.pockettv

import java.io.File

// Only present in upgradeQa. Provide the Magisk boundary for an unattended APK
// replacement on AOSP. Receiver, job, preferences and process lifecycle are real.
class UpgradeQaApplication : PocketApplication() {
    override fun onCreate() {
        backendFactory = { ctx ->
            object : PocketBackend by RootBackend(ctx) {
                override fun ensureAutomaticFastStart(): FastStartResult {
                    val mode = File(ctx.filesDir, "qa-mode")
                    return when (mode.readText().trim()) {
                        "on" -> FastStartResult.ALREADY_ON
                        "off" -> { mode.writeText("on"); FastStartResult.APPLIED }
                        else -> error("Unknown fixture")
                    }
                }
            }
        }
        super.onCreate()
    }
}
