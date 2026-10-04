package com.slawa99.pockettv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val app get() = application as PocketApplication
    private val ink = Color.rgb(240, 245, 238)
    private val muted = Color.rgb(171, 193, 183)
    private val mint = Color.rgb(168, 231, 202)
    private val card = Color.rgb(28, 46, 40)
    private val bg = Color.rgb(16, 25, 24)
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var title: TextView
    private val nav = mutableListOf<Button>()
    private val controls = mutableMapOf<String, Button>()
    private var page = 0
    private var info: TextView? = null
    private var subtitle: TextView? = null
    private var progressTitle: TextView? = null
    private var progressCount: TextView? = null
    private var progress: ProgressBar? = null
    private var list: ListView? = null
    private var adapter: StrategyAdapter? = null
    private var journal: TextView? = null
    private val observer: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() { if (app.snapshot.active) app.poll(); app.main.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(bg)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val rail = vertical().apply { setPadding(0, 0, dp(18), 0) }
        rail.addView(label("POCKET", 25, mint, true))
        rail.addView(label("TV · 1.0 test1", 13, muted).apply { setPadding(0, 0, 0, dp(20)) })
        listOf("Главная", "Стратегии", "Подбор", "Журнал", "О приложении").forEachIndexed { index, name ->
            val button = button(name) { showPage(index) }.apply {
                tag = "nav_$index"; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(12), 0, dp(6), 0)
            }
            nav.add(button); rail.addView(button, rowParams())
        }
        rail.addView(label("↑ ↓  Перемещение\nOK  Выбрать\n←  Разделы", 12, muted).apply { setPadding(0, dp(22), 0, 0) })
        root.addView(rail, LinearLayout.LayoutParams(dp(172), -1))
        val body = vertical()
        title = label("Главная", 27, ink, true)
        body.addView(title)
        status = label("", 14, muted).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = focusBackground()
            setOnClickListener { if (app.error.isNotEmpty()) showError() }
        }
        body.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(10) })
        content = FrameLayout(this)
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        showPage(savedInstanceState?.getInt("page") ?: 0)
        nav[page].requestFocus()
    }
    override fun onResume() {
        super.onResume(); app.observe(observer); app.refresh()
        app.main.postDelayed(ticker, 3000)
    }
    override fun onPause() { app.remove(observer); app.main.removeCallbacks(ticker); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Int = 16, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun shape(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(10).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }
    private fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(39, 75, 60), mint))
        addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(47, 89, 69), mint))
        addState(intArrayOf(android.R.attr.state_enabled), shape(card))
        addState(intArrayOf(), shape(Color.rgb(24, 35, 31)))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        id = View.generateViewId(); text = value; textSize = 15f; isAllCaps = false
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.rgb(105, 126, 115), ink)))
        background = focusBackground(); minimumHeight = dp(46); minHeight = dp(46)
        setPadding(dp(14), dp(8), dp(14), dp(8)); isFocusable = true; isFocusableInTouchMode = true
        setOnClickListener { action() }
    }
    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun addControl(parent: LinearLayout, key: String, caption: String, action: () -> Unit): Button {
        val b = button(caption, action).apply { tag = key }
        controls[key] = b; parent.addView(b, rowParams()); return b
    }
    private fun scrolling(parent: ViewGroup, inner: View): ScrollView = ScrollView(this).apply {
        isFillViewport = false; clipToPadding = false; setPadding(0, 0, dp(5), dp(4))
        addView(inner, FrameLayout.LayoutParams(-1, -2)); parent.addView(this, ViewGroup.LayoutParams(-1, -1))
    }
    private fun showPage(index: Int) {
        page = index.coerceIn(0, 4)
        controls.clear(); content.removeAllViews(); info = null; subtitle = null
        progressTitle = null; progressCount = null; progress = null; list = null; adapter = null; journal = null
        val labels = listOf("Главная", "Стратегии", "Подбор", "Журнал", "О приложении")
        title.text = labels[page]
        nav.forEachIndexed { i, b -> b.text = if (i == page) "• ${labels[i]}" else labels[i] }
        when (page) {
            0 -> buildHome()
            1 -> buildStrategies(false)
            2 -> buildStrategies(true)
            3 -> buildJournal()
            else -> buildAbout()
        }
        render()
    }
    private fun buildHome() {
        val body = vertical()
        info = label("", 18).apply { setPadding(dp(16), dp(16), dp(16), dp(16)); background = shape(card) }
        body.addView(info, rowParams())
        addControl(body, "start", "▶  Запустить сервис") { app.control("start") }
        addControl(body, "stop", "■  Остановить сервис") { app.control("stop") }
        addControl(body, "restart", "↻  Перезапустить сервис") { app.control("restart") }
        addControl(body, "open_tests", "Подобрать стратегию из каталога Pocket") { showPage(2); controls["test_youtube"]?.requestFocus() }
        addControl(body, "refresh", "Обновить состояние / запросить root") { app.refresh() }
        addControl(body, "youtube", "Открыть YouTube") { openVideo(false) }
        addControl(body, "smarttube", "Открыть SmartTube") { openVideo(true) }
        body.addView(label("Автозапуск выполняет сам модуль Pocket при загрузке приставки, если он включён в Magisk. Выбранная стратегия сохраняется в модуле.", 14, muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        scrolling(content, body)
    }
    private fun buildStrategies(testPage: Boolean) {
        val body = vertical()
        if (testPage) {
            progressTitle = label("", 17, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            body.addView(progressTitle, rowParams())
            progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = ColorStateList.valueOf(mint); indeterminateTintList = ColorStateList.valueOf(mint)
            }
            body.addView(progress, LinearLayout.LayoutParams(-1, dp(7)).apply { bottomMargin = dp(8) })
            progressCount = label("", 14, muted)
            body.addView(progressCount, rowParams())
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("test_youtube" to "Подбор: YouTube", "test_all" to "Все сервисы", "cancel" to "Остановить").forEach { (key, caption) ->
                val b = button(caption) { if (key == "cancel") confirmStop() else confirmTest(if (key == "test_all") "all" else "youtube") }.apply { tag = key }
                controls[key] = b
                actions.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(7) })
            }
            body.addView(actions, rowParams())
        } else {
            val b = addControl(body, "open_tests", "Подбор по всему каталогу Pocket") { showPage(2); controls["test_youtube"]?.requestFocus() }
            b.nextFocusLeftId = nav[page].id
        }
        subtitle = label("", 14, muted).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        body.addView(subtitle, rowParams())
        val lv = ListView(this).apply {
            id = View.generateViewId(); tag = "strategy_list"
            divider = null; dividerHeight = dp(8); isFocusable = true
            setPadding(dp(3), dp(3), dp(3), dp(3)); clipToPadding = false
            selector = shape(Color.TRANSPARENT, mint); setDrawSelectorOnTop(true)
            nextFocusLeftId = nav[page].id
        }
        adapter = StrategyAdapter()
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, position, _ -> adapter?.rows?.getOrNull(position)?.let { showStrategy(it) } }
        // Explicit return to section navigation; ListView handles ↑/↓ and autoscroll.
        lv.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { nav[page].requestFocus(); true } else false
        }
        list = lv
        body.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(body, FrameLayout.LayoutParams(-1, -1))
    }
    private fun buildJournal() {
        val body = vertical()
        val buttons = LinearLayout(this)
        listOf("read_log" to "Обновить", "copy_log" to "Скопировать", "share_log" to "Поделиться").forEach { (key, caption) ->
            val b = button(caption) { when (key) { "read_log" -> app.readLog(); "copy_log" -> copyLog(); else -> shareLog() } }
            controls[key] = b
            buttons.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        }
        body.addView(buttons, rowParams())
        body.addView(label("Стрелки ↑ ↓ прокручивают журнал. Копирование показывает подтверждение; «Поделиться» открывает системное меню.", 13, muted), rowParams())
        journal = label("", 13, muted).apply { typeface = Typeface.MONOSPACE; setPadding(dp(10), dp(10), dp(10), dp(10)); background = shape(card) }
        val scroll = ScrollView(this).apply {
            tag = "log_scroll"; isFocusable = true; isFocusableInTouchMode = true; background = focusBackground()
            addView(journal, FrameLayout.LayoutParams(-1, -2)); nextFocusLeftId = nav[page].id
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(body, FrameLayout.LayoutParams(-1, -1))
    }
    private fun buildAbout() {
        val body = vertical()
        body.addView(label("Pocket TV — оболочка для Zapret Pocket от sevcator", 20, ink, true), rowParams())
        body.addView(label("Для первого запуска:\n1. Установите модуль Zapret Pocket через Magisk и перезагрузите приставку.\n2. Разрешите Pocket TV права суперпользователя.\n3. Откройте «Подбор» и запустите проверку каталога.\n4. Выберите результат: приложение сохранит стратегию и перезапустит Pocket.", 16), rowParams())
        body.addView(label("Каталог читается из установленного модуля. Отдельно скачивать стратегии не нужно. Совместимость команд проверена по исходникам Pocket v71; ранние версии без маркеров результатов потребуется обновить.\n\nПодбор может длиться несколько часов. Закрытие приложения его не останавливает; перезагрузка приставки прерывает проверку. Частичные результаты сохраняются. После проверки возвращается прежнее состояние сервиса.\n\nНе запускайте одновременно второй модуль Zapret или тестер в терминале/WebUI. При смене провайдера запустите новый подбор: сохранённая оценка относится к предыдущей проверке.\n\nРейтинг основан на ответах сетевых проб Pocket. Дополнительные проверки модуля доступны в журнале. Встроенного теста воспроизведения ролика здесь нет; проверьте выбранный вариант в YouTube или SmartTube.\n\nПриложение не является официальным клиентом sevcator. Оно не устанавливает и не обновляет root-модуль автоматически.", 14, muted), rowParams())
        addControl(body, "pocket_site", "Открыть страницу Zapret Pocket") { openUrl("https://github.com/sevcator/zapret-pocket/releases") }
        addControl(body, "source", "Исходники Pocket TV") { openUrl("https://github.com/slawa99-prog/zaprett-app/tree/feature/pocket-tv/pocket-tv") }
        scrolling(content, body)
    }
    private fun render() {
        if (!::content.isInitialized) return
        val restorationFailed = app.snapshot.restore == "failed" && app.module.service != "running"
        status.text = when {
            app.busy.isNotEmpty() -> app.busy
            app.error.isNotEmpty() -> "${app.error}\nOK — подробности"
            restorationFailed -> "Pocket не смог восстановить сервис. Откройте журнал и нажмите «Перезапустить сервис»."
            app.notice.isNotEmpty() -> app.notice
            app.connected -> "Pocket ${app.module.version}  ·  сервис ${app.serviceLabel(app.module.service)}"
            else -> "Для управления требуется Zapret Pocket и разрешение Magisk"
        }
        status.setTextColor(if (app.error.isNotEmpty() || restorationFailed) Color.rgb(255, 199, 164) else muted)
        status.isFocusable = app.error.isNotEmpty(); status.isClickable = app.error.isNotEmpty()
        val m = app.module; val s = app.snapshot
        val idle = app.busy.isEmpty()
        val canControl = idle && app.connected && m.error.isEmpty() && !s.active
        controls.forEach { (key, b) ->
            b.isEnabled = when (key) {
                "start", "stop", "restart" -> canControl
                "test_youtube", "test_all" -> canControl && m.compatible && m.strategies.isNotEmpty()
                "cancel" -> idle && s.active && s.status !in setOf("restoring", "cancelling")
                "refresh", "read_log" -> idle
                else -> true
            }
        }
        info?.text = if (app.connected) "Zapret Pocket ${m.version}\nСервис: ${app.serviceLabel(m.service)}\nВыбрана: ${m.selected.ifEmpty { "не задана" }}\nВ каталоге: ${m.strategies.size} стратегий" +
            if (s.active) "\nИдёт подбор: ${s.completed} из ${s.total}" else ""
            else "Zapret Pocket ещё не подключён\nНажмите «Обновить состояние» и разрешите root в Magisk. Если модуль не установлен, откройте раздел «О приложении»."
        progressTitle?.text = s.headline()
        progress?.apply {
            isIndeterminate = s.status in setOf("starting", "restoring", "cancelling")
            max = maxOf(1, s.total); progress = s.completed
        }
        progressCount?.text = if (s.id.isEmpty()) "Доступно ${m.strategies.size} стратегий. Проверяется весь каталог модуля."
            else "Обработано ${s.completed} из ${s.total}  ·  пропущено ${s.results.count { it.status == "SKIP" }}  ·  ${if (s.profile == "youtube") "YouTube" else "все сервисы"}  ·  ${formatTime(s.started)}"
        subtitle?.text = when {
            !app.connected && s.id.isEmpty() -> "Подключите модуль на главной странице."
            page == 2 && !m.compatible && app.connected -> "Для прогресса и результатов требуется новая версия Pocket. Обновите модуль (проверено с v71)."
            s.id.isEmpty() -> "Выберите стратегию или запустите подбор. Список загружается из установленного Pocket."
            else -> "Рейтинг сохранён до нового подбора. Баллы — ответы сетевых проб, воспроизведение видео не проверено. OK на строке — выбор и перезапуск."
        }
        updateRows()
        journal?.text = app.diagnosticText()
    }
    private fun formatTime(seconds: Long): String = if (seconds == 0L) "" else SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
    private data class Row(val name: String, val result: ProbeResult? = null)
    private fun updateRows() {
        val adapter = adapter ?: return
        val lv = list ?: return
        val ranked = app.snapshot.ranked
        val checked = ranked.map { it.name }.toSet()
        val rows = ranked.map { Row(it.name, it) } + app.module.strategies.filter { it !in checked }.map { Row(it) }
        if (adapter.rows == rows && adapter.selected == app.module.selected && adapter.current == app.snapshot.current && adapter.running == app.snapshot.active) return
        val focusedName = if (lv.hasFocus()) adapter.rows.getOrNull(lv.selectedItemPosition)?.name else null
        adapter.rows = rows; adapter.selected = app.module.selected; adapter.current = app.snapshot.current; adapter.running = app.snapshot.active
        adapter.notifyDataSetChanged()
        if (focusedName != null) {
            val pos = rows.indexOfFirst { it.name == focusedName }
            if (pos >= 0) lv.setSelection(pos)
        }
    }
    private inner class StrategyAdapter : BaseAdapter() {
        var rows = emptyList<Row>(); var selected = ""; var current = ""; var running = false
        private val identities = mutableMapOf<String, Long>()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = identities.getOrPut(rows[position].name) { identities.size.toLong() }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val box = (convertView as? LinearLayout) ?: vertical().apply {
                setPadding(dp(15), dp(13), dp(15), dp(13)); background = shape(card)
                addView(label("", 17, ink, true))
                addView(label("", 13, muted).apply { setPadding(0, dp(7), 0, 0) })
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                isFocusable = false; minimumHeight = dp(76)
            }
            val row = rows[position]
            val marker = if (row.name == selected) "  ·  ВЫБРАНА" else ""
            (box.getChildAt(0) as TextView).text = row.name + marker
            (box.getChildAt(1) as TextView).text = row.result?.description() ?: when {
                running && row.name == current -> "Проверяется сейчас…"
                running -> "Ожидает проверки"
                else -> "Не проверена в последнем подборе"
            }
            return box
        }
    }
    private fun showStrategy(row: Row) {
        val available = row.name in app.module.strategies
        val message = buildString {
            append(row.result?.description() ?: "Стратегия не проверена в последнем подборе.")
            append("\n\nПосле выбора Pocket сохранит стратегию и перезапустит сервис. Затем откройте YouTube или SmartTube для проверки видео.")
            if (!available) append("\n\nВ текущем каталоге этой стратегии нет. Результат сохранён от предыдущей версии модуля.")
            if (app.snapshot.active) append("\n\nСначала остановите подбор и дождитесь восстановления сервиса.")
        }
        val dialog = AlertDialog.Builder(this).setTitle(row.name).setMessage(message)
            .setNegativeButton("Закрыть", null)
        if (available && !app.snapshot.active && app.busy.isEmpty()) {
            dialog.setPositiveButton("Выбрать и перезапустить") { _, _ -> app.control("apply", row.name) }
        }
        dialog.show()
    }
    private fun confirmTest(profile: String) {
        val scope = if (profile == "youtube") "адреса YouTube" else "стандартные адреса разных сервисов Pocket"
        AlertDialog.Builder(this).setTitle("Проверить ${app.module.strategies.size} стратегий?")
            .setMessage("Тестер модуля проверит $scope и выполнит свои дополнительные пробы. Это может занять несколько часов. Во время подбора интернет может прерываться.\n\nПриложение можно закрыть. Приставку не перезагружайте. После окончания восстановится прежнее состояние сервиса; результаты нового подбора заменят прежние.")
            .setNegativeButton("Отмена", null).setPositiveButton("Начать подбор") { _, _ -> app.launch(profile) }.show()
    }
    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("Остановить подбор?")
            .setMessage("Готовые результаты сохранятся. Pocket завершит текущие проверки и восстановит прежнее состояние сервиса.")
            .setNegativeButton("Продолжить проверку", null).setPositiveButton("Остановить") { _, _ -> app.control("cancel-test") }.show()
    }
    private fun showError() {
        AlertDialog.Builder(this).setTitle("Ошибка").setMessage(app.error)
            .setPositiveButton("Закрыть", null).setNegativeButton("Скопировать журнал") { _, _ -> copyLog() }.show()
    }
    private fun copyLog() {
        try {
            val full = app.diagnosticText()
            val text = if (full.length > 160000) full.take(2000) + "\n[Средняя часть журнала сокращена]\n" + full.takeLast(158000) else full
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pocket TV log", text))
            Toast.makeText(this, "Журнал скопирован в буфер обмена", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "Копирование не удалось: ${e.message}", Toast.LENGTH_LONG).show() }
    }
    private fun shareLog() {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, app.diagnosticText().takeLast(180000)), "Отправить журнал Pocket TV")) }
        catch (_: Exception) { Toast.makeText(this, "На приставке нет приложения для отправки. Используйте «Скопировать».", Toast.LENGTH_LONG).show() }
    }
    private fun openVideo(smart: Boolean) {
        val names = if (smart) listOf("com.teamsmart.videomanager.tv", "com.teamsmart.videomanager.tv.beta")
            else listOf("com.google.android.youtube.tv", "com.google.android.youtube")
        val intent = names.firstNotNullOfOrNull { packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) Toast.makeText(this, "${if (smart) "SmartTube" else "YouTube"} не найден. Откройте его из меню приставки.", Toast.LENGTH_LONG).show()
        else try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }
    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { AlertDialog.Builder(this).setTitle("Откройте ссылку на телефоне или компьютере").setMessage(url).setPositiveButton("Закрыть", null).show() }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !nav.contains(currentFocus)) {
            nav[page].requestFocus(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    @Deprecated("TV back navigation")
    override fun onBackPressed() {
        when {
            !nav.contains(currentFocus) -> nav[page].requestFocus()
            page != 0 -> { showPage(0); nav[0].requestFocus() }
            else -> super.onBackPressed()
        }
    }
}
