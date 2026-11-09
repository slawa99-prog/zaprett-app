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
    private val muted = Color.rgb(161, 183, 192)
    private val mint = Color.rgb(79, 224, 228)
    private val card = Color.rgb(24, 40, 49)
    private val bg = Color.rgb(11, 20, 26)
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var title: TextView
    private val nav = mutableListOf<Button>()
    private val controls = mutableMapOf<String, Button>()
    private var page = 0
    private var pendingUpdateVersion = 0
    private val pageNames = listOf("Главная", "Стратегии", "Подбор", "Журнал", "О приложении", "Помощь", "Настройки", "Обновление обхода", "Расширенные настройки")
    private val backStack = mutableListOf<Int>()
    private val focusByPage = mutableMapOf<Int, String>()
    private val selectionByPage = mutableMapOf<Int, Int>()
    private fun navigationButton() = nav[when (page) { 0 -> 0; 5 -> 1; else -> 2 }]
    private fun rememberPosition() {
        (currentFocus?.tag as? String)?.let { focusByPage[page] = it }
        list?.let { selectionByPage[page] = it.selectedItemPosition.coerceAtLeast(0) }
    }
    private fun focusContent() {
        val remembered = focusByPage[page]?.let { window.decorView.findViewWithTag<View>(it) }
        if (remembered != null && remembered.visibility == View.VISIBLE && remembered.isEnabled && remembered.requestFocus()) return
        if (list != null) { list?.requestFocus(); list?.setSelection(selectionByPage[page] ?: 0); return }
        controls.values.firstOrNull { it.visibility == View.VISIBLE && it.isEnabled }?.requestFocus()
            ?: window.decorView.findViewWithTag<View>("about_scroll")?.requestFocus()
    }
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
        override fun run() { if (app.snapshot.active || app.update.installing) app.poll(); app.main.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV has no software navigation bar. Immersive mode can put Android's
        // first-run confirmation above the app and intercept remote input.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(bg)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val rail = vertical().apply { setPadding(0, 0, dp(18), 0) }
        rail.addView(brandLogo().apply { tag = "app_name" }, LinearLayout.LayoutParams(-1, dp(30)))
        rail.addView(label(getString(R.string.app_tagline), 12, muted).apply { setPadding(0, dp(5), 0, dp(20)) })
        listOf(0 to "Главная", 5 to "Помощь", 6 to "Настройки").forEach { (index, name) ->
            val button = button(name) { backStack.clear(); showPage(index, remember = false) }.apply {
                tag = "nav_$index"; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(12), 0, dp(6), 0)
            }
            nav.add(button); rail.addView(button, rowParams())
        }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(label("↑ ↓  Перемещение\nOK  Выбрать\n←  Разделы", 12, muted).apply { setPadding(0, dp(14), 0, 0) })
        rail.addView(label(getString(R.string.app_version, BuildConfig.VERSION_NAME), 11, muted).apply {
            tag = "app_version"; setPadding(0, dp(14), 0, 0)
        })
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
        savedInstanceState?.getIntArray("back_stack")?.let { backStack.addAll(it.toList()) }
        showPage(if (intent.getBooleanExtra("updates", false)) 7 else savedInstanceState?.getInt("page") ?: 0, remember = false)
        navigationButton().requestFocus()
    }
    override fun onResume() {
        super.onResume(); app.observe(observer); app.refresh()
        app.checkUpdates(automatic = true)
        app.main.postDelayed(ticker, 3000)
    }
    override fun onPause() { app.remove(observer); app.main.removeCallbacks(ticker); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); outState.putIntArray("back_stack", backStack.toIntArray()); super.onSaveInstanceState(outState) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun brandLogo() = ImageView(this).apply {
        setImageResource(R.drawable.sv_pocket_wordmark)
        contentDescription = getString(R.string.app_name)
        scaleType = ImageView.ScaleType.FIT_START
        adjustViewBounds = true
        isFocusable = false
    }
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
        addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(32, 69, 82), mint))
        addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(36, 83, 93), mint))
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
    private fun showPage(index: Int, remember: Boolean = true) {
        if (remember && index != page) { rememberPosition(); backStack.add(page) }
        page = index.coerceIn(0, 8)
        controls.clear(); content.removeAllViews(); info = null; subtitle = null
        progressTitle = null; progressCount = null; progress = null; list = null; adapter = null; journal = null
        title.text = pageNames[page]
        nav.forEach { b ->
            val i = b.tag.toString().substringAfter('_').toInt()
            b.text = if (b == navigationButton()) "• ${pageNames[i]}" else pageNames[i]
            b.nextFocusRightId = View.NO_ID
        }
        when (page) {
            0 -> buildHome()
            1 -> buildStrategies(false)
            2 -> buildStrategies(true)
            3 -> buildJournal()
            4 -> buildAbout()
            5 -> buildHelp()
            6 -> buildSettings()
            7 -> buildUpdates()
            else -> buildAdvanced()
        }
        render()
        val first = controls.values.firstOrNull { it.visibility == View.VISIBLE && it.isEnabled }
        navigationButton().nextFocusRightId = first?.id ?: list?.id ?: View.NO_ID
        list?.setSelection(selectionByPage[page] ?: 0)
    }
    private fun buildHome() {
        val body = vertical()
        val hero = vertical().apply {
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(25, 56, 66), card)).apply { cornerRadius = dp(14).toFloat() }
        }
        info = label("", 26, ink, true); hero.addView(info)
        subtitle = label("", 15, muted).apply { setPadding(0, dp(10), 0, 0) }; hero.addView(subtitle)
        body.addView(hero, rowParams().apply { bottomMargin = dp(18) })
        addControl(body, "home_primary", "Открыть YouTube") {
            when {
                app.update.blocksChanges -> { showPage(7); focusContent() }
                app.snapshot.active -> { showPage(2); focusContent() }
                !app.connected -> { showPage(5); focusContent(); app.diagnose() }
                app.module.service != "running" -> app.control("start")
                else -> openVideo(false)
            }
        }.apply { textSize = 20f; minimumHeight = dp(62) }
        addControl(body, "home_help", "YouTube не работает") { showPage(5); focusContent(); app.diagnose() }
        addControl(body, "home_smarttube", "Открыть SmartTube") { openVideo(true) }
        addControl(body, "home_test", "Посмотреть ход подбора") { showPage(2); focusContent() }
        addControl(body, "home_update", "Доступно обновление обхода") { showPage(7); focusContent() }
        body.addView(label("После настройки обход запускается вместе с приставкой. Включать его каждый раз не нужно, если модуль включён в Magisk.", 13, muted).apply { setPadding(0, dp(12), 0, 0) })
        wireVerticalButtons(body); scrolling(content, body)
    }
    private fun buildHelp() {
        val body = vertical()
        info = label("", 23, ink, true); body.addView(info, rowParams())
        subtitle = label("", 16, muted); body.addView(subtitle, rowParams().apply { bottomMargin = dp(18) })
        addControl(body, "diagnose", "Проверить подключение и обход") { app.diagnose() }
        addControl(body, "help_home", "Готово — на главную") { backStack.clear(); showPage(0, remember = false); focusContent() }
        addControl(body, "help_repair", "Перезапустить обход") { app.control(if (app.module.service == "running") "restart" else "start", helpAfter = true) }
        addControl(body, "help_youtube", "Открыть YouTube и проверить видео") { openVideo(false, feedback = true) }
        addControl(body, "help_smarttube", "Проверить в SmartTube") { openVideo(true, feedback = true) }
        addControl(body, "video_yes", "Видео работает — сохранить стратегию") { app.videoWorks() }
        addControl(body, "video_no", "Проблема осталась") { app.videoFailed() }
        addControl(body, "next_strategy", "Попробовать следующую стратегию") {
            app.nextStrategy?.let { confirmApply(it) }
        }
        addControl(body, "restore_strategy", "Вернуть последнюю рабочую стратегию") { confirmApply(app.confirmedName) }
        addControl(body, "help_tests", "Открыть полный подбор стратегий") { showPage(2); focusContent() }
        addControl(body, "help_update", "Завершить обновление") { showPage(7); focusContent() }
        addControl(body, "help_setup", "Как настроить первый запуск") { showPage(4); focusContent() }
        wireVerticalButtons(body); scrolling(content, body)
    }
    private fun buildSettings() {
        val body = vertical()
        body.addView(label("Обновления и дополнительные возможности", 16, muted), rowParams())
        addControl(body, "open_updates", "Обновление обхода YouTube") { showPage(7); focusContent() }
        addControl(body, "auto_update_check", "") { app.automaticUpdateCheck = !app.automaticUpdateCheck }
        addControl(body, "open_advanced", "Расширенные настройки") { showPage(8); focusContent() }
        addControl(body, "open_about", "О приложении и первый запуск") { showPage(4); focusContent() }
        wireVerticalButtons(body); scrolling(content, body)
    }
    private fun buildAdvanced() {
        val body = vertical()
        body.addView(label("Оригинальные названия стратегий и подробные результаты", 15, muted), rowParams())
        addControl(body, "open_strategies", "Стратегии") { showPage(1); focusContent() }
        addControl(body, "open_tests", "Подбор стратегий") { showPage(2); focusContent() }
        addControl(body, "open_log", "Журнал и диагностика") { showPage(3); focusContent(); app.readLog() }
        addControl(body, "start", "Включить обход") { app.control("start") }
        addControl(body, "stop", "Выключить обход") { app.control("stop") }
        addControl(body, "restart", "Перезапустить обход") { app.control("restart") }
        addControl(body, "refresh", "Проверить состояние / запросить доступ") { app.refresh() }
        wireVerticalButtons(body); scrolling(content, body)
    }
    private fun buildUpdates() {
        val body = vertical()
        info = label("", 23, ink, true); body.addView(info, rowParams())
        subtitle = label("", 15, muted); body.addView(subtitle, rowParams())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(mint); indeterminateTintList = ColorStateList.valueOf(mint)
        }
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { bottomMargin = dp(10) })
        progressCount = label("", 14, muted); body.addView(progressCount, rowParams())
        addControl(body, "check_update", "Проверить обновления") { app.checkUpdates() }
        addControl(body, "install_update", "Обновить обход") { confirmUpdate() }
        addControl(body, "reboot_update", "Перезагрузить сейчас") {
            AlertDialog.Builder(this).setTitle("Перезагрузить приставку?").setMessage("Просмотр видео и другие приложения закроются. Обновление завершится после запуска приставки.")
                .setNegativeButton("Позже", null).setPositiveButton("Перезагрузить") { _, _ -> app.rebootAfterUpdate() }.show()
        }
        addControl(body, "update_later", "Позже — вернуться на главную") { backStack.clear(); showPage(0, remember = false); focusContent() }
        addControl(body, "update_details", "Подробности установки") { showPage(3); focusContent(); app.readLog() }
        body.addView(label("SV Pocket проверяет каталог при открытии, не чаще раза в сутки. Установка начинается после вашего подтверждения. После обновления проверьте видео; при необходимости повторите подбор.", 13, muted).apply { setPadding(0, dp(12), 0, 0) })
        wireVerticalButtons(body); scrolling(content, body)
    }
    private fun confirmUpdate() {
        val r = app.release ?: return
        AlertDialog.Builder(this).setTitle("Обновить обход до ${r.version}?")
            .setMessage("Текущая версия: ${app.module.version}.\n\nПриложение скачает модуль, сохранит резервную копию и установит обновление. Не отключайте питание во время установки. Затем потребуется перезагрузка.")
            .setNegativeButton("Отмена", null).setPositiveButton("Обновить") { _, _ ->
                pendingUpdateVersion = r.versionCode
                if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 72)
                else app.startModuleUpdate(r.versionCode)
            }.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 72 && pendingUpdateVersion > 0) app.startModuleUpdate(pendingUpdateVersion)
    }
    private fun confirmApply(name: String) {
        AlertDialog.Builder(this).setTitle(name).setMessage("Включить эту стратегию и перезапустить обход? Затем проверьте видео.")
            .setNegativeButton("Отмена", null).setPositiveButton("Попробовать") { _, _ -> app.control("apply", name, helpAfter = true) }.show()
    }
    private fun wireVerticalButtons(parent: LinearLayout) {
        val buttons = (0 until parent.childCount).mapNotNull { parent.getChildAt(it) as? Button }
        buttons.forEachIndexed { index, button ->
            button.nextFocusLeftId = navigationButton().id
            button.setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_DPAD_DOWN && key != KeyEvent.KEYCODE_DPAD_UP) false
                else {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                        var target = index + step
                        while (target in buttons.indices && (!buttons[target].isEnabled || buttons[target].visibility != View.VISIBLE)) target += step
                        if (target in buttons.indices) buttons[target].requestFocus()
                        else if (target < 0 && status.isFocusable && status.visibility == View.VISIBLE) status.requestFocus()
                    }
                    true
                }
            }
        }
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
            b.nextFocusLeftId = navigationButton().id
        }
        subtitle = label("", 14, muted).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        body.addView(subtitle, rowParams())
        val lv = ListView(this).apply {
            id = View.generateViewId(); tag = "strategy_list"
            divider = null; dividerHeight = dp(8); isFocusable = true
            setPadding(dp(3), dp(3), dp(3), dp(3)); clipToPadding = false
            selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(Color.TRANSPARENT, mint))
                addState(intArrayOf(android.R.attr.state_pressed), shape(Color.TRANSPARENT, mint))
                addState(intArrayOf(), shape(Color.TRANSPARENT))
            }
            setDrawSelectorOnTop(true)
            nextFocusLeftId = navigationButton().id
        }
        adapter = StrategyAdapter()
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, position, _ -> adapter?.rows?.getOrNull(position)?.let { showStrategy(it) } }
        // Explicit return to section navigation; ListView handles ↑/↓ and autoscroll.
        lv.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { navigationButton().requestFocus(); true } else false
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
            addView(journal, FrameLayout.LayoutParams(-1, -2)); nextFocusLeftId = navigationButton().id
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(body, FrameLayout.LayoutParams(-1, -1))
    }
    private fun buildAbout() {
        val body = vertical()
        body.addView(brandLogo(), LinearLayout.LayoutParams(dp(210), dp(38)).apply { bottomMargin = dp(8) })
        body.addView(label("Приложение от мастера Святослава для удобной настройки обхода на Android TV.", 16, mint), rowParams())
        body.addView(label("Для первого запуска:\n1. Установите модуль Zapret Pocket через Magisk и перезагрузите приставку.\n2. Разрешите SV Pocket права суперпользователя.\n3. Откройте «Настройки → Расширенные настройки → Подбор» и запустите проверку каталога.\n4. Выберите результат: приложение сохранит стратегию и перезапустит сервис.", 16), rowParams())
        body.addView(label("Каталог читается из установленного модуля. Отдельно скачивать стратегии не нужно. Команды сверены с исходниками Pocket v71 и v72; ранние версии без маркеров результатов потребуется обновить.\n\nПодбор может длиться несколько часов. Закрытие приложения его не останавливает; перезагрузка приставки прерывает проверку. Частичные результаты сохраняются. После проверки возвращается прежнее состояние сервиса.\n\nНе запускайте одновременно второй модуль Zapret или тестер в терминале/WebUI. При смене провайдера запустите новый подбор: сохранённая оценка относится к предыдущей проверке.\n\nРейтинг основан на ответах сетевых проб Pocket. Дополнительные проверки модуля доступны в журнале. Встроенного теста воспроизведения ролика здесь нет; проверьте выбранный вариант в YouTube или SmartTube.\n\nОбновить установленный модуль можно в разделе «Настройки → Обновление обхода». Установка начинается после подтверждения и завершается перезагрузкой. Приложение не является официальным клиентом sevcator.", 14, muted), rowParams())
        scrolling(content, body).apply {
            tag = "about_scroll"; isFocusable = true; isFocusableInTouchMode = true
            nextFocusLeftId = navigationButton().id
        }
    }
    private fun render() {
        if (!::content.isInitialized) return
        val m = app.module; val s = app.snapshot; val u = app.update; val r = app.release
        val idle = app.busy.isEmpty() && !app.updateRequested
        val canControl = app.changesAllowed && m.error.isEmpty()
        val awaiting = app.awaitingVideo
        status.text = when {
            app.busy.isNotEmpty() -> app.busy
            app.error.isNotEmpty() -> "Не удалось выполнить действие. OK — подробности"
            u.installing -> "Обновление устанавливается. Не отключайте питание приставки."
            u.pending && page != 7 -> "Обновление готово. Завершите его в разделе «Обновление обхода»."
            s.restore == "failed" && m.service != "running" -> "Обход не восстановился после подбора. Откройте помощь."
            else -> app.notice
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        status.setTextColor(if (app.error.isNotEmpty()) Color.rgb(255, 199, 164) else muted)
        status.isFocusable = app.error.isNotEmpty(); status.isClickable = app.error.isNotEmpty()
        fun visible(key: String, value: Boolean) { controls[key]?.visibility = if (value) View.VISIBLE else View.GONE }
        controls.forEach { (key, b) ->
            b.isEnabled = when (key) {
                "start", "stop", "restart", "help_repair" -> canControl
                "test_youtube", "test_all" -> canControl && m.compatible && m.strategies.isNotEmpty()
                "cancel" -> idle && s.active && s.status !in setOf("restoring", "cancelling")
                "refresh", "read_log", "diagnose" -> idle && !u.installing
                "video_yes" -> canControl && m.service == "running" && m.selected.isNotEmpty()
                "video_no" -> idle
                "next_strategy" -> canControl && app.nextStrategy != null
                "restore_strategy" -> canControl && app.canRestore
                "check_update" -> !app.checkingUpdates && !app.updateRequested && !u.installing
                "install_update" -> canControl && r != null && r.versionCode > m.versionCode && r.minAppVersion <= BuildConfig.VERSION_CODE
                "reboot_update" -> idle && u.pending && !s.active
                "home_primary", "home_help" -> idle
                else -> true
            }
        }
        controls["auto_update_check"]?.text = "Проверять обновления при открытии: " + if (app.automaticUpdateCheck) "включено" else "выключено"
        if (page == 0) {
            info?.text = when {
                u.pending -> "Обновление готово"
                u.installing || app.updateRequested -> "Обновляем обход"
                s.active -> "Идёт подбор стратегий"
                !app.connected && !idle -> "Проверяем обход…"
                !app.connected -> "Нужно настроить обход"
                m.service == "running" -> "Обход включён"
                m.service == "stopped" -> "Обход выключен"
                else -> "Обходу нужна помощь"
            }
            subtitle?.text = when {
                u.pending -> "Перезагрузите приставку, чтобы завершить установку."
                s.active -> "Обработано ${s.completed} из ${s.total}. Можно открыть ход проверки."
                !app.connected -> "Нажмите «Настроить обход» — приложение подскажет следующий шаг."
                else -> "Стратегия: ${m.selected.ifEmpty { "не выбрана" }}\n" +
                    if (app.selectedConfirmed) "Вы подтвердили видео ${formatTime(app.confirmedAt / 1000)}. При смене сети проверьте снова."
                    else "Воспроизведение видео ещё не подтверждено."
            }
            controls["home_primary"]?.text = when {
                u.blocksChanges -> "Завершить обновление"
                s.active -> "Посмотреть ход подбора"
                !app.connected -> "Настроить обход"
                m.service != "running" -> "Включить обход"
                else -> "Открыть YouTube"
            }
            visible("home_test", false)
            visible("home_update", u.pending || r != null && r.versionCode > m.versionCode && app.connected)
        }
        if (page == 5) {
            info?.text = if (awaiting) "Удалось посмотреть видео?" else app.help.title
            subtitle?.text = (if (awaiting) "Выберите результат проверки. Он сохранится отдельно от сетевых баллов." else app.help.detail) +
                if (app.connected && m.selected.isNotEmpty()) "\nСтратегия: ${m.selected}" else ""
            controls["diagnose"]?.text = if (app.help.offline) "Проверить ещё раз" else "Проверить подключение и обход"
            controls["help_repair"]?.text = if (m.service == "running") "Перезапустить обход" else "Включить обход"
            val ready = (app.help.ready || awaiting) && app.connected && !s.active && !u.blocksChanges
            visible("diagnose", !ready)
            visible("help_home", app.help.phase == "done" && !awaiting)
            visible("help_repair", ready && !awaiting && app.help.phase == "repair")
            visible("help_youtube", ready)
            visible("help_smarttube", ready)
            visible("video_yes", awaiting && ready)
            visible("video_no", awaiting && ready)
            visible("next_strategy", ready && app.helpAlternatives)
            visible("restore_strategy", ready && app.helpAlternatives && app.canRestore)
            visible("help_tests", s.active || ready && app.helpAlternatives)
            visible("help_update", u.blocksChanges)
            visible("help_setup", !app.connected && !app.help.offline)
            controls["next_strategy"]?.text = app.nextStrategy?.let { "Попробовать: $it" } ?: "Других стратегий нет — откройте подбор"
            controls["restore_strategy"]?.text = "Вернуть рабочую: ${app.confirmedName}"
        }
        if (page == 1 || page == 2) {
            progressTitle?.text = s.headline()
            progress?.apply { isIndeterminate = s.status in setOf("starting", "restoring", "cancelling"); max = maxOf(1, s.total); progress = s.completed }
            progressCount?.text = if (s.id.isEmpty()) "Доступно ${m.strategies.size} стратегий. Проверяется весь каталог модуля."
                else "Обработано ${s.completed} из ${s.total}  ·  пропущено ${s.results.count { it.status == "SKIP" }}  ·  ${formatTime(s.started)}"
            subtitle?.text = when {
                !app.connected && s.id.isEmpty() -> "Откройте помощь и подключите модуль."
                !m.compatible && app.connected -> "Эта версия модуля не поддерживает результаты подбора для приложения."
                s.id.isEmpty() -> "Названия стратегий сохранены из каталога Pocket. Выберите стратегию или запустите подбор."
                !s.matches(m) -> "Сохранены прежние результаты (${s.moduleVersion.ifEmpty { "версия не записана" }}). Каталог изменился или не был записан — для актуальной оценки повторите подбор."
                else -> "Рейтинг сохранён. Баллы относятся к сетевым проверкам; видео проверяется отдельно. OK — выбрать стратегию."
            }
        }
        if (page == 7) {
            info?.text = when {
                app.updateRequested || u.installing -> "Обновление устанавливается"
                u.pending -> "Осталась перезагрузка"
                u.phase in setOf("failed", "interrupted") -> "Обновление не завершено"
                !app.connected -> "Сначала подключите обход"
                r != null && r.minAppVersion > BuildConfig.VERSION_CODE -> "Сначала обновите SV Pocket"
                r != null && r.versionCode > m.versionCode -> "Доступно обновление ${r.version}"
                r == null -> "Проверьте наличие обновлений"
                else -> "Доступных обновлений нет"
            }
            subtitle?.text = "Установлено: ${m.version.ifEmpty { "не определено" }}" +
                (if (r != null) "  ·  В каталоге: ${r.version}" else "") + "\n\n" + when {
                    u.pending -> "Модуль установлен. Новая версия начнёт работать после перезагрузки."
                    u.phase in setOf("failed", "interrupted") -> "Откройте подробности установки. Если перезагрузка уже была, проверьте состояние модуля. Резервная копия сохранена на приставке."
                    else -> r?.notes.orEmpty()
                }
            progress?.apply { visibility = if (app.updateRequested || u.installing) View.VISIBLE else View.GONE; max = 100; isIndeterminate = app.updateProgress < 0; progress = app.updateProgress.coerceAtLeast(0) }
            progressCount?.text = if (app.updateRequested) app.busy else app.updateMessage
            visible("install_update", !u.blocksChanges && r != null && r.versionCode > m.versionCode)
            visible("reboot_update", u.pending)
            visible("update_later", u.pending)
            visible("update_details", u.phase != "idle" || app.error.isNotEmpty())
        }
        updateRows(); journal?.text = app.diagnosticText()
        val focused = currentFocus
        if (focused != null && !nav.contains(focused) && (focused.visibility != View.VISIBLE || !focused.isEnabled)) {
            controls.values.firstOrNull { it.visibility == View.VISIBLE && it.isEnabled }?.requestFocus() ?: navigationButton().requestFocus()
        }
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
            if (row.result != null && !app.snapshot.matches(app.module)) append("\nЭто результат прежнего каталога. Требуется новая проверка.")
            append("\n\nПосле выбора Pocket сохранит стратегию и перезапустит сервис. Затем откройте YouTube или SmartTube для проверки видео.")
            if (!available) append("\n\nВ текущем каталоге этой стратегии нет. Результат сохранён от предыдущей версии модуля.")
            if (app.snapshot.active) append("\n\nСначала остановите подбор и дождитесь восстановления сервиса.")
        }
        val dialog = AlertDialog.Builder(this).setTitle(row.name).setMessage(message)
            .setNegativeButton("Закрыть", null)
        if (available && app.changesAllowed) {
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
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SV Pocket log", text))
            Toast.makeText(this, "Журнал скопирован в буфер обмена", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "Копирование не удалось: ${e.message}", Toast.LENGTH_LONG).show() }
    }
    private fun shareLog() {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, app.diagnosticText().takeLast(180000)), "Отправить журнал SV Pocket")) }
        catch (_: Exception) { Toast.makeText(this, "На приставке нет приложения для отправки. Используйте «Скопировать».", Toast.LENGTH_LONG).show() }
    }
    private fun openVideo(smart: Boolean, feedback: Boolean = false) {
        val names = if (smart) listOf("com.teamsmart.videomanager.tv", "com.teamsmart.videomanager.tv.beta")
            else listOf("com.google.android.youtube.tv", "com.google.android.youtube")
        val intent = names.firstNotNullOfOrNull { packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) Toast.makeText(this, "${if (smart) "SmartTube" else "YouTube"} не найден. Откройте его из меню приставки.", Toast.LENGTH_LONG).show()
        else try { if (feedback) app.videoOpened(); startActivity(intent) } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && nav.contains(currentFocus)) { focusContent(); return true }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !nav.contains(currentFocus)) {
            navigationButton().requestFocus(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    @Deprecated("TV back navigation")
    override fun onBackPressed() {
        when {
            backStack.isNotEmpty() -> { rememberPosition(); val previous = backStack.removeAt(backStack.lastIndex); showPage(previous, remember = false); focusContent() }
            page != 0 -> { showPage(0, remember = false); nav[0].requestFocus() }
            !nav.contains(currentFocus) -> navigationButton().requestFocus()
            else -> super.onBackPressed()
        }
    }
}
