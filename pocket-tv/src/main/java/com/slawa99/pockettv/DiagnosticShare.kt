package com.slawa99.pockettv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

class DiagnosticFileProvider : FileProvider()

internal object DiagnosticShare {
    data class Target(val component: ComponentName, val label: String)
    private fun send() = Intent(Intent.ACTION_SEND).setType("text/plain")

    @Suppress("DEPRECATION")
    fun targets(context: Context): List<Target> {
        val pm = context.packageManager
        return pm.queryIntentActivities(send(), PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { r ->
            val a = r.activityInfo ?: return@mapNotNull null
            if (!a.exported || !a.enabled || a.packageName == context.packageName ||
                (a.permission != null && context.checkSelfPermission(a.permission) != PackageManager.PERMISSION_GRANTED)) return@mapNotNull null
            Target(ComponentName(a.packageName, a.name), r.loadLabel(pm).toString())
        }.distinctBy { it.component }.sortedBy { it.label }
    }

    // Some TV firmware ships a chooser that closes as soon as it is opened.
    // Offer only installed activities and start one only after a user selects it.
    fun choose(activity: Activity, targets: List<Target>, copy: () -> Unit, open: (Target) -> Unit,
               unavailable: String = "На приставке нет приложения для отправки журнала."): AlertDialog {
        val builder = AlertDialog.Builder(activity).setTitle("Отправить журнал SV Pocket")
            .setNegativeButton("Закрыть", null).setNeutralButton("Скопировать") { _, _ -> copy() }
        if (targets.isEmpty()) builder.setMessage(unavailable)
        else builder.setItems(targets.map { it.label }.toTypedArray()) { _, i -> open(targets[i]) }
        return builder.show()
    }

    // The report itself never travels through Binder. Issue a separate, read-only
    // grant for an immutable snapshot, so sharing again cannot replace a pending attachment.
    fun attachment(context: Context, target: Target, report: String): Intent {
        val dir = File(context.cacheDir, "diagnostic-exports")
        check(dir.isDirectory || dir.mkdirs()) { "Не удалось создать каталог журнала." }
        val expiry = System.currentTimeMillis() - 86_400_000L
        dir.listFiles()?.filter { it.name.startsWith("SV-Pocket-") && it.lastModified() < expiry }?.forEach { it.delete() }
        val file = File.createTempFile("SV-Pocket-", ".txt", dir)
        try {
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".diagnostics", file)
            return send().setComponent(target.component).putExtra(Intent.EXTRA_SUBJECT, "Журнал SV Pocket")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("Журнал SV Pocket", uri) }
        } catch (e: Exception) { file.delete(); throw e }
    }
}
