package io.github.ouse.ousecode

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val manager by lazy { RuntimeManager.get(this) }
    private val androidAccess by lazy { AndroidAccess.get(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var splash: BootAnimationView
    private var splashFinished = false
    private lateinit var startup: LinearLayout
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var edge: FloatingSettings
    private var temporaryTerminal: TemporaryTerminal? = null
    private var androidTerminal: TemporaryTerminal? = null
    private enum class SettingsPage { HOME, TERMINAL, ANDROID, RUNTIME, ANIMATION, ABOUT }
    private var settingsPage = SettingsPage.HOME
    private var androidApproval: Pair<AndroidApproval, Dialog>? = null
    private var resumed = false
    private val androidObserver: () -> Unit = {
        syncAndroidApproval()
        if (androidApproval == null && settingsPage == SettingsPage.ANDROID && settingsDialog?.isShowing == true) {
            settingsDialog?.dismiss(); openSettingsPanel(SettingsPage.ANDROID)
        }
    }
    private var settingsDialog: Dialog? = null
    private var surfaceColor = Color.rgb(30, 30, 30)
    private var textColor = Color.rgb(229, 229, 229)
    private var loadedBase = ""
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: String? = null
    private val observer: (RuntimeStatus) -> Unit = { render(it) }
    private fun t(zh: String, en: String) = manager.text(zh, en)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = Color.rgb(229, 237, 249)) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 21, 29)
        window.navigationBarColor = Color.rgb(17, 21, 29)
        val content = FrameLayout(this).apply { setBackgroundColor(Color.rgb(17, 21, 29)) }
        splash = BootAnimationView(this)
        content.addView(splash, FrameLayout.LayoutParams(-1, -1))
        startup = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(32))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(22, 32, 49), Color.rgb(12, 16, 23)))
        }
        startup.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(80), dp(80)).apply { bottomMargin = dp(24) })
        title = label("OpenCode", 32f).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        startup.addView(title, LinearLayout.LayoutParams(-1, dp(50)))
        description = label(t("你的随身智能体工作区", "Your agent workspace, on your device"), 16f, Color.rgb(166, 183, 206)).apply { gravity = Gravity.CENTER }
        startup.addView(description, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(24) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        startup.addView(progress, LinearLayout.LayoutParams(dp(240), dp(5)).apply { bottomMargin = dp(24) })
        action = Button(this).apply { text = t("启动", "Start"); isAllCaps = false; setOnClickListener { RuntimeService.send(this@MainActivity) } }
        startup.addView(action, LinearLayout.LayoutParams(dp(180), dp(48)))
        startup.addView(label(t("Ubuntu · PRoot · OpenCode", "Ubuntu · PRoot · OpenCode"), 13f, Color.rgb(115, 137, 165)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(startup, FrameLayout.LayoutParams(-1, -1))
        edge = FloatingSettings(this) { showSettings() }.also { it.attachTo(content) }
        setContentView(content)
        androidAccess.observe(androidObserver)
        // Draw native startup first; even WebView initialization happens after the first frame.
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                content.post {
                    if (!isDestroyed) {
                        web = OpenCodeWebView(this@MainActivity)
                        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
                        configureWeb()
                        content.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
                        manager.observe(observer)
                        if (manager.status.phase == Phase.STOPPED) RuntimeService.send(this@MainActivity)
                    }
                }
                return true
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWeb() {
        web.setBackgroundColor(Color.rgb(17, 21, 29))
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            allowFileAccess = false; allowContentAccess = true
            offscreenPreRaster = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (localOrigin(Uri.parse(url))) (view as OpenCodeWebView).installInputCompatibility()
                syncPageTheme {}
                if (manager.status.phase == Phase.READY && localOrigin(Uri.parse(url)) && !Uri.parse(url).path.orEmpty().startsWith("/auth/")) {
                    view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            val current = Uri.parse(view.url ?: "")
                            if (!isDestroyed && manager.status.phase == Phase.READY && localOrigin(current) && !current.path.orEmpty().startsWith("/auth/")) finishBoot()
                        }
                    })
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (localOrigin(request.url)) return false
                if (request.isForMainFrame) openExternal(request.url)
                return true
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                if ((host == "127.0.0.1" || host == "127.0.0.1:${Uri.parse(manager.baseUrl).port}") && localOrigin(Uri.parse(view.url ?: manager.baseUrl))) {
                    handler.proceed("opencode", manager.authPassword())
                } else handler.cancel()
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                startup.visibility = View.VISIBLE
                title.text = t("页面加载失败", "Page could not load")
                description.text = error.description
                progress.visibility = View.GONE
                action.visibility = View.VISIBLE; action.text = t("重新加载", "Reload")
                action.setOnClickListener { startup.visibility = View.GONE; web.reload() }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null); chooser = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    val types = params.acceptTypes.filter { it.contains('/') }.toTypedArray()
                    if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types)
                }
                startActivityForResult(intent, 41)
                return true
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) return false
                val child = WebView(this@MainActivity)
                child.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openExternal(request.url); child.destroy(); return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() }
        }
        web.setDownloadListener { url, _, disposition, mime, _ ->
            if (localOrigin(Uri.parse(url))) {
                pendingDownload = url
                val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = mime ?: "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, name)
                }, 42)
            } else if (url.startsWith("https://")) openExternal(Uri.parse(url))
            else showMessage(t("下载", "Download"), t("请使用文件导出入口下载此内容。", "Use the file export action to save this content."))
        }
    }

    private fun localOrigin(uri: Uri): Boolean {
        val base = Uri.parse(manager.baseUrl)
        return uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == base.port
    }
    private fun openExternal(uri: Uri) {
        if (uri.scheme !in setOf("http", "https", "mailto")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: android.content.ActivityNotFoundException) { showMessage(t("无法打开链接", "Cannot open link"), uri.toString().substringBefore('?')) }
    }

    private fun render(state: RuntimeStatus) {
        if (isDestroyed) return
        if (manager.busy) temporaryTerminal?.close()
        if (manager.busy) androidTerminal?.close()
        updateBoot(state)
        if (state.phase == Phase.READY) {
            startup.visibility = View.GONE
            web.visibility = View.VISIBLE
            if (loadedBase != manager.baseUrl) {
                loadedBase = manager.baseUrl
                val expectedBase = loadedBase
                title.text = "OpenCode"
                description.text = t("连接本机工作区", "Connecting your local workspace")
                progress.visibility = View.VISIBLE; progress.isIndeterminate = true
                action.visibility = View.GONE
                web.setHttpAuthUsernamePassword("127.0.0.1:${Uri.parse(loadedBase).port}", "Secure Area", "opencode", manager.authPassword())
                thread(name = "ousecode-pair") {
                    try {
                        val url = manager.pairingUrl()
                        main.post {
                            if (!isDestroyed && manager.status.phase == Phase.READY && manager.baseUrl == expectedBase) {
                                startup.visibility = View.GONE
                                web.loadUrl(url)
                            }
                        }
                    } catch (error: Exception) {
                        main.post {
                            if (!isDestroyed && manager.status.phase == Phase.READY && manager.baseUrl == expectedBase) {
                                startup.visibility = View.VISIBLE
                                title.text = t("连接失败", "Connection failed")
                                description.text = error.message
                                progress.visibility = View.GONE
                                action.visibility = View.VISIBLE; action.text = t("重试", "Retry")
                                action.setOnClickListener { loadedBase = ""; render(manager.status) }
                            }
                        }
                    }
                }
            }
        } else {
            if (state.phase != Phase.STOPPED) loadedBase = ""
            startup.visibility = if (manager.busy) View.GONE else View.VISIBLE
            web.visibility = View.GONE
            title.text = if (state.phase == Phase.ERROR) t("暂时无法启动", "Unable to start") else "OpenCode"
            description.text = state.message + if (state.progress >= 0) "  ${state.progress}%" else ""
            progress.visibility = if (manager.busy) View.VISIBLE else View.GONE
            progress.isIndeterminate = state.progress < 0
            if (state.progress >= 0) progress.progress = state.progress
            action.visibility = if (manager.busy) View.GONE else View.VISIBLE
            action.text = if (state.phase == Phase.ERROR) t("重试", "Retry") else t("启动", "Start")
            action.setOnClickListener { RuntimeService.send(this) }
            if (state.phase == Phase.ERROR) description.setOnClickListener { showLogs() }
        }
    }

    private fun updateBoot(state: RuntimeStatus) {
        if (!::splash.isInitialized) return
        if (state.phase != Phase.READY) {
            splash.cancelExit(); splash.alpha = 1f; splash.translationY = 0f; splash.visibility = View.VISIBLE
            if (::web.isInitialized) web.alpha = 1f
            if (splashFinished) { splash.resetBoot(); splash.resumeAnimation() }
            splashFinished = false
        }
        if (!splashFinished) {
            val message = if (state.phase == Phase.READY) t("连接本机工作区", "Connecting your local workspace") else state.message
            splash.setBootProgress(state.progress, message)
        }
    }

    private fun finishBoot() {
        if (splashFinished) return
        splashFinished = true
        startup.visibility = View.GONE
        splash.finishBoot()
        splash.playExit {
            if (!isDestroyed && manager.status.phase == Phase.READY && splashFinished) {
                splash.visibility = View.GONE
                splash.pauseAnimation()
            }
        }
    }

    private fun showSettings() {
        if (settingsDialog?.isShowing == true) return
        syncPageTheme { openSettingsPanel() }
    }

    private fun syncPageTheme(done: () -> Unit) {
        if (!::web.isInitialized || web.visibility != View.VISIBLE || !localOrigin(Uri.parse(web.url ?: ""))) { done(); return }
        // Read the rendered page palette, without injecting controls into the upstream app.
        web.evaluateJavascript("""
            (() => {
              const root = document.documentElement;
              const style = getComputedStyle(root);
              const probe = document.createElement('span');
              probe.style.position = 'fixed'; probe.style.visibility = 'hidden';
              probe.style.backgroundColor = style.getPropertyValue('--background-base').trim() || getComputedStyle(document.body).backgroundColor;
              probe.style.color = style.getPropertyValue('--text-base').trim() || getComputedStyle(document.body).color;
              document.body.appendChild(probe);
              const s = getComputedStyle(probe);
              const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
              const ctx = canvas.getContext('2d');
              const rgba = color => { ctx.clearRect(0,0,1,1); ctx.fillStyle = color; ctx.fillRect(0,0,1,1); return Array.from(ctx.getImageData(0,0,1,1).data); };
              const result = [rgba(s.backgroundColor), rgba(s.color)];
              probe.remove();
              return result;
            })()
        """.trimIndent()) { result ->
            if (isDestroyed) return@evaluateJavascript
            if (result != "null") {
                val palette = JSONArray(result)
                fun color(rgba: JSONArray, fallback: Int): Int {
                    return if (rgba.getInt(3) > 0) Color.rgb(rgba.getInt(0), rgba.getInt(1), rgba.getInt(2)) else fallback
                }
                surfaceColor = color(palette.getJSONArray(0), surfaceColor)
                textColor = color(palette.getJSONArray(1), textColor)
                edge.updateTheme(surfaceColor, textColor)
                window.statusBarColor = surfaceColor; window.navigationBarColor = surfaceColor
                val light = Color.red(surfaceColor) * 0.299 + Color.green(surfaceColor) * 0.587 + Color.blue(surfaceColor) * 0.114 > 160
                window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            }
            done()
        }
    }

    private fun themedContext() = ContextThemeWrapper(this, if (Color.red(surfaceColor) + Color.green(surfaceColor) + Color.blue(surfaceColor) > 480) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert)

    private fun openSettingsPanel(page: SettingsPage = SettingsPage.HOME) {
        if (settingsDialog?.isShowing == true) return
        settingsPage = page
        val dialog = Dialog(themedContext()).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); setCanceledOnTouchOutside(true) }
        settingsDialog = dialog
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(12))
            val dark = Color.red(surfaceColor) + Color.green(surfaceColor) + Color.blue(surfaceColor) < 480
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(38, 38, 38) else Color.rgb(250, 250, 250))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (dark) Color.rgb(76, 76, 76) else Color.rgb(210, 210, 210))
            }
            elevation = dp(18).toFloat()
        }
        val panelText = if (Color.red(surfaceColor) + Color.green(surfaceColor) + Color.blue(surfaceColor) < 480) Color.rgb(240, 240, 240) else Color.rgb(30, 30, 30)
        val heading = when (page) {
            SettingsPage.HOME -> t("设置", "Settings")
            SettingsPage.TERMINAL -> t("终端", "Terminal")
            SettingsPage.ANDROID -> t("Android 访问", "Android access")
            SettingsPage.RUNTIME -> t("运行环境", "Runtime environment")
            SettingsPage.ANIMATION -> t("启动动画", "Startup animation")
            SettingsPage.ABOUT -> t("关于", "About")
        }
        panel.addView(label(if (page == SettingsPage.HOME) heading else "‹  $heading", 19f, panelText).apply {
            setPadding(dp(12), 0, dp(12), 0)
            if (page != SettingsPage.HOME) { isFocusable = true; setOnClickListener { dialog.dismiss(); openSettingsPanel() } }
        }, LinearLayout.LayoutParams(-1, dp(40)))
        fun row(name: String, enabled: Boolean = true, run: () -> Unit) {
            val view = label(name, 15f, panelText).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                isEnabled = enabled; alpha = if (enabled) 1f else 0.35f
                val shape = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.TRANSPARENT) }
                background = RippleDrawable(ColorStateList.valueOf((textColor and 0x00ffffff) or 0x18000000), shape, null)
                isFocusable = true; setOnClickListener { dialog.dismiss(); run() }
            }
            panel.addView(view, LinearLayout.LayoutParams(-1, dp(46)))
        }
        val ready = manager.status.phase == Phase.READY
        when (page) {
            SettingsPage.HOME -> {
                row(t("终端  ›", "Terminal  ›")) { openSettingsPanel(SettingsPage.TERMINAL) }
                row(t("Android 访问  ›", "Android access  ›")) { openSettingsPanel(SettingsPage.ANDROID) }
                row(t("运行环境  ›", "Runtime environment  ›")) { openSettingsPanel(SettingsPage.RUNTIME) }
                row(t("启动动画  ›", "Startup animation  ›")) { openSettingsPanel(SettingsPage.ANIMATION) }
                row(t("关于  ›", "About  ›")) { openSettingsPanel(SettingsPage.ABOUT) }
            }
            SettingsPage.TERMINAL -> {
                row(t("打开 Ubuntu 终端", "Open Ubuntu terminal"), manager.rootfs.isDirectory && !manager.busy) { showTerminal() }
                row(t("终端显示名称", "Terminal display name")) { showTerminalName() }
            }
            SettingsPage.ANDROID -> {
                val state = when {
                    !androidAccess.installed -> t("未安装 Shizuku", "Shizuku is not installed")
                    !androidAccess.running -> t("Shizuku 未启动", "Shizuku is not running")
                    !androidAccess.authorized -> t("等待 Shizuku 授权", "Shizuku authorization required")
                    else -> "Shizuku · UID ${androidAccess.serviceUid}"
                }
                panel.addView(label(state, 14f, panelText).apply { alpha = 0.75f; setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(-1, dp(36)))
                row(t("执行模式", "Execution mode") + " · " + androidModeName(androidAccess.mode) + "  ›", !manager.busy) { chooseAndroidMode() }
                if (androidAccess.mode == AndroidMode.SMART) panel.addView(label(t("状态查询免确认；其他命令先确认", "Status queries run automatically; other commands ask"), 14f, panelText).apply {
                    alpha = 0.75f; setPadding(dp(12), 0, dp(12), dp(8))
                }, LinearLayout.LayoutParams(-1, -2))
                if (!androidAccess.authorized) row(if (androidAccess.running) t("授权 Shizuku", "Authorize Shizuku") else t("打开 Shizuku", "Open Shizuku")) {
                    try {
                        if (androidAccess.running) { openSettingsPanel(SettingsPage.ANDROID); androidAccess.requestPermission() }
                        else packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(::startActivity)
                            ?: openExternal(Uri.parse("https://shizuku.rikka.app/download/"))
                    } catch (error: RuntimeException) { showMessage("Shizuku", error.message ?: "SHIZUKU_ERROR") }
                }
                row(t("打开 Android 终端", "Open Android terminal"), androidAccess.mode != AndroidMode.OFF && androidAccess.authorized && !manager.busy) { showAndroidTerminal() }
                row(t("跨应用确认", "Cross-app approval") + " · " + if (android.provider.Settings.canDrawOverlays(this)) t("已开启", "Enabled") else t("去开启", "Enable")) {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                row(t("确认通知", "Approval notifications")) {
                    startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, AndroidApprovalUi.CHANNEL))
                }
            }
            SettingsPage.RUNTIME -> {
                val status = if (ready) t("本机运行中", "Running locally") else manager.status.message
                panel.addView(label(status, 14f, panelText).apply { alpha = 0.75f; setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(-1, dp(36)))
                row(t("重新加载页面", "Reload page"), ready) { web.reload() }
                row(t("重启运行环境", "Restart environment"), !manager.busy) {
                    confirm(t("重启运行环境", "Restart environment"), t("当前任务会中断，项目和会话会保留。", "Active tasks will be interrupted. Projects and sessions are retained.")) { RuntimeService.send(this, "restart") }
                }
                row(t("更新 OpenCode", "Update OpenCode"), ready) {
                    confirm(t("更新 OpenCode", "Update OpenCode"), t("结束当前任务后更新。会保留旧版可执行文件；跨大版本更新前请导出重要项目和会话。", "Finish active tasks before updating. The previous executable is retained; export important projects and sessions before major updates.")) { RuntimeService.send(this, "upgrade") }
                }
                row(t("运行日志", "Runtime logs")) { showLogs() }
                row(t("停止运行环境", "Stop environment"), ready) {
                    confirm(t("停止运行环境", "Stop environment"), t("将停止 OpenCode 和当前任务。", "OpenCode and its active tasks will stop.")) { RuntimeService.send(this, "stop") }
                }
            }
            SettingsPage.ANIMATION -> {
                panel.addView(label(t("数字雨 · 信号撕裂", "Digital rain · Signal breach"), 15f, panelText).apply {
                    setPadding(dp(12), 0, dp(12), 0)
                }, LinearLayout.LayoutParams(-1, dp(46)))
                row(t("预览动画", "Preview animation"), ready) {
                    splash.resetBoot(); splash.setBootProgress(100, t("本机工作区已就绪", "Local workspace ready"))
                    splash.visibility = View.VISIBLE; splash.resumeAnimation(); splashFinished = false
                    splash.postDelayed({ if (!isDestroyed && !splashFinished && manager.status.phase == Phase.READY) finishBoot() }, 500)
                }
            }
            SettingsPage.ABOUT -> {
                panel.addView(label("OpenCode Android ${packageManager.getPackageInfo(packageName, 0).versionName}", 15f, panelText).apply { setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(-1, dp(40)))
                row(t("开源许可", "Open-source licenses")) { showMessage(t("开源许可", "Open-source licenses"), assets.open("NOTICE.txt").bufferedReader().use { it.readText() }) }
            }
        }
        dialog.setContentView(ScrollView(this).apply { addView(panel); isFillViewport = true })
        dialog.setOnDismissListener {
            if (settingsDialog !== dialog) return@setOnDismissListener
            settingsDialog = null
            if (androidTerminal?.visibility == View.VISIBLE) androidTerminal?.terminal?.requestFocus()
            else if (temporaryTerminal?.visibility == View.VISIBLE) temporaryTerminal?.terminal?.requestFocus()
            else if (::web.isInitialized) web.requestFocus()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(400)), -2)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.45f }
        }
    }

    private fun androidModeName(mode: AndroidMode) = when (mode) {
        AndroidMode.OFF -> t("关闭", "Off")
        AndroidMode.ASK -> t("每次确认", "Ask each time")
        AndroidMode.SMART -> t("智能", "Smart")
        AndroidMode.DIRECT -> t("直接执行", "Run directly")
    }

    private fun syncAndroidApproval() {
        val request = androidAccess.pendingApproval
        if (request != null) settingsDialog?.dismiss()
        val shown = request != null && resumed && !androidAccess.approvalUi.showingOverlay
        if (shown && androidApproval?.first === request) return
        androidApproval?.second?.dismiss()
        if (!shown || request == null || isFinishing || isDestroyed) return
        val dialog = Dialog(themedContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(AndroidApprovalCard(this, request) { androidAccess.reply(request, it) })
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { androidAccess.reply(request, false) }
        dialog.setOnDismissListener { if (androidApproval?.second === dialog) androidApproval = null }
        androidApproval = request to dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(minOf(resources.displayMetrics.widthPixels - dp(32), dp(420)), -2)
            attributes = attributes.apply { dimAmount = 0.3f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
    private fun chooseAndroidMode() {
        val modes = AndroidMode.entries
        AlertDialog.Builder(themedContext()).setTitle(t("Android 执行模式", "Android execution mode"))
            .setSingleChoiceItems(modes.map(::androidModeName).toTypedArray(), modes.indexOf(androidAccess.mode)) { dialog, index ->
                dialog.dismiss()
                manager.updateAndroidMode(modes[index]) { error ->
                    if (!isDestroyed) {
                        openSettingsPanel(SettingsPage.ANDROID)
                        if (error != null) showMessage(t("设置更新失败", "Settings update failed"), error)
                    }
                }
            }.setNegativeButton(t("取消", "Cancel")) { _, _ -> openSettingsPanel(SettingsPage.ANDROID) }.show()
    }

    private fun showAndroidTerminal() {
        if (androidAccess.mode == AndroidMode.OFF) { showMessage(t("Android 访问", "Android access"), t("请先开启 Android 访问。", "Enable Android access first.")); return }
        androidTerminal?.let { temporaryTerminal?.hide(); it.show(); return }
        fun open() {
            thread(name = "android-shell-connect") {
                try {
                    androidAccess.authorizeTerminal()
                    val remote = androidAccess.shell()
                    main.post { if (!isDestroyed) attachTerminal(remote) }
                } catch (error: Exception) {
                    val reason = error.cause?.message ?: error.message ?: "SHIZUKU_ERROR"
                    if (reason != "ANDROID_COMMAND_DENIED" && reason != "ANDROID_CONFIRMATION_TIMED_OUT") main.post { if (!isDestroyed) showMessage("Shizuku", reason) }
                }
            }
        }
        open()
    }

    private fun showTerminalName() {
        val column = LinearLayout(themedContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val input = EditText(themedContext()).apply {
            setSingleLine(true); setText(manager.terminalDisplayName()); selectAll()
            hint = "ouse@OpenCode"; contentDescription = t("终端显示名称", "Terminal display name")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val preview = TextView(themedContext()).apply { typeface = Typeface.MONOSPACE; textSize = 14f; setPadding(0, dp(12), 0, dp(12)) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { preview.text = "${s}:~/workspace#" }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        preview.text = "${input.text}:~/workspace#"
        column.addView(input); column.addView(preview)
        column.addView(TextView(themedContext()).apply { text = t("仅修改显示；新终端生效。当前终端可执行 source ~/.bashrc。", "Display only; applies to new terminals. Run source ~/.bashrc in an existing terminal."); textSize = 14f })
        val dialog = AlertDialog.Builder(themedContext()).setTitle(t("终端显示名称", "Terminal display name")).setView(column)
            .setNegativeButton(t("取消", "Cancel"), null).setPositiveButton(t("保存", "Save"), null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { manager.setTerminalDisplayName(input.text.toString().trim()); dialog.dismiss() }
            catch (error: IllegalArgumentException) { input.error = error.message }
            catch (error: java.io.IOException) { showMessage(t("保存失败", "Could not save"), error.message ?: "I/O error") }
        } }
        dialog.show()
    }
    private fun confirm(title: String, message: String, yes: () -> Unit) {
        if (manager.busy) { showMessage("OpenCode", t("请等待当前操作完成。", "Wait for the current operation to finish.")); return }
        AlertDialog.Builder(themedContext()).setTitle(title).setMessage(message).setNegativeButton(t("取消", "Cancel"), null).setPositiveButton(t("继续", "Continue")) { _, _ -> yes() }.show()
    }
    private fun showMessage(title: String, message: String) { AlertDialog.Builder(themedContext()).setTitle(title).setMessage(message).setPositiveButton(t("关闭", "Close"), null).show() }
    private fun showLogs() {
        val text = label(manager.logs().ifBlank { t("暂无日志", "No logs yet") }, 13f, textColor).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(themedContext()).setTitle(t("运行日志", "Runtime logs")).setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(t("关闭", "Close"), null).show()
    }
    private fun showTerminal() {
        settingsDialog?.dismiss()
        androidTerminal?.hide()
        temporaryTerminal?.let { it.show(); return }
        attachTerminal(null)
    }

    private fun attachTerminal(remote: IAndroidShell?) {
        try {
            if (remote != null) {
                temporaryTerminal?.hide()
                androidTerminal?.let { it.show(); return }
            }
            val root = edge.parent as FrameLayout
            val panel = TemporaryTerminal(this, manager, { dismissed ->
                if (dismissed.isClosed && temporaryTerminal === dismissed) {
                    temporaryTerminal = null
                }
                if (dismissed.isClosed && androidTerminal === dismissed) androidTerminal = null
                if (temporaryTerminal == null && androidTerminal == null) root.removeOnLayoutChangeListener(terminalLayoutListener)
                if (::web.isInitialized) web.requestFocus()
            }, remote)
            if (remote == null) temporaryTerminal = panel else androidTerminal = panel
            val params = FrameLayout.LayoutParams(-1, (root.height * 0.55f).toInt(), Gravity.BOTTOM).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            }
            root.addView(panel, params)
            root.addOnLayoutChangeListener(terminalLayoutListener)
            panel.show()
        } catch (error: RuntimeException) { showMessage(t("终端启动失败", "Terminal could not start"), error.message ?: "PTY startup failed") }
    }

    private val terminalLayoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        listOfNotNull(temporaryTerminal, androidTerminal).forEach { panel ->
            val height = (view.height * 0.55f).toInt()
            if (panel.layoutParams.height != height) panel.layoutParams = panel.layoutParams.apply { this.height = height }
        }
    }

    private fun toggleTerminal() {
        val panel = temporaryTerminal
        if (panel?.visibility == View.VISIBLE) panel.hide() else showTerminal()
    }
    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41) {
            val uris = if (resultCode == RESULT_OK) data?.clipData?.let { clips -> Array(clips.itemCount) { clips.getItemAt(it).uri } }
                ?: data?.data?.let { arrayOf(it) } else null
            chooser?.onReceiveValue(uris); chooser = null
        }
        if (requestCode == 42) {
            val url = pendingDownload; pendingDownload = null
            val destination = data?.data
            if (resultCode == RESULT_OK && url != null && destination != null) thread(name = "ousecode-download") {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Authorization", manager.authorization())
                    connection.connectTimeout = 15000; connection.readTimeout = 30000
                    try {
                        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                        connection.inputStream.use { src -> contentResolver.openOutputStream(destination)!!.use { src.copyTo(it) } }
                    } finally { connection.disconnect() }
                } catch (error: Exception) { main.post { if (!isDestroyed) showMessage(t("下载失败", "Download failed"), error.message ?: "Download failed") } }
            }
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isCtrlPressed && event.isAltPressed && !event.isShiftPressed && event.keyCode == KeyEvent.KEYCODE_T) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleTerminal()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_O) {
            if (event.repeatCount == 0) showSettings()
            return true
        }
        if (::web.isInitialized && event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && event.isShiftPressed && event.keyCode == KeyEvent.KEYCODE_R) { web.reload(); return true }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Platform back handling")
    override fun onBackPressed() {
        if (androidTerminal?.visibility == View.VISIBLE) androidTerminal?.hide()
        else if (temporaryTerminal?.visibility == View.VISIBLE) temporaryTerminal?.hide()
        else if (::web.isInitialized && web.visibility == View.VISIBLE && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }
    override fun onResume() {
        super.onResume(); if (::web.isInitialized) web.onResume()
        resumed = true
        androidAccess.approvalUi.sync(force = true)
        syncAndroidApproval()
        if (::splash.isInitialized && splash.visibility == View.VISIBLE) splash.resumeAnimation()
    }
    override fun onPause() {
        resumed = false
        androidApproval?.second?.dismiss()
        if (::splash.isInitialized) splash.pauseAnimation()
        CookieManager.getInstance().flush(); super.onPause()
    }
    override fun onDestroy() {
        androidApproval?.second?.dismiss()
        androidAccess.unobserve(androidObserver)
        androidTerminal?.close()
        temporaryTerminal?.close()
        settingsDialog?.dismiss()
        manager.unobserve(observer); chooser?.onReceiveValue(null); chooser = null
        if (::web.isInitialized) { web.animate().cancel(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        if (::splash.isInitialized) { splash.cancelExit(); splash.pauseAnimation(); (splash.parent as? ViewGroup)?.removeView(splash) }
        super.onDestroy()
    }
}
