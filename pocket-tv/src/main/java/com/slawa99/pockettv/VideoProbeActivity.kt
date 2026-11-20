package com.slawa99.pockettv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.ConnectivityManager
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONArray

internal fun Context.videoNetwork(): Long =
    getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle ?: 0L

/** One visible, disposable player process per candidate. No JS interface or access to root. */
class VideoProbeActivity : Activity() {
    private val life = Binder()
    private val main = Handler(Looper.getMainLooper())
    private val clock get() = SystemClock.elapsedRealtime()
    private lateinit var status: TextView
    private var view: WebView? = null
    private lateinit var stop: Button
    private var meter = VideoMeter(0)
    private var loaded = false
    private var finished = false
    private var polling = false
    private var blocked = false
    private var blockedAt = 0L
    private var loadedAt = 0L
    private val loop = object : Runnable { override fun run() { pulse(); if (!finished) main.postDelayed(this,400) } }
    private val ticket get() = intent.getStringExtra("token").orEmpty()
    private fun label(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState!=null || ticket.isBlank()) { end("pause","Экран плеера был закрыт."); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        val dp = resources.displayMetrics.density
        val pad = (16*dp).toInt()
        val page = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,27,30)); setPadding(pad,pad,pad,pad)
        }
        val idx=intent.getIntExtra("index",1); val count=intent.getIntExtra("count",1)
        val group=(idx-1)/VideoRank.GROUP+1
        val passes=intent.getIntExtra("passes",0)
        page.addView(label("SV Pocket  /  Видеотест YouTube",18f,Color.rgb(115,247,210)))
        val row=LinearLayout(this)
        val frame=FrameLayout(this); row.addView(frame,LinearLayout.LayoutParams(0,-1,2f))
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(pad,0,0,0); gravity=Gravity.CENTER_VERTICAL }
        panel.addView(label("Проверяется: " + intent.getStringExtra("candidate"),22f))
        panel.addView(label("Кандидат $idx из $count · группа $group",15f))
        panel.addView(label(if (passes >= VideoRank.MIN_PASSES) "Воспроизвели: $passes · завершаем группу"
            else "Воспроизвели: $passes · для выбора нужно ${VideoRank.MIN_PASSES}",15f))
        if(intent.getIntExtra("clip",0)==1) panel.addView(label("Резервный контрольный ролик",14f,Color.rgb(115,247,210)))
        status=label("Открываем видеоплеер…",16f)
        panel.addView(status)
        stop=Button(this).apply { text="Остановить видеотест"; isAllCaps=false; tag="stop_video"; setOnClickListener { end("stop","Видеотест остановлен.") } }
        panel.addView(stop); row.addView(panel,LinearLayout.LayoutParams(0,-1,1f))
        page.addView(row,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(page); stop.requestFocus()
        try {
            val w=WebView(this); view=w
            w.setBackgroundColor(Color.BLACK)
            with(w.settings) {
                javaScriptEnabled=true; domStorageEnabled=true; cacheMode=WebSettings.LOAD_NO_CACHE
                allowFileAccess=false; allowContentAccess=false; javaScriptCanOpenWindowsAutomatically=false
                mediaPlaybackRequiresUserGesture=false; mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            w.clearCache(true) // This process has a private WebView data directory, not the user's browser cache.
            w.webViewClient=object: WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=r.isForMainFrame || r.url.scheme!="https"
                override fun onRenderProcessGone(v:WebView,d:RenderProcessGoneDetail):Boolean {
                    end("invalid","Системный WebView завершился. Стратегия не оценена.");return true
                }
            }
            frame.addView(w,FrameLayout.LayoutParams(-1,-1))
        } catch(e:Exception){ end("invalid","Не удалось открыть системный WebView. Видеотест недоступен.");return }
        main.post(loop)
    }
    private fun pulse() {
        if (finished) return
        if (videoNetwork()!=intent.getLongExtra("network",0L)) {
            end("invalid","Подключение изменилось. Запустите подбор для текущей сети.");return
        }
        val w=view ?: return
        if (!loaded) {
            val r=Rect()
            if (!hasWindowFocus() || !w.getGlobalVisibleRect(r) || r.width()<w.width*0.85 || r.height()<w.height*0.85 || w.width<=0 || w.height<=0) return
            val base="https://"+packageName.lowercase(java.util.Locale.ROOT)+"/"
            val html=assets.open("video_probe.html").bufferedReader().use{it.readText()}
                .replace("__SV_INDEX__",intent.getIntExtra("index",1).toString())
                .replace("__SV_CLIP__",intent.getIntExtra("clip",0).coerceIn(0,1).toString())
                .replace("__SV_PROVIDER__",assets.open((application as PocketApplication).videoProviderAsset()).bufferedReader().use{it.readText()})
            loadedAt=clock; meter=VideoMeter(loadedAt); loaded=true
            w.loadDataWithBaseURL(base,html,"text/html","UTF-8",null)
        }
        if (blocked && clock-blockedAt>120_000) {end("pause","Плеер ждёт нажатия OK. Можно продолжить позже.");return}
        if (!blocked && clock-meter.began>70_000) {end("timeout","");return}
        if (polling) return
        polling=true
        w.evaluateJavascript("typeof svRead==='function'?svRead():[false,false,0,-1,0]") { value ->
            polling=false
            if (finished) return@evaluateJavascript
            val a=try { JSONArray(value) } catch(e:Exception) { return@evaluateJavascript }
            val err=a.optInt(2);val wait=a.optBoolean(1)
            if (err!=0) {
                // HTML5 playback failure can be specific to this candidate. Unlike a
                // missing/restricted clip or rejected app identity it doesn't end the group.
                if (err in setOf(100,101,150)) { end("clip_unavailable","код $err"); return@evaluateJavascript }
                if (err==5) { end("player_error",""); return@evaluateJavascript }
                val reason=when(err){
                    153->"YouTube не принял данные приложения для встраивания."
                    else->"Плеер YouTube сообщил об ошибке $err."
                }
                end("invalid","$reason Стратегия не оценена.");return@evaluateJavascript
            }
            if (wait) {
                if(!blocked){blocked=true;blockedAt=clock;w.requestFocus();w.evaluateJavascript("document.getElementById('gesture').focus()",null)}
                status.text="Автозапуск заблокирован. Нажмите OK в плеере."
                return@evaluateJavascript
            }
            if (blocked){blocked=false;meter=VideoMeter(clock);stop.requestFocus()}
            val state=a.optInt(3,-1);val done=meter.sample(clock,state,a.optDouble(4,0.0))
            status.text=when { meter.startup>0->"Воспроизведение: ${meter.seconds} из 20 с"
                a.optBoolean(0)->"Ждём начала воспроизведения…"; else->"Загружаем плеер YouTube…" }
            if(done!=null)end(done,if(done=="pass") "" else "Состояние плеера: $state; прошло ${meter.seconds} с видео.",meter.startup)
        }
    }
    private fun end(code:String,reason:String,start:Long=0) {
        if(finished)return
        finished=true;main.removeCallbacks(loop)
        android.util.Log.i("SVVideo","attempt=${intent.getIntExtra("index",0)} result=$code media=${meter.seconds}s startup=${start}ms $reason")
        val b=Bundle().apply{putBinder("player_process",life);putString("token",ticket);putString("code",code);putString("reason",reason);putLong("ms",start)}
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<ResultReceiver>("reply")?.send(RESULT_OK,b)
        setResult(RESULT_OK,Intent().putExtras(b));finish()
    }
    @Deprecated("TV remote back")
    override fun onBackPressed(){end("stop","Видеотест остановлен с пульта.")}
    override fun onPause(){ if(!finished)end("pause","Видеотест приостановлен при выходе из приложения."); view?.onPause();super.onPause() }
    override fun onDestroy(){
        main.removeCallbacks(loop)
        view?.let { w-> (w.parent as? ViewGroup)?.removeView(w);w.stopLoading();w.destroy() };view=null
        super.onDestroy()
        // Never share the previous candidate's open media connections with the next one.
        if (isFinishing && Application.getProcessName()==packageName+":videoprobe")
            main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) },180)
    }
}
