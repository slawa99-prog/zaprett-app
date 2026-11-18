package com.slawa99.pockettv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import java.util.concurrent.atomic.AtomicBoolean

enum class FastStartResult(val wire: String, val label: String) {
    APPLIED("applied", "включён"), ALREADY_ON("already_on", "уже включён"),
    DEFERRED("deferred", "модуль занят"), UNSUPPORTED("unsupported", "неподдерживаемый модуль"),
    UNAVAILABLE("unavailable", "нет активного модуля"), FAILED("failed", "не применён"),
    OPTED_OUT("opted_out", "выбран обычный запуск")
}

class PocketReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) FastStartJob.schedule(context, replace = true)
    }
}

class FastStartJob : JobService() {
    companion object {
        private const val ID = 0x535650
        const val MAX_ATTEMPTS = 8
        fun schedule(context: Context, replace: Boolean = false) {
            val app = context.applicationContext as PocketApplication
            if (!app.automaticFastStart) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!replace && scheduler.getPendingJob(ID) != null) return
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val extras = PersistableBundle().apply { putLong("stamp", stamp) }
            val job = JobInfo.Builder(ID, ComponentName(context, FastStartJob::class.java))
                .setExtras(extras).setPersisted(true).setOverrideDeadline(0)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            scheduler.schedule(job) // Foreground reconciliation also works if the OS defers this.
        }
    }
    private var current: AtomicBoolean? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as PocketApplication
        if (!app.automaticFastStart) return false
        val active = AtomicBoolean(true)
        current = active
        app.afterReplacement(params.extras.getLong("stamp"), active) { retry ->
            if (active.compareAndSet(true, false)) { current = null; jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        current?.set(false); current = null
        return (application as PocketApplication).automaticFastStart
    }
}
