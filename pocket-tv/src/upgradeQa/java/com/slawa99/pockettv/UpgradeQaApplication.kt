package com.slawa99.pockettv

import java.io.File

// Only present in upgradeQa. Provide the Magisk boundary for an unattended APK
// replacement on AOSP. Receiver, job, preferences and process lifecycle are real.
class UpgradeQaApplication : PocketApplication() {
    override fun videoProviderAsset() = if(File(filesDir,"qa-real-player").exists()) super.videoProviderAsset() else "qa_video_provider.js"
    override fun onCreate() {
        backendFactory = { ctx ->
            if (File(ctx.filesDir,"qa-video-fixture").exists()) QaVideoBackend(ctx.filesDir)
            else object : PocketBackend by RootBackend(ctx) {
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

// Test boundaries for the native module, confined to this non-delivery build type.
private class QaVideoBackend(private val dir: File) : PocketBackend {
    private val names = listOf("A-network-first","B-slow","C-fast","D","E","F-untested","G-untested")
    private val selected=File(dir,"qa-selection")
    private val live=File(dir,"qa-live")
    private val sid=File(dir,"qa-session")
    private fun trace(s:String){File(dir,"qa-events").appendText(s+"\n")}
    override fun inspect()=ModuleInfo("v72",if(live.exists() && live.readText()=="off") "stopped" else "running",
        if(selected.exists()) selected.readText() else "BASE",names+"BASE",true,bootEnabled=true,versionCode=72,catalogId="qa-video",fastStart="on")
    override fun poll()=if(!sid.exists()) TestSnapshot() else TestSnapshot(sid.readText(),"completed",names.size,profile="youtube",
        results=names.mapIndexed { i,n -> ProbeResult(n,"OK",10-i,10,0,0,0,0) },restore="ok",catalogId="qa-video")
    override fun launchWithId(profile:String,id:String):String {sid.writeText(id);trace("network");return "@@ID $id"}
    override fun launch(profile:String)=error("use explicit session")
    override fun ensureAutomaticFastStart()=FastStartResult.ALREADY_ON
    override fun command(command:String,argument:String)=""
    override fun log()=""
    override fun videoTransition(action:String,session:String,catalog:String,original:String,target:String):String {
        check(session==sid.readText() && catalog=="qa-video")
        trace("$action $target")
        return when(action){
            "try" -> if(target in (when { File(dir,"qa-real-player").exists() -> listOf("B-slow"); File(dir,"qa-second-group").exists() -> listOf("F-untested"); else -> listOf("B-slow","C-fast") })) { live.writeText(target); "ready" } else "no_service"
            "commit" -> {selected.writeText(target);live.writeText(target);"committed"}
            else -> {selected.writeText(original);live.writeText(if(action=="restore-off") "off" else original);"restored"}
        }
    }
}
