package com.dshgo.app

import androidx.compose.foundation.layout.statusBarsPadding
import com.dshgo.app.ui.ConnectionsScreen
import com.dshgo.app.data.ConnectionStore
import com.dshgo.app.data.AutoRoute
import com.dshgo.app.data.NetWatcher
import com.dshgo.app.data.ConnectionProbe
import com.dshgo.app.data.Connection
import androidx.biometric.BiometricManager
import com.dshgo.app.data.UnlockClient
import com.dshgo.app.data.AppLock
import com.dshgo.app.data.UpdateInstaller
import com.dshgo.app.data.ApkDownloader
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File
import com.dshgo.app.data.ShareInbox
import com.dshgo.app.data.ComposerWriter
import java.util.Locale
import android.widget.Toast
import android.speech.RecognizerIntent
import com.dshgo.app.data.AddressPicker
import com.dshgo.app.tunnel.TsTunnel
import android.util.Log
import android.net.ConnectivityManager
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.dshgo.app.data.UpdateCheck
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.ValueCallback
import android.net.Uri
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import okhttp3.Request
import okhttp3.OkHttpClient
import com.dshgo.app.web.PageInject
import com.dshgo.app.data.WebViewCookies
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import com.dshgo.app.ui.NoticePanel
import com.dshgo.app.notify.SessionWatcher
import com.dshgo.app.notify.NotificationCenter
import com.dshgo.app.notify.WidgetRefresher
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dshgo.app.ui.ConnectingScreen
import com.dshgo.app.ui.CrashDialog
import com.dshgo.app.ui.DshLoadingOverlay
import com.dshgo.app.ui.LockScreen
import com.dshgo.app.ui.PageErrorOverlay
import com.dshgo.app.ui.DshStatusStrip
import com.dshgo.app.ui.Screen
import com.dshgo.app.ui.SettingsSheet
import com.dshgo.app.ui.SetupScreen
import com.dshgo.app.data.EventLog
import com.dshgo.app.ui.StripHeight
import com.dshgo.app.ui.theme.DshTheme

/**
 * 单 Activity，**没有首页**。
 *
 * DSH 界面里本来就有会话列表（侧栏抽屉），客户端再画一份是纯重复。所以配好地址之后
 * 打开就是 DSH 界面本身。
 *
 * 原生层只负责 DSH 界面做不到或做得别扭的几件事：
 *   · 与 dsh-pocket 的握手、会话凭据维护
 *   · 从会话里改入口地址、切换布局、界面缩放
 *   · 崩溃自记录
 *
 * WebView 常驻不销毁，所以「设置 → 改地址 → 连回来」不会丢掉页面状态。
 */
/**
 * 宿主 Activity。
 *
 * 继承 [androidx.fragment.app.FragmentActivity] 而不是 ComponentActivity，
 * 只是因为 `BiometricPrompt` 的构造函数要求前者（它内部用 Fragment 承载对话框）。
 * 其余能力不变 —— FragmentActivity 本身就是 ComponentActivity 的子类，
 * Compose、registerForActivityResult、enableEdgeToEdge 都照常。
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    /**
     * 快捷方式的 action 常量。
     *
     * 用 `internal` 而不是 `private`：主屏小组件的按钮要复用同一套 action ——
     * 逻辑只写一份，在 MainActivity 里处理。
     */
    internal companion object {
        const val TAG = "dshgo"

        /** 与 res/xml/shortcuts.xml 里的 action 一一对应。 */
        const val ACTION_NEW_SESSION = "com.dshgo.app.NEW_SESSION"
        const val ACTION_RECENT_SESSION = "com.dshgo.app.RECENT_SESSION"
        const val ACTION_OPEN_SETTINGS = "com.dshgo.app.OPEN_SETTINGS"
    }


    private lateinit var prefs: Prefs
    /**
     * **当前活跃**的 WebView。
     *
     * 名字没变、语义没变 —— 全文件几十处 `webView.xxx` 都不用动。
     * 变的是它不再只有一个：池子里可以有多个，这个字段指向正在显示的那个。
     */
    private lateinit var webView: WebView

    /** 同时常驻几个 WebView。见 webViews 的说明。 */
    private val MAX_LIVE_WEBVIEWS = 2

    /** 还没有配地址时占位用的空白页 —— 见 activate() 里为什么必须有它。 */
    private val BLANK_PAGE = "about:blank"

    /**
     * 常驻的 WebView，按入口地址存。最多 [MAX_LIVE_WEBVIEWS] 个。
     *
     * ## 为什么值得常驻
     *
     * 在两台电脑之间来回切时，重载一次要等整页加载；常驻之后切换只是换个可见性。
     *
     * ## 为什么只留两个
     *
     * 每个 WebView 里跑着一整个 DSH 页面，内存是实打实的。两个覆盖了
     * 「两台机器来回切」这个最常见的场景，再多就是拿内存换一个不常发生的场景。
     *
     * ## 为什么不需要担心"两套通知"
     *
     * 审批接管在 SessionWatcher（App 侧的单例），不在 WebView 里 ——
     * 它只跟当前入口地址走，所以多出来的 WebView 不会各推一条通知。
     * 它们的页面里根本没有接管逻辑。
     *
     * 但仍要**停掉非活跃那个的 JS**：见 activate() 里的 onPause()。
     */
    private val webViews = LinkedHashMap<String, WebView>()

    /** 装 WebView 的宿主。切换 = 换它里面的孩子，而不是重建。 */
    private lateinit var webHost: android.widget.FrameLayout

    /** DSH 页面是否已经成功载入过一次（决定能不能往页面里注入 JS）。 */
    private var loaded = false

    /**
     * 换路后「自动补一次刷新」的开关。
     *
     * 手机切换网络的那一刻，WebView 手里那条连接已经废了。多数情况下新导航能正常起来
     * （onPageCommitVisible 会把开关关掉），但偶尔首帧就是不来 —— 用户看到的就是
     * 「换路了、画面不出来、手动点一下刷新才有」。与其让用户去点，不如到点自己重载一次。
     * 只重载一次，避免在真的连不上时变成死循环刷屏。
     */
    private var switchAutoReloadArmed = false

    /** 进设备后是否已经自动补过一次导航（首帧到达后复位）。 */
    private var entryRetried = false

    /** 上一次「探测后发现 Tailscale 不通、自动重建隧道」的时间，用来节流。 */
    private var lastTunnelRebuildTry = 0L

    /** 宿主的解锁 cookie 名（`lib/auth.mjs` 的 `UNLOCK_COOKIE`）。同名才能在通道间搬运。 */
    private val UNLOCK_COOKIE = "dshgo-unlock"

    private val handler = Handler(Looper.getMainLooper())
    private var loadTimeout: Runnable? = null

    /**
     * 自己发请求用的客户端。用于 `shouldInterceptRequest` 里取主文档来改写
     * （注入布局覆盖 + polyfill 兜底），以及取不到时的兜底判断。
     * cookie 走 [WebViewCookies] —— 和 WebView 共用一份会话。
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookies)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 抓主文档专用的客户端：超时必须短。
     *
     * [shouldInterceptRequest] 是**同步**回调，它阻塞的是 WebView 自己的请求线程 ——
     * 这里多等一秒，用户就多一秒空白页。默认那 10 s 连接 / 20 s 读取是按「下载文件」定的，
     * 用在主文档上只有一个后果：换路后旧网络那条 socket 还没断，请求挂到超时才回来，
     * 页面就是一片空，用户以为没加载，手动刷新一次（新连接）才出画面。
     *
     * 3 s / 6 s 拿不到就当拦截失败，交回 WebView 自己发请求 —— 它会同样快地失败或成功，
     * 但至少不会卡在拦截层。
     */
    private val docClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private var ui by mutableStateOf(ShellState())

    /**
     * WebView 文件选择器的回执通道。
     *
     * 没有它，页面上任何 `<input type="file">`（DSH 的附件按钮就是这个）点了都**毫无反应** ——
     * WebView 通过 [WebChromeClient.onShowFileChooser] 把选择请求交给宿主，宿主不接管就等于丢弃。
     *
     * 选文件走的是 SAF（`ACTION_GET_CONTENT`），**不需要任何权限**：用户选中的文件由系统
     * 逐次授权给本 app。所以「图片/文件权限」在设置里找不到是正常的，本来就没有这个开关。
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 从通知点进来、但页面还没载入完时先存着，等 onPageFinished 再应用。
     * 冷启动点通知会走这条路径。
     */
    private var pendingSessionId: String? = null

    /** Android 13+ 发通知要运行时授权；用户拒绝就保持关闭，不反复骚扰。 */
    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // ★ 关键时机：权限刚拿到就建渠道。
            // 在还没权限时创建的渠道会被系统按静默落库（国产 ROM 尤其明显），
            // 而渠道一旦创建就不可变 —— 之后再改代码也没用。
            NotificationCenter.ensureDoneChannel(this)
        }
        setNotify(granted)
    }

    /**
     * 扫码填地址。
     *
     * 用 ZXing 的嵌入式 ScanContract：它自带一个相机 Activity，扫完把内容回传，
     * 不用自己写预览/解码。选它而不是 ML Kit，是因为后者依赖 Google Play 服务 ——
     * 侧载安装的设备上不保证有。
     *
     * 扫到就直接连，不再让用户点一次「连接」：二维码就是入口地址本身，
     * 扫的动作已经表达了「用这个」。
     */
    /** 语音识别器。见 [onVoice]。 */
    private var recognizer: android.speech.SpeechRecognizer? = null

    /** 麦克风权限。老版本没有这个权限，回调只会在新版本触发。 */
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening() else toast("需要麦克风权限才能语音输入")
    }

    private val scanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) return@registerForActivityResult
        val normalized = Prefs.normalize(raw)
        if (normalized.isEmpty()) {
            ui = ui.copy(setupError = "扫到的不是可用的地址：$raw")
            return@registerForActivityResult
        }
        connect(normalized)
    }

    /** 点「扫描二维码」。没相机的设备会由系统抛出来，接住给一句人话。 */
    private fun onScan() {
        val options = com.journeyapps.barcodescanner.ScanOptions().apply {
            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            setPrompt("对准电脑上的二维码")
            setBeepEnabled(false)
            // 锁定为进入扫码页时的方向：解锁会让 Samsung 折叠屏在展开/合上时把预览转错 90°，
            // 用户看到的是「相机方向和手机方向不符」。锁住后预览与屏幕始终一致。
            setOrientationLocked(true)
        }
        runCatching { scanLauncher.launch(options) }
            .onFailure { ui = ui.copy(setupError = "打不开相机 —— 检查有没有给相机权限，或直接手输地址。") }
    }

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        // parseResult 会把多选、取消、以及 file:///content:// 都归一成 Uri 数组
        cb?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        // 回到前台：页面自己会处理审批，把挂起的撤掉，别让两条链路抢答
        SessionWatcher.onForegroundChanged(true)
        // 顺手刷一次小组件快照：用户刚在 App 里干完活（换了地址、解锁了、开过会话），
        // 桌面上的卡片应该跟上。有网才拉，失败静默留在缓存。
        WidgetRefresher.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 统一 edge-to-edge。Android 15 起对 targetSdk 35 是强制的，旧版本上显式开启
        // 才能拿到同一套 inset 语义 —— 否则「输入法遮挡输入框」在不同系统上表现不一致。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = ShellState(
            entryUrl = prefs.entryUrl(),
            layout = prefs.layout(),
            scale = prefs.scale(),
            awake = prefs.keepAwake(),
            notifyEnabled = prefs.notifyEnabled(),
            crashReport = CrashLog.read(this),
        )
        CookieManager.getInstance().setAcceptCookie(true)

        webHost = android.widget.FrameLayout(this)
        activate(prefs.entryUrl())
        applyKeepAwake()

        setContent {
            DshTheme {
                Shell(
                    state = ui,
                    webView = webView,
                    webHost = webHost,
                    onConnect = ::connect,
                    onReload = ::reloadDsh,
                    onBack = ::handleBack,
                    onOpenSettings = { ui = ui.copy(settingsOpen = true, notifyProblem = notifyProblem()) },
                    onCloseSettings = { ui = ui.copy(settingsOpen = false) },
                    onChangeUrl = {
                        ui = ui.copy(
                            screen = Screen.Setup,
                            settingsOpen = false,
                            setupError = null,
                            connecting = false,
                        )
                    },
                    onCancelSetup = { ui = ui.copy(screen = Screen.Dsh) },
                    onSetupBack = { ui = ui.copy(screen = Screen.Connections) },
                    onLayout = ::setLayout,
                    onScale = ::applyScale,
                    onToggleAwake = ::toggleAwake,
                    onClearSession = ::clearSession,
                    onToggleNotify = ::requestNotify,
                    onTestNotify = ::onTestNotify,
                    onNotifySettings = ::onNotifySettings,
                    onScan = ::onScan,
                    onVoice = ::onVoice,
                    onUnlock = ::submitPassword,
                    onRetryUnlock = ::tryUnlock,
                    onLockMode = ::setLockMode,
                    // ★ 必须同时关掉设置面板 —— 它是 ModalBottomSheet，
                    //   只改 screen 的话连接页会开在它**后面**，用户看到的还是设置。
                    onOpenConnections = {
                        ui = ui.copy(screen = Screen.Connections, settingsOpen = false)
                        // ★ 从 DSH 回列表要重读 + 重探：切换过网络之后地址可能已经变了，
                        //   列表还挂着旧地址的话，点进去必然连不上（用户实测：要手动点刷新）。
                        refreshConnections()
                    },
                    onOpenSetupForAdd = { ui = ui.copy(screen = Screen.Setup, setupError = null) },
                    onBackToDsh = { ui = ui.copy(screen = Screen.Dsh) },
                    onSwitchConn = ::switchTo,
                    onProbeConn = ::refreshConnections,
                    onSaveConn = ::saveConnection,
                    onDeleteConn = ::deleteConnection,
                    lockModeState = ui.lockModeState,
                    lockDevice = ui.lockDevice,
                    updateLatest = ui.updateLatest,
                    updateNewer = ui.updateNewer,
                    updateChecking = ui.updateChecking,
                    updateApkUrl = ui.updateApkUrl,
                    onCheckUpdate = { checkUpdate(force = true) },
                    onPinWidget = ::pinWidget,
                    updatePhase = ui.updatePhase,
                    updateBytes = ui.updateBytes,
                    updateTotal = ui.updateTotal,
                    updateError = ui.updateError,
                    onInstall = { startUpdateDownload(ui.updateApkUrl) },
                    onDownload = { url -> openDownload(url) },
                    onOpenPanel = {
                        SessionWatcher.markAllSeen()
                        ui = ui.copy(panelOpen = true)
                    },
                    onClosePanel = { ui = ui.copy(panelOpen = false) },
                    onClearNotices = { SessionWatcher.clear() },
                    onOpenNotice = { sessionId -> openSessionFromNotice(sessionId) },
                    onDismissCrash = {
                        CrashLog.clear(this)
                        ui = ui.copy(crashReport = null)
                    },
                )
            }
        }

        NotificationCenter.ensureChannels(this)

        // ★ 启动先进首页（连接列表），不自动连 —— 有多台电脑时，
        //   自动连上某台再让你切走，不如先让你选。只有一条连接时也是一次点击。
        if (ConnectionStore.all(this).isEmpty() && !prefs.hasEntryUrl()) {
            ui = ui.copy(screen = Screen.Setup)
        } else {
            ui = ui.copy(screen = Screen.Connections)
        }
        maybeHandleShare(intent)
        // 从通知点进来 / 冷启动时带过来的目标会话
        consumeOpenSession(intent)
        // 冷启动是唯一能读到「上次进程怎么死的」的时机（ApplicationExitInfo 只在这里可靠）。
        com.dshgo.app.data.CrashCenter.capture(this)
        com.dshgo.app.data.EventLog.event(this, "app", "onCreate v${BuildConfig.VERSION_NAME}")
        if (prefs.notifyEnabled()) startWatching()

        // 已知地址不止一个时，看看现在这个还用不用得上
        autoPickAddress()

        // ★ 首屏不等探测：探测与查更新都挪到第一帧之后。
        //   冷启动时它们会和 Compose 首帧、WebView 初始化抢 CPU 与网络，
        //   表现就是点了图标先停一下才出画面。post 到 webHost 上，
        //   等这一轮布局绘制做完再开工。
        webHost.post {
            // 后台查一次更新（内部有 24 小时节流，失败静默）
            checkUpdate()
            // 连接列表：首次读取会把老版本的单地址迁进来（见 ConnectionStore.migrateIfNeeded）
            refreshConnections()
        }
        watchNetwork()
        maybeHandleShortcut(intent)

        // 解锁方式与设备能力各读一次（重组时不必反复查系统）
        ui = ui.copy(
            lockModeState = AppLock.mode(this).id,
            lockDevice = AppLock.describe(this),
        )
    }

    /** 网络切换后要还原的滚动位置；-1 表示没有待还原的。 */
    private var pendingScrollY: Int = -1

    /**
     * 手动选路的记忆。
     *
     * 用户一旦在连接列表里点了某台设备，就说明「我知道我要连哪」——
     * 这时后台探测再把地址换回局域网，看起来就是「隧道进去后地址自己变了」。
     * 只有网络类型变化（Wi-Fi ↔ 蜂窝）时清掉这个标记，让自动选路重新生效。
     */
    private val routePrefs by lazy { getSharedPreferences("dshgo_route", MODE_PRIVATE) }

    private fun manualRoute(): Boolean = routePrefs.getBoolean("manual", false)

    private fun setManualRoute(v: Boolean) {
        routePrefs.edit().putBoolean("manual", v).apply()
    }

    /** 原地重连但记住滚动位置：网络切换、隧道切换都走这里。 */
    private fun reloadKeepingPosition() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "String(window.scrollY||document.documentElement.scrollTop||0)",
        ) { raw ->
            pendingScrollY = raw?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
            runCatching { webView.reload() }
        }
    }

    override fun onStart() {
        super.onStart()
        // 前后台切换会决定「审批由谁接管」：前台交给页面，后台由 App 发通知/更新小组件。
        // 之前这个回调没有任何调用方，appForeground 永远是 true ——
        // 于是审批既不进通知也不进小组件计数。
        SessionWatcher.onForegroundChanged(true)
        // Wi-Fi ↔ 蜂窝 切换后 WebView 里那条连接已经废了：先按「是否在同一局域网」重选地址，
        // 没有更合适的再原地重连，并且保留滚动位置。
        NetWatcher.attach(this) {
            runOnUiThread {
                if (!::webView.isInitialized) return@runOnUiThread
                val current = webView.url ?: return@runOnUiThread
                if (!current.startsWith("http")) return@runOnUiThread
                val target = AutoRoute.pick(this, ConnectionStore.all(this), current)
                if (target != null && target != current) {
                    // ★ 换路后要把 entryUrl 一起翻过去：顶部地址/通道标签读的是它，
                    //   锁页、解锁、SessionWatcher 也都按它走。以前只 loadUrl，
                    //   于是页面换到外网了，顶上还写着局域网地址。
                    prefs.setEntryUrl(target)
                    prefs.rememberUrl(target)
                    val targetConn = ConnectionStore.all(this).firstOrNull { it.url == target }
                    if (targetConn != null) ConnectionStore.setActive(this, targetConn.id)
                    // ★ 同时把 dshReady 打回 false：换路是一次真正的导航，新页面首帧之前
                    //   屏幕上是空的。不打回 false 就不会出加载遮罩，深色下会看到一片空白
                    //   （浅色主题里更刺眼：整块白）。遮罩底色是主题背景色，两种模式都对。
                    ui = ui.copy(
                        entryUrl = target,
                        activeConnId = targetConn?.id ?: ui.activeConnId,
                        dshReady = false,
                    )
                    ensureThemeCookie(target)
                    // 同一台电脑的解锁凭据在两条通道间通用，换路时搬过去
                    carryUnlockCookie(current, target)
                    // ★ 换网络后连接池里那些 socket 还绑在旧网络上。不清掉的话，
                    //   主文档拦截会先尝试复用它们，一直挂到 readTimeout 才失败 ——
                    //   表现就是「换路了但画面不出来，手动刷新一次才有」。
                    webView.stopLoading()
                    httpClient.dispatcher.cancelAll()
                    httpClient.connectionPool.evictAll()
                    docClient.dispatcher.cancelAll()
                    docClient.connectionPool.evictAll()
                    webView.loadUrl(target, themeHeaders())
                    armAutoReloadAfterSwitch()
                    // ★ 换路必须说一声。以前地址悄悄换掉：会话重连、滚动位置丢了，
                    // 用户只看到页面一抖，以为是网络出问题。
                    val from = channelOf(current).ifEmpty { "当前" }
                    val to = channelOf(target).ifEmpty { "另一条通道" }
                    Toast.makeText(
                        this@MainActivity,
                        "网络变了，已从$from 切到$to：${hostOf(target)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    EventLog.event(this@MainActivity, "route", "switch $from -> $to ${hostOf(target)}")
                } else {
                    reloadKeepingPosition()
                    // ★ 从外网切回 Wi-Fi 时，ConnectivityManager 报上来的「是否在家」会晚一拍：
                    //   这一刻 AutoRoute.pick 仍然选回外网地址，于是分支落进这里，顶部地址
                    //   一格不动（用户实测：内网→外网会变，外网→内网不变）。
                    //   半秒后按当时的网络状态再选一次，仍然选到不同地址就补一次真正的切换。
                    val token = this
                    handler.postDelayed({
                        if (!::webView.isInitialized) return@postDelayed
                        val now = webView.url ?: return@postDelayed
                        if (!now.startsWith("http")) return@postDelayed
                        val again = AutoRoute.pick(token, ConnectionStore.all(token), now)
                        if (again != null && again != now) {
                            val conn = ConnectionStore.all(token).firstOrNull { it.url == again }
                            if (conn != null) {
                                EventLog.event(token, "route", "late pick -> ${hostOf(again)}")
                                switchTo(conn)
                            }
                        }
                    }, 700L)
                }
            }
        }
    }

    override fun onStop() {
        NetWatcher.detach()
        SessionWatcher.onForegroundChanged(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleShortcut(intent)
        maybeHandleShare(intent)
        consumeOpenSession(intent)
    }

    /**
     * 长按图标的快捷方式。
     *
     * 「新会话」和「继续最近」都是往 DSH 页面里注入一次点击/localStorage，
     * 而不是另开一个界面 —— 会话的选与开本来就是 DSH 自己的事，
     * 我们只负责把他送到那个状态。
     */
    private fun maybeHandleShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW_SESSION -> handler.postDelayed({ clickNewSession() }, 600)
            ACTION_RECENT_SESSION -> openMostRecentSession()
            ACTION_OPEN_SETTINGS -> ui = ui.copy(settingsOpen = true)
        }
    }


    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        // 退到后台：审批改由原生接管（前台时页面自己会处理，抢答会打架）
        SessionWatcher.onForegroundChanged(false)
    }

    override fun onDestroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        clearLoadTimeout()
        webViews.values.forEach { runCatching { it.onPause() } }
        // 不碰 webView 字段：url 为空时 activate() 从没跑过，读它就是
        // UninitializedPropertyAccessException —— 池子里的东西循环处理就够了
        webViews.values.forEach { runCatching { it.stopLoading() } }
        webViews.values.forEach { runCatching { it.destroy() } }
        webViews.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * 让某个地址成为当前连接 —— 池子里有就直接用，没有就新建。
     *
     * 切换的全部代价就是换一次可见性（还有一次 onResume/onPause）。
     * 没有整页加载，也就没有重载。
     */
    private fun activate(url: String) {
        // ★ 空地址也要有 WebView —— 这里绝对不能 `if (key.isEmpty()) return`。
        //
        // 那个 early return 是我第一版写的，它引入了一个必崩：全新安装还没有地址时
        // activate() 什么也不做，`webView` 这个 lateinit 就永远没被赋值，
        // 而 Shell(webView = webView, ...) 会立刻读它 → 未初始化异常 → 闪退。
        //
        // 以前的 buildWebView() 是无条件跑的，所以永远有值。改成条件式之后
        // 这个前提就没了 —— 用一张 about:blank 占位，前提就又成立了。
        val key = url.trimEnd('/').ifEmpty { BLANK_PAGE }
        if (::webView.isInitialized) {
            // 先把当前这个停下 —— 不管新地址在不在池里。
            // 一开始这里加了个 `webViews[key] !== null` 的条件，那是错的：
            // 切到一个从没连过的地址时条件不成立，旧的那个就不会被暂停，
            // 它的 JS 和事件流会一直跑在后台。
            runCatching { webView.onPause() }
        }
        val target = webViewFor(key)
        if (!::webView.isInitialized || target !== webView) {
            webView = target
            webHost.removeAllViews()
            webHost.addView(
                target,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        runCatching { target.onResume() }
    }

    /**
     * 取这个地址的 WebView，没有就造一个。
     *
     * 超出上限时淘汰最久没用过的那个 —— 连同它的 Cookie 之外的运行状态一起销毁，
     * 不然内存只增不减。
     */
    private fun webViewFor(url: String): WebView {
        webViews.remove(url)?.let { hit ->
            webViews[url] = hit          // 重新插入到末尾 = 标记为最近使用
            return hit
        }
        val created = newWebView()
        webViews[url] = created
        while (webViews.size > MAX_LIVE_WEBVIEWS) {
            val oldest = webViews.keys.firstOrNull() ?: break
            if (oldest == url) break

            // ★ 正在显示的那个绝不淘汰。
            //
            // 一开始这里写的是「先 remove，如果它是 webView 就 return@let 跳过后面的销毁」——
            // 那是错的：它已经先从 map 里出去了，于是屏幕上还挂着它、池子里却没有了，
            // 下次切回来会**新建一个**，而旧的那个既没人引用也没被销毁。
            // 正确的做法是根本不把它移出去。
            if (::webView.isInitialized && webViews[oldest] === webView) break

            webViews.remove(oldest)?.let { dead ->
                runCatching {
                    dead.onPause()
                    dead.stopLoading()
                    dead.destroy()
                }
            }
        }
        return created
    }

    private fun newWebView(): WebView {
        val v = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // 深色下别先闪一块白：WebView 自己的底色跟系统主题走，
            // 否则页面还没画出来时是一整屏白（夜间看着像卡住了）。
            setBackgroundColor(
                if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                ) 0xFF0B0E14.toInt() else 0xFFF4F6F9.toInt()
            )
        }
        WebView.setWebContentsDebuggingEnabled(true)

        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = true          // 附件上传要读 content://
            // 不覆盖 UA：DSH 按视口宽度决定移动/桌面布局，手机平板各就各位
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)

        v.webViewClient = object : WebViewClient() {

            /**
             * 接管主文档，注入布局覆盖与 polyfill 兜底。
             *
             * 为什么必须在这一层：DSH 决定手机/电脑布局靠的是页面启动时的一次
             * `matchMedia('(max-width: 1023px)')`，页面加载完再改就晚了，
             * 改 CSS 也没用（判定走 JS）。只有在自己的脚本之前把结果换掉才有效。
             *
             * 任何一步不顺利都返回 null，交回 WebView 走原路 ——
             * 布局设置出问题也不能让页面打不开。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (!request.isForMainFrame || request.method != "GET") return null
                val scheme = request.url.scheme
                if (scheme != "http" && scheme != "https") return null
                return runCatching { rewriteMainDocument(request.url) }.getOrNull()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view !== webView) return   // 非活跃的 WebView 不该改当前连接的状态
                // 新的一次导航，先把上一次的失败清掉
                if (ui.pageError != null) ui = ui.copy(pageError = null)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (view !== webView) return
                // 首帧已经画出来了就撤遮罩，不必等子资源全部结束。
                // 移动网络下 onPageFinished 常常比首帧晚 2–5 秒，那几秒里用户看到的
                // 只是转圈，而页面其实早就能点了。
                switchAutoReloadArmed = false
                if (ui.screen == Screen.Dsh && ui.pageError == null && !ui.dshReady) {
                    entryRetried = false
                    ui = ui.copy(dshReady = true)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                // ★ 池子里可能有两个 WebView。非活跃那个的加载结果不能覆盖当前连接的
                //   loaded / pendingSessionId / pageError —— 否则切回去会看到别人的错误。
                if (view !== webView) return
                loaded = true
                clearLoadTimeout()

                // 网络切换后的原地重连：把滚动位置还原，别让用户回到顶部重找会话。
                if (pendingScrollY >= 0) {
                    val y = pendingScrollY
                    pendingScrollY = -1
                    view.evaluateJavascript(
                        "(function(){var y=$y;window.scrollTo(0,y);" +
                            "var s=document.scrollingElement||document.documentElement;" +
                            "if(s){s.scrollTop=y;}})()",
                        null,
                    )
                }

                // 从通知点进来的目标会话：页面刚就绪，正好写 localStorage
                pendingSessionId?.let { pending ->
                    pendingSessionId = null
                    view.evaluateJavascript(setCurrentSessionJs(pending), null)
                    prefs.setLastSessionId(pending)
                    return
                }

                // 整页载入会把 html 的 zoom 冲掉，这里补回来
                view.evaluateJavascript(scaleJs(prefs.scale()), null)
                // 加载失败时**不能**把 dshReady 置回 true —— Chrome 对错误页
                // 同样会调 onPageFinished，置回去会把失败界面撤掉、露出原生错误页。
                if (ui.screen == Screen.Dsh && ui.pageError == null) {
                    ui = ui.copy(dshReady = true, dshError = null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                req: WebResourceRequest,
                err: WebResourceError,
            ) {
                if (view !== webView) return   // 同上：别把另一条连接的错误显示在这边
                if (!req.isForMainFrame) return
                clearLoadTimeout()
                if (ui.screen == Screen.Dsh) {
                    ui = ui.copy(
                        dshReady = false,
                        pageError = err.description.toString(),
                        dshError = err.description.toString(),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                req: WebResourceRequest,
                res: WebResourceResponse,
            ) {
                if (view !== webView) return   // 同上：别把另一条连接的错误显示在这边
                if (!req.isForMainFrame || res.statusCode < 400) return
                clearLoadTimeout()
                if (ui.screen == Screen.Dsh) {
                    ui = ui.copy(pageError = "HTTP ${res.statusCode}")
                    ui = ui.copy(
                        dshReady = false,
                        dshError = when (res.statusCode) {
                            401 -> "登录凭据失效了（电脑上的 dsh web 可能重启过）。点「重新加载」换一份新的。"
                            502, 504 -> "代理连不上 dsh web：电脑上的 dsh web 可能没在运行。"
                            else -> "HTTP ${res.statusCode}"
                        },
                    )
                }
            }
        }

        v.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                // 上一次没回执就作废，否则页面的 input 会一直卡在「已打开」状态
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                // 正好有一张从别的应用分享进来的图？直接把它交出去。
                //
                // 这是「分享进 DSH」的另一半：分享时图片已经落到私有目录，
                // 用户回到 DSH 点「附件」就应该是把那张图发出去，而不是又弹一次
                // 文件选择器让他重新找一遍。
                ShareInbox.consumePendingImage(this@MainActivity)?.let { (file, name) ->
                    // 必须叫这个名字，页面才会把它当图片附件处理
                    val named = File(file.parentFile, name)
                    runCatching { file.renameTo(named) }
                    val target = if (named.exists()) named else file
                    ShareInbox.clearPendingImage(this@MainActivity)
                    filePathCallback = null
                    callback.onReceiveValue(arrayOf(Uri.fromFile(target)))
                    return true
                }

                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    // 没有文件管理器 / 相机：回 null 让页面知道没选到，别把 input 卡死
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // 只拒绝 getUserMedia 那类（摄像头/麦克风实时流）。
                // 附件上传不经过这里 —— 那是 onShowFileChooser 的事。
                request.deny()
            }
        }

        return v
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    /** @param url 来自连接页的输入；为 null 表示沿用已保存的地址。 */
    private fun connect(url: String?) {
        // 已经在同一条地址上、页面也出来了：不要再走一遍握手+导航。
        // 换路回调、自动重连、点卡片命中的「其实还是同一条」都会走到这里，
        // 以前每次都 `loadUrl` —— 用户看到的就是「页面明明出来了又自己刷一次」。
        val same = url?.let { Prefs.normalize(it) }.orEmpty()
        if (same.isNotEmpty() && same == prefs.entryUrl() &&
            loaded && ui.screen == Screen.Dsh && ui.dshReady
        ) {
            EventLog.event(this, "route", "connect skipped: already on ${hostOf(same)}")
            return
        }

        // 连上就把这条地址收进列表 —— 别让首页空着（见 ConnectionStore.ensure）
        url?.let { ConnectionStore.ensure(this, it) }
        if (url != null) {
            val normalized = Prefs.normalize(url)
            if (normalized.isEmpty()) {
                ui = ui.copy(setupError = "地址格式不对，例：http://192.168.1.100:3081")
                return
            }
            val changed = prefs.entryUrl() != normalized
            prefs.setEntryUrl(normalized)
            ui = ui.copy(entryUrl = prefs.entryUrl())

            // ★ 换了地址就得让 SessionWatcher 跟上。
            //
            // 它是个单例、只跟一个 url（= prefs.entryUrl()）。切到另一台电脑后
            // 如果不重开，它会继续盯着**旧那台** —— 通知和审批都会来自错的机器。
            // 这是在加多连接常驻时才注意到的：切换以前只发生在设置页改地址，
            // 现在首页点一下就能切，这个漏洞就露出来了。
            if (changed && prefs.notifyEnabled()) {
                SessionWatcher.stop()
                startWatching()
            }
        }

        val entry = prefs.entryUrl()
        if (entry.isEmpty()) {
            ui = ui.copy(screen = Screen.Setup, connecting = false)
            return
        }

        loaded = false
        ui = ui.copy(
            screen = Screen.Connecting,
            connecting = true,
            setupError = null,
            dshError = null,
        )

        Thread({
            val result = Handshake.run(entry, false)
            handler.post { onHandshake(entry, result) }
        }, "dsh-handshake").start()
    }

    private fun onHandshake(entry: String, result: Handshake.Result) {
        val cm = CookieManager.getInstance()
        if (!result.ok) {
            cm.removeAllCookies(null)
            cm.flush()
            ui = ui.copy(
                screen = Screen.Setup,
                connecting = false,
                setupError = buildString {
                    append(if (result.status == 0) "连不上电脑" else "电脑拒绝了这次连接")
                    if (result.message.isNotEmpty()) append(" · ").append(result.message)
                },
            )
            return
        }
        result.cookies.forEach { cm.setCookie(entry, it) }
        cm.flush()

        // 握手成功 = 这个地址确实能用，记下来供以后自动选路
        prefs.rememberUrl(entry)

        ui = ui.copy(
            screen = Screen.Dsh,
            connecting = false,
            dshReady = false,
            dshError = null,
        )

        // ★ 先过访问闸门，再加载页面。
        //
        // 宿主（插件）在转发端口上加了密码闸门：没解锁的话，注入启动 token 那一步
        // 会被挡下，页面拿到的是密码页或 401。App 这一侧的正确顺序是
        // 「握手 → 解锁 → 带 Cookie 加载」，顺序反了会先闪一下密码页。
        gateThenLoad(entry)

        // 通知开着的话，握手之后把事件流（重新）接上。
        //
        // 为什么必须在这里做：SessionWatcher 把 baseUrl 用闭包捕获了，改了入口
        // 地址之后那条流还连着旧机器 —— 界面显示新地址、诊断里却报旧 IP（实测踩过）。
        // SessionWatcher.start 现在会识别地址变化并重开，但前提是**有人再调它一次**，
        // 而改地址走的正是 connect → 握手，这里就是那个该调的地方。
        if (prefs.notifyEnabled()) startWatching()

        // 后台查一次更新（内部有 24 小时节流，失败静默）
        checkUpdate()

        // 重新加载时顺手看看地址还用不用得上（不重复注册网络回调）
        autoPickAddress()

        startLoadTimeout()
        ensureThemeCookie(entry)
        webView.loadUrl(entry, themeHeaders())
    }

    /**
     * 重新加载 DSH 界面。
     *
     * 单纯 `reload()` 不够：电脑上重启过 dsh web 之后旧 cookie 已经作废，页面只会拿到 401。
     * 所以先走一遍原生握手换新凭据，再加载。
     */
    private fun reloadDsh() {
        val entry = prefs.entryUrl()
        if (entry.isEmpty()) return

        ui = ui.copy(screen = Screen.Dsh, dshReady = false, dshError = null)
        startLoadTimeout()

        Thread({
            // 宿主开了访问密码、而这台设备还没解锁时，普通握手是拿不到凭据的，
            // 页面只会一直转圈。所以先问一句要不要解锁，要就切到解锁页，
            // 让用户输密码 / 过生物识别，而不是在 DSH 界面里等到超时。
            val needUnlock = runCatching { kotlinx.coroutines.runBlocking { UnlockClient.required(entry) } }.getOrDefault(false)
            if (needUnlock) {
                handler.post { gateThenLoad(entry) }
                return@Thread
            }
            val result = Handshake.run(entry, false)
            handler.post {
                if (result.ok) {
                    val cm = CookieManager.getInstance()
                    result.cookies.forEach { cm.setCookie(entry, it) }
                    cm.flush()
                    ensureThemeCookie(entry)
                    webView.loadUrl(entry, themeHeaders())
                } else {
                    // 握手失败就退回普通 reload，至少让页面自己把错误显示出来
                    webView.reload()
                }
            }
        }, "dsh-rehandshake").start()
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        loaded = false
        ui = ui.copy(
            screen = Screen.Setup,
            settingsOpen = false,
            connecting = false,
            setupError = null,
            dshReady = false,
            dshError = null,
        )
    }

    // ------------------------------------------------------------------
    // 会话完成通知
    // ------------------------------------------------------------------

    /** 通知/启动意图里带的会话 id。 */
    private fun consumeOpenSession(intent: Intent?) {
        val id = intent?.getStringExtra(NotificationCenter.EXTRA_SESSION_ID)
        if (id.isNullOrEmpty()) return
        intent.removeExtra(NotificationCenter.EXTRA_SESSION_ID)
        openSessionFromNotice(id)
    }

    /** 跳到指定会话。 */
    private fun openSessionFromNotice(sessionId: String) {
        // 已经在看这个会话：直接回到前台，**不要**再动加载状态。
        // 之前是先置 dshReady = false 再判断 same，于是「同一个对话重复进入」时
        // 既不 reload 也永远等不到 onPageFinished —— 遮罩一直转，只能手动刷新才回来。
        if (loaded && prefs.lastSessionId() == sessionId) {
            ui = ui.copy(panelOpen = false, screen = Screen.Dsh, dshError = null)
            return
        }
        ui = ui.copy(panelOpen = false, screen = Screen.Dsh, dshReady = false, dshError = null)
        if (loaded) {
            // 页面已就绪但换了一个会话：写 localStorage 再 reload
            webView.evaluateJavascript(setCurrentSessionJs(sessionId), null)
            prefs.setLastSessionId(sessionId)   // 供「继续最近」用
        } else {
            // 冷启动还没载入 —— localStorage 要等 origin 就绪才写得进去
            pendingSessionId = sessionId
            // 关键：这里必须真发起一次加载，否则遮罩会一直等一个从没发出去的请求，
            // 连 12 秒超时都不会触发（startLoadTimeout 也没启动）。
            // 走 gateThenLoad 而不是 loadDsh，是为了先问「要不要解锁」——
            // 之前点刷新才弹解锁页，就是因为只有刷新那条路做了这个检查。
            val entry = prefs.entryUrl()
            if (entry.isEmpty()) {
                ui = ui.copy(screen = Screen.Setup, dshReady = false)
            } else {
                startLoadTimeout()
                gateThenLoad(entry)
            }
        }
    }

    /**
     * 打开指定会话。
     *
     * ## 为什么是「写 localStorage + 整页重载」
     *
     * DSH 前端**没有 URL 路由**（实测无 pushState / URLSearchParams），会话状态
     * 只活在内存里，所以这是唯一可靠的深链方式。
     *
     * ## 试过「在页面里点那张卡片」，放弃了
     *
     * 机制本身成立 —— 实测点一张已渲染的卡片，加载次数 1 → 1，不重载。
     * 但**卡片的渲染时机拿不到**：会话列表是虚拟滚动的，卡片只有在滚到可见位置时
     * 才会进 DOM —— 实测展开 47 个会话后 DOM 里也只有 0～1 张。于是按标题找卡片
     * 基本必然落空。
     *
     * 落空之后只能回退到重载，而回退前那段等待（1.2 秒）**比原来的直接重载还慢**。
     * 一个大部分时候不生效、生效时也谈不上快的优化，不如不做。
     *
     * 保留这个判断的代价是每次进会话多等一秒多，收益却不稳定 —— 所以退回原样。
     */
    private fun setCurrentSessionJs(sessionId: String): String {
        val q = org.json.JSONObject.quote(sessionId)
        return "(function(){try{localStorage.setItem('dsh.sessions.current'," +
            "JSON.stringify({sessionId:$q}));location.reload();}catch(e){}})();"
    }

    /** 把当前系统的深浅色带给 WebView（header 由代理注入成页面里的 matchMedia 补丁）。 */
    private fun isDarkNow(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * 把上一条通道的解锁凭据带到新通道。
     *
     * 解锁 cookie（`dshgo-unlock`）是**按主机名**发的：局域网 `192.168.3.21` 和
     * Tailscale `100.75.57.100` 在浏览器看来是两个站点，各存各的。于是换路之后
     * 明明还是同一台电脑、同一个密码，却又要输一遍 —— 用户实测反馈就是这个。
     *
     * 两条通道指向的是同一台电脑（设备卡按 deviceId 合并过），凭据本来就该通用，
     * 所以换路时把旧主机的那张 cookie 原样复制到新主机。
     */
    private fun carryUnlockCookie(from: String, to: String) {
        if (from.isEmpty() || to.isEmpty() || from == to) return
        runCatching {
            val cm = CookieManager.getInstance()
            val hit = cm.getCookie(from).orEmpty()
                .split(';')
                .map { it.trim() }
                .firstOrNull { it.startsWith("$UNLOCK_COOKIE=") }
                ?: return
            cm.setCookie(to, "$hit; path=/; max-age=${30 * 24 * 60 * 60}")
            cm.flush()
        }
    }

    private fun themeHeaders(): Map<String, String> =
        mapOf("X-Fnh-Theme" to if (isDarkNow()) "dark" else "light")

    /**
     * 主题同时写一份 cookie。
     *
     * 只发 header 不够：宿主 303 跳到 `/` 时 WebView 不会把自定义头带过去，
     * 于是首屏深色、跳一下又变回浅色（用户实测就是「进入 dsh 后还是浅色」）。
     * cookie 会跟着后续所有请求走，代理优先读 header、读不到再读 cookie。
     */
    private fun ensureThemeCookie(entry: String) {
        if (entry.isEmpty()) return
        try {
            val cm = CookieManager.getInstance()
            cm.setCookie(entry, "fnh_theme=${if (isDarkNow()) "dark" else "light"}; path=/")
            cm.flush()
        } catch (_: Throwable) {
            // 写 cookie 失败不影响加载，header 那条路还在
        }
    }

    /** 上次看到的深浅色（UI_MODE_NIGHT_MASK 的结果），用来区分「换主题」和「换尺寸」。 */
    private var lastUiMode = -1

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统切深色/浅色时 DSH 页面必须重新取一次，否则它还按旧主题渲染。
        //
        // 但清单里也声明了 screenSize/screenLayout（分屏、折叠屏开合都会走这里），
        // 以前这里**无条件** loadUrl：一开合就把整页刷掉，用户看到「界面尺寸一变就整个重载」。
        // 只有深浅色真的变了才重载，尺寸变化交给 WebView 自己重排。
        val mode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val themeChanged = lastUiMode != -1 && lastUiMode != mode
        lastUiMode = mode
        if (!themeChanged) return
        val entry = prefs.entryUrl()
        if (entry.isEmpty() || !loaded) return
        EventLog.event(this, "config", "uiMode -> $mode; reload for theme")
        ensureThemeCookie(entry)
        webView.loadUrl(entry, themeHeaders())
    }

    private fun startWatching() {
        val url = prefs.entryUrl()
        if (url.isEmpty()) return
        SessionWatcher.start(this, url)
        WatchService.start(this)
    }

    private fun setNotify(enabled: Boolean) {
        prefs.setNotifyEnabled(enabled)
        // 一起算上 notifyProblem：开关刚打开时最需要知道「到底能不能响」
        ui = ui.copy(notifyEnabled = enabled, notifyProblem = notifyProblem())
        if (enabled) {
            startWatching()
        } else {
            SessionWatcher.stop()
            WatchService.stop(this)
        }
    }

    /**
     * 通知现在能不能真的响。
     *
     * 三种失败各有各的修法，所以分开说：
     *   · 没授权        → 引导去系统设置给权限（Android 13+）
     *   · 渠道被静音    → 去系统通知设置里把「会话完成」调回默认或更高
     *   · 都没问题      → null
     *
     * 之所以要显示出来：通知不开是**静默失败**的 —— 用户只会觉得「怎么不响」，
     * 而代码里没有任何地方会告诉他为什么。
     */
    private fun notifyProblem(): String? {
        if (!prefs.notifyEnabled()) return null
        if (!NotificationCenter.granted(this)) {
            return "系统还没给通知权限 —— 点「系统设置」打开；开了之后回到这里点「发条测试通知」验证。"
        }
        // 具体缺哪一项（横幅/铃声/震动）由它说 —— 只说"静音"用户不知道开哪个开关
        return NotificationCenter.channelProblem(this)
    }

    /** 发一条测试通知，让用户当场确认能响。 */
    private fun onTestNotify() {
        val ok = NotificationCenter.postTest(this)
        ui = ui.copy(
            notifyProblem = if (ok) null else "没能发出通知 —— 先看系统有没有给通知权限。",
        )
    }

    /**
     * 语音输入。
     *
     * ## 为什么不用 ACTION_RECOGNIZE_SPEECH
     *
     * 那条路是"让系统弹一个识别界面"，但它要求设备上**有 Activity 处理这个 Intent**。
     * 实测 ColorOS 上没有 —— 语音能力被做进了输入法，不暴露系统入口，
     * 于是 `launch` 直接抛 ActivityNotFoundException，用户看到的是
     * "这台设备没有语音识别服务"，而手机明明有。
     *
     * 改成 [SpeechRecognizer]：它直接找系统里的 `RecognitionService`，
     * 不依赖有没有那个界面。代价是要 `RECORD_AUDIO` 权限（原来不需要），
     * 值得 —— 能用比少一个权限重要。
     *
     * 实在连识别服务都没有时，才回退到系统界面那条路。
     */
    private fun onVoice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // 兜底：连识别服务都没有，再试系统界面（有些设备只提供后者）
            toast("这台设备没有语音识别服务，可以先用键盘上的麦克风")
            return
        }
        runCatching {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle?) {
                        // 边说边写：用户能看见自己正在说什么，说错了可以立刻停
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { writeToComposer(it) }
                    }

                    override fun onResults(results: Bundle?) {
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { writeToComposer(it) }
                            ?: toast("没听清，再试一次")
                    }

                    override fun onError(error: Int) {
                        // 不把错误码直接丢给用户 —— 翻译成人话
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> "没听清，再试一次"
                            SpeechRecognizer.ERROR_AUDIO -> "麦克风被占用或不可用"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            -> "识别服务需要联网，检查一下网络"
                            else -> "语音识别失败（$error）"
                        }
                        toast(msg)
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
            recognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
            toast("请说话…")
        }.onFailure { toast("打不开语音识别：${it.message}") }
    }

    /** 把识别到的文字写进 DSH 的输入框。 */
    private fun writeToComposer(text: String) {
        if (text.isBlank()) return
        webView.evaluateJavascript(ComposerWriter.writeJs(text)) { result ->
            // JS 回的是带引号的字符串字面量
            if (result != null && !result.contains("ok")) {
                Log.w(TAG, "写入输入框失败：$result")
                toast("没找到 DSH 的输入框，先把页面刷新一下")
            }
        }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 从已知地址里挑一个能用的。
     *
     * 规则刻意保守：**当前地址能用就什么都不做。** 只在它连不上时才切到最快的
     * 可用地址 —— 用户可能正在输入，无端换地址（进而整页重载）比慢一点烦人得多。
     *
     * 全都探测失败时也什么都不做：那说明电脑那边没起来，改地址没用，
     * 改错了反而更找不回来。
     */
    private fun autoPickAddress() {
        // 手动选过就不插手：见 manualRoute() 的说明。
        if (manualRoute()) return
        val known = prefs.knownUrls()
        if (known.size < 2) return
        lifecycleScope.launch {
            val reachable = AddressPicker.probeAll(known)
            if (reachable.isEmpty()) return@launch
            val current = prefs.entryUrl()
            if (reachable.any { it.url == current }) return@launch   // 现在这个能用，不动
            val best = reachable.first()
            Log.i(TAG, "自动切换地址：$current → ${best.url}（${best.ms}ms，共 ${known.size} 个候选）")
            connect(best.url)
        }
    }

    /** 网络变化时重新探一遍 —— WiFi ↔ 蜂窝切换后原来的地址往往就不通了。 */
    private fun watchNetwork() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // 系统刚切换网络，底层的地址很可能已经变了。
                    // 这也是唯一让「手动选择」失效的时机：换网络了，原来的选择不再有意义。
                    setManualRoute(false)
                    handler.postDelayed({ autoPickAddress() }, 1500)
                }
            })
        }
    }

    /**
     * 检查有没有新版本。
     *
     * 走后台线程、短超时，**失败一律静默** —— 检查更新不是主功能，
     * 国内直连 GitHub 时常不通，不该因此让界面出现报错或卡顿。
     */
    private fun checkUpdate(force: Boolean = false) {
        if (force) ui = ui.copy(updateChecking = true)
        Thread({
            val r = UpdateCheck.check(this, BuildConfig.VERSION_NAME, force)
            handler.post {
                ui = ui.copy(
                    updateLatest = r.latest,
                    updateNewer = r.newer,
                    updateApkUrl = r.apkUrl,
                    updateChecking = false,
                )
            }
        }, "dsh-update-check").start()
    }

    /**
     * 应用内下载并安装新版本。
     *
     * 为什么不再跳浏览器：跳过去之后用户要在浏览器的下载列表里找那个 apk、
     * 点开、确认 —— 中间隔着一个我们看不见的界面，出问题也没法提示。
     * 放在应用内能显示进度、失败能说清原因、下完直接进安装器。
     *
     * 装完**不可能**由我们自动打开新版本：系统会杀掉旧进程。真正的"重启"
     * 是安装器上的「打开」按钮；另外 [UpdateInstalledReceiver] 会在新版本
     * 启动时补一条通知。
     */
    private fun startUpdateDownload(url: String) {
        if (ui.updatePhase == "downloading") return
        val target = url.ifEmpty { UpdateCheck.APK_URL }
        ui = ui.copy(updatePhase = "downloading", updateBytes = 0, updateTotal = 0, updateError = null)

        lifecycleScope.launch {
            fun report(p: ApkDownloader.Progress) {
                handler.post { ui = ui.copy(updateBytes = p.bytes, updateTotal = p.total) }
            }

            // ★ 先交给系统下载器：它走手机默认网络（用户开的代理 / VPN 照常生效），
            //   不受本进程「隧道内 DNS」的影响，还带断点续传 —— 用户反馈的
            //   「开着 VPN 时 App 内下载一直 0 字节」就是后者导致的。
            var firstError: String? = null
            val sysId = ApkDownloader.enqueueSystem(this@MainActivity, target)
            if (sysId != null) {
                runCatching { ApkDownloader.awaitSystem(this@MainActivity, sysId, ::report) }
                    .onFailure { firstError = it.message ?: "系统下载失败" }
            } else {
                firstError = "系统下载器不可用"
            }

            if (ApkDownloader.existing(this@MainActivity)?.length()?.let { it > 0 } != true) {
                // 系统下载没成：退回自带实现，再试一次同一个地址
                runCatching {
                    ApkDownloader.download(this@MainActivity, target, ::report)
                }.onFailure {
                    ui = ui.copy(
                        updatePhase = "failed",
                        updateError = listOfNotNull(firstError, it.message).joinToString("；"),
                    )
                    return@launch
                }
            }

            val apk = ApkDownloader.existing(this@MainActivity)
            if (apk == null) {
                ui = ui.copy(updatePhase = "failed", updateError = firstError ?: "下载失败")
                return@launch
            }
            ui = ui.copy(updatePhase = "ready")
            launchInstaller(apk)
        }
    }

    /**
     * 唤起系统安装器。
     *
     * 没被允许"安装未知应用"时先跳去授权页 —— 不然用户点了安装什么都不会发生，
     * 而系统连个提示都不给，那是这个流程里最容易卡住的一步。
     */
    private fun launchInstaller(apk: java.io.File) {
        if (!UpdateInstaller.canInstall(this)) {
            toast("先允许「安装未知应用」，回来再点一次安装")
            runCatching { startActivity(UpdateInstaller.unknownSourcesSettings(this)) }
                .onFailure { runCatching { startActivity(UpdateInstaller.appDetailsSettings(this)) } }
            return
        }
        if (!UpdateInstaller.install(this, apk)) {
            ui = ui.copy(updatePhase = "failed", updateError = "打不开系统安装器")
        }
    }

    /**
     * 打开 APK 下载页（交给浏览器，App 自己不下载、不装）。
     *
     * 默认用镜像 —— GitHub 国内经常打不开。用户真要官方包时，设置里另有入口。
     */
    private fun openDownload(url: String) {
        // 空串 = 走官方地址（镜像可能因为索引延迟还没有新版本）
        val target = url.ifEmpty { UpdateCheck.APK_URL }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 问宿主要不要解锁；要就弹界面，不要就直接加载。
     */
    private fun gateThenLoad(entry: String) {
        // ★ 直连快通道：本机已经有这个地址的解锁 Cookie 时，跳过 /auth/status 那一跳。
        //
        // 之前无论如何都先问一次「要不要解锁」，多一次往返、解锁页还会闪一下。
        // Cookie 还在就说明宿主不会再拦，直接加载页面；万一它其实已经失效，
        // 页面 401 会走原有的自愈路径重新弹解锁页 —— 两边的行为是一致的。
        val cookie = CookieManager.getInstance().getCookie(entry).orEmpty()
        val unlocked = cookie.split(';').any {
            it.trim().startsWith(UnlockClient.COOKIE_NAME + "=")
        }
        if (unlocked) {
            loadDsh(entry)
            return
        }
        lifecycleScope.launch {
            val required = UnlockClient.required(entry)
            if (!required) {
                loadDsh(entry)
                return@launch
            }
            ui = ui.copy(
                screen = Screen.Locked,
                lockStage = "prompt",
                lockError = null,
                lockDeviceMethod = AppLock.describe(this@MainActivity),
                lockHasStored = AppLock.hasPassword(this@MainActivity),
            )
            tryUnlock()
        }
    }

    /** 有存过的密码就先过生物识别；没有就问用户。 */
    private fun tryUnlock() {
        val stored = AppLock.loadPassword(this)
        if (stored == null) {
            ui = ui.copy(lockStage = "password", lockError = null)
            return
        }
        when (AppLock.method(this)) {
            // 设备既没有生物识别也没有设备密码 —— 本地无从校验，只能用存下的密码解锁。
            // 安全边界仍在宿主那边（密码不对照样进不去），这里只是少一道本地门。
            AppLock.Method.None -> submitPassword(stored)
            else -> promptBiometric { submitPassword(stored) }
        }
    }

    /** 拉起系统生物识别 / 设备密码。 */
    private fun promptBiometric(onSuccess: () -> Unit) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val callback = object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: androidx.biometric.BiometricPrompt.AuthenticationResult,
            ) {
                onSuccess()
            }

            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                // 用户主动取消不算错误，别在界面上留红字
                val cancelled = code == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED ||
                    code == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == androidx.biometric.BiometricPrompt.ERROR_CANCELED
                ui = ui.copy(
                    lockStage = "prompt",
                    lockError = if (cancelled) null else msg.toString(),
                )
            }
        }

        runCatching {
            val prompt = androidx.biometric.BiometricPrompt(this, executor, callback)
            val builder = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(AppLock.promptTitle(this))
                .setSubtitle(AppLock.promptSubtitle())
                .setAllowedAuthenticators(AppLock.authenticators(this))

            // 只允许设备密码时**不能**设 negativeButtonText ——
            // 两者同时存在会抛 IllegalArgumentException（BiometricPrompt 的硬性约束）。
            // 用 authenticators() 判断而不是 method()：只支持弱生物识别时，
            // 传 STRONG 进去会立刻报错，用户点了什么也不发生。
            if (AppLock.authenticators(this) !=
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) {
                builder.setNegativeButtonText("取消")
            }
            prompt.authenticate(builder.build())
        }.onFailure {
            // 没有指纹硬件、被策略禁用等等 —— 退到手输密码，而不是卡住
            ui = ui.copy(lockStage = "password", lockError = "这台设备用不了生物识别：${it.message}")
        }
    }

    /** 重新读连接列表并探测一遍。 */
    /** 冷启动「首次探测全灭」只补探一次，避免网络没起来时来回打转。 */
    private var warmRetryDone = false

    private fun refreshConnections() {
        val list = ConnectionStore.all(this)
        ui = ui.copy(connections = list, activeConnId = ConnectionStore.activeId(this))
        if (list.isEmpty()) return
        ui = ui.copy(probing = true)
        lifecycleScope.launch {
            // 把 WebView 里已有的 Cookie 带上再探：已经解锁过的电脑会直接回 200，
            // 列表就显示「可以连接 · 在线」，而不是写着「需要密码登录」、点进去却能直开。
            val cm = CookieManager.getInstance()
            val cookieOf: (String) -> String? = { url ->
                runCatching { cm.getCookie(url) }.getOrNull()
            }
            val statuses = runCatching {
                ConnectionProbe.probeAll(list, cookieOf)
            }.getOrDefault(emptyMap())
            // R11 取证：列表说「需要解锁」但点进去能直开，说明「探测看到的身份」与
            // 「WebView 实际状态」不一致。把每条的 级别 / 是否带 Cookie / 耗时 记进事件日志，
            // 下次复现时能直接从日志判断是「Cookie 没读到」还是「插件不认 401 之外的路径」。
            statuses.forEach { (url, st) ->
                val hadCookie = runCatching { !cookieOf(url).isNullOrBlank() }.getOrDefault(false)
                EventLog.event(
                    this@MainActivity,
                    "probe",
                    "${hostOf(url)} level=${st.level} ms=${st.ms} cookie=$hadCookie id=${st.deviceId.ifEmpty { "-" }}",
                )
            }
            // 电脑换了 Wi-Fi / 路由器换了网段 → 局域网地址会变。
            // 原地改地址，而不是让列表里多出一台「新设备」。
            // 记住这次探到的设备身份：出门时局域网那条探不到，靠它才能和 Tailscale
            // 那条并成同一台电脑（否则列表里永远是两台）。
            val learned = runCatching { ConnectionStore.rememberDeviceIds(this@MainActivity, statuses) }
                .getOrDefault(0)
            val moved = runCatching { ConnectionStore.reconcileDevices(this@MainActivity, statuses) }
                .getOrDefault(0)
            if (moved > 0) {
                EventLog.event(this@MainActivity, "conn", "address moved x$moved")
                Toast.makeText(
                    this@MainActivity,
                    "有 $moved 条连接地址变了，已自动更新",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            val refreshed = if (moved > 0 || learned > 0) ConnectionStore.all(this@MainActivity)
            else ui.connections
            ui = ui.copy(
                connStatuses = statuses,
                probing = false,
                connections = refreshed,
            )
            maybeRebuildTunnel(statuses)
            // 冷启动时网络栈可能还没就绪：第一次探测全灭会让列表闪一下「断联」，
            // 而之后没有任何东西再触发探测（用户 21:02 报的第三个问题）。
            // 全灭就等 2.5 s 再探一次，只补一次，避免打转。
            val allDown = statuses.isNotEmpty() &&
                statuses.values.all { it.level == ConnectionProbe.Level.Offline }
            if (allDown && !warmRetryDone) {
                warmRetryDone = true
                EventLog.event(this@MainActivity, "conn", "all offline on first probe; retry in 2.5s")
                webHost.postDelayed({ refreshConnections() }, 2_500L)
            } else if (!allDown) {
                warmRetryDone = false
            }
            // R17：地址变了但用户还停在旧地址上时，自动跟到新的局域网地址。
            // 只有在「同一台电脑（deviceId 相同）+ 现在确实能连」时才切，
            // 免得把用户从能用的通道上踢下去。
            if (moved > 0) {
                val active = refreshed.firstOrNull { it.id == ui.activeConnId }
                    ?: refreshed.firstOrNull { it.url == ui.entryUrl }
                val better = refreshed.firstOrNull { c ->
                    c.deviceId.isNotBlank() && c.deviceId == active?.deviceId &&
                        c.url != ui.entryUrl &&
                        AutoRoute.isLanUrl(c.url) &&
                        AutoRoute.onWifi(this@MainActivity) &&
                        statuses[c.url]?.level == ConnectionProbe.Level.Online
                }
                if (better != null) {
                    EventLog.event(
                        this@MainActivity,
                        "route",
                        "address moved; follow ${hostOf(better.url)}",
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "局域网地址变了，已切到 ${hostOf(better.url)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    switchTo(better)
                }
            }
        }
    }

    /**
     * 探测发现「Tailscale 那条不通」时，如果用户本来就想用隧道（开过且没关），
     * 而系统里没有别的 VPN 占着唯一那条槽位，就自动重建一次。
     *
     * 触发点选在**探测之后**：这正是「检查连接状态」那一刻，用户期待它自愈。
     * 60 秒节流，避免每次刷新都弹系统授权。
     */
    private fun maybeRebuildTunnel(statuses: Map<String, ConnectionProbe.Status>) {
        if (!TsTunnel.wantsTunnel(this)) return
        if (TsTunnel.tunUp()) return
        val tailDead = statuses.entries.any { (url, st) ->
            url.contains("100.") && st.level == ConnectionProbe.Level.Offline
        }
        if (!tailDead) return
        val now = System.currentTimeMillis()
        if (now - lastTunnelRebuildTry < 60_000L) return
        lastTunnelRebuildTry = now
        EventLog.event(this, "route", "tail dead; auto rebuild tunnel")
        runCatching { TsTunnel.start(this) }
            .onFailure { EventLog.event(this, "route", "auto rebuild failed: ${it.message}") }
    }

    /**
     * 切到另一条连接。
     *
     * 就是改入口地址再走一遍握手 —— 换地址要重连这件事在 3.6.5 已经修过
     * （事件流会把地址闭包捕获，不重开就一直连着旧的那台）。
     */
    private fun switchTo(conn: Connection) {
        // 手动选择优先：记下来，后台探测别再把地址改回去。
        setManualRoute(true)
        ConnectionStore.markUsed(this, conn.id)
        prefs.setEntryUrl(conn.url)
        prefs.rememberUrl(conn.url)
        ui = ui.copy(activeConnId = conn.id)
        connect(conn.url)
    }

    /** 保存新增或编辑后的连接；改的是当前那条就顺势重连。 */
    private fun saveConnection(conn: Connection) {
        val saved = ConnectionStore.upsert(this, conn)
        if (saved.url == prefs.entryUrl()) {
            // 地址被改了，当前这条得重连
            switchTo(saved)
        } else {
            refreshConnections()
        }
    }

    private fun deleteConnection(conn: Connection) {
        ConnectionStore.remove(this, conn.id)
        val wasActive = prefs.entryUrl() == conn.url
        prefs.forgetUrl(conn.url)
        if (wasActive) {
            // 删掉的正是当前这条 —— 退到列表里剩下的一条
            val next = ConnectionStore.active(this)
            if (next != null) switchTo(next) else ui = ui.copy(screen = Screen.Setup)
        }
        refreshConnections()
    }

    /** 换解锁方式。选「每次输密码」时 AppLock 会顺手清掉本机存的那份。 */
    private fun setLockMode(id: String) {
        AppLock.setMode(this, AppLock.Mode.from(id))
        ui = ui.copy(
            lockModeState = AppLock.mode(this).id,
            lockDevice = AppLock.describe(this),
        )
    }

    /** 把密码交给宿主，拿到解锁 Cookie 后转存进 WebView 的 CookieManager。 */
    private fun submitPassword(password: String) {
        ui = ui.copy(lockStage = "busy", lockError = null)
        lifecycleScope.launch {
            when (val r = UnlockClient.unlock(prefs.entryUrl(), password)) {
                is UnlockClient.Unlock.Ok -> {
                    val cm = CookieManager.getInstance()
                    cm.setCookie(prefs.entryUrl(), r.cookie)
                    cm.flush()
                    AppLock.savePassword(this@MainActivity, password)
                    ui = ui.copy(screen = Screen.Dsh, lockError = null)
                    loadDsh(prefs.entryUrl())
                }
                UnlockClient.Unlock.NotRequired -> {
                    ui = ui.copy(screen = Screen.Dsh, lockError = null)
                    loadDsh(prefs.entryUrl())
                }
                UnlockClient.Unlock.BadPassword -> {
                    // 本机存的那份已经不对了（宿主改过密码）—— 清掉再问
                    AppLock.clear(this@MainActivity)
                    ui = ui.copy(lockStage = "password", lockError = "密码不对，再试一次")
                }
                is UnlockClient.Unlock.Failed ->
                    ui = ui.copy(lockStage = "password", lockError = r.message)
            }
        }
    }

    /** 真正把页面载进来（原来直接写在 onHandshake 里的那段）。 */
    private fun loadDsh(entry: String) {
        // activate() 会把 entryUrl 换成新地址，先记下原来那条 —— 解锁凭据要跨通道搬。
        val priorEntry = prefs.entryUrl()
        // ★ 先切到这条连接的 WebView，再决定要不要导航。
        //
        // 把 activate() 提到最前面、并且**先判断复用再置 dshReady**，是因为这里踩过一个坑：
        // 复用一个已经载好的 WebView 时我们**不发任何导航**（这正是「秒切」的实现方式），
        // 但 dshReady 已经被置成 false 了 —— 而把它置回 true 的只有 onPageFinished，
        // 没有导航就永远不会触发。
        //
        // 症状：切换后遮罩一直转，等到超时显示「界面加载超时」，用户点「重新加载」才成功
        // （那条路真的会 loadUrl，于是 onPageFinished 来了）。
        // 实测反馈：「切换后加载失败，要再次重新加载才会成功」—— 就是这个。
        activate(entry)
        carryUnlockCookie(priorEntry, entry)

        val alive = webView.url?.startsWith("http") == true
        if (!alive || prefs.entryUrl() != entry) {
            // 新造的、或者地址变了：真真正正导航一次
            ui = ui.copy(screen = Screen.Dsh, dshReady = false, dshError = null)
            startLoadTimeout()
            ensureThemeCookie(entry)
            webView.loadUrl(entry, themeHeaders())
            armEntryRetry(entry)
            return
        }

        // 复用：页面已经在里面了，**直接进**。没有导航，也就不会有 onPageFinished，
        // 所以这里得自己把它标成就绪 —— 否则遮罩会一直转到超时。
        clearLoadTimeout()
        loaded = true
        ui = ui.copy(
            screen = Screen.Dsh,
            dshReady = true,
            dshError = null,
            pageError = null,
        )
        // 页面没重载，但缩放仍要跟当前设置对齐一次
        webView.evaluateJavascript(scaleJs(prefs.scale()), null)
    }

    /**
     * 请系统把小组件钉到桌面。
     *
     * 小组件在桌面上是"用户自己去找"的东西 —— 系统把入口藏在长按桌面 →
     * 小组件 → 找到本应用 里面，很多人根本不知道。给一个直达入口是常规做法。
     *
     * API 26+ 才有 `requestPinAppWidget`；更早的版本只能提示用户手动加。
     */
    private fun pinWidget() {
        val mgr = android.appwidget.AppWidgetManager.getInstance(this)
        if (!mgr.isRequestPinAppWidgetSupported) {
            toast("这台设备不支持自动添加 —— 长按桌面 → 小组件 → 找到「Fnh Go」")
            return
        }
        val ok = mgr.requestPinAppWidget(
            android.content.ComponentName(this, com.dshgo.app.notify.DshWidgetProvider::class.java),
            null,
            null,
        )
        if (!ok) toast("请长按桌面 → 小组件 → 找到「Fnh Go」")
    }

    /**
     * 打开系统通知设置。
     *
     * 优先**直达渠道页**（`EXTRA_CHANNEL_ID`）—— 用户进去就看到「会话完成」
     * 那一条，横幅/响铃/震动三个开关就在眼前。国产 ROM 上这条路径未必实现，
     * 所以失败时回退到应用级通知设置。
     */
    private fun onNotifySettings() {
        runCatching { startActivity(NotificationCenter.channelSettingsIntent(this)) }
            .onFailure {
                runCatching { startActivity(NotificationCenter.settingsIntent(this)) }
            }
    }

    /** 开启通知：Android 13+ 先要运行时授权，其它版本直接开。 */
    private fun requestNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationCenter.granted(this)
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setNotify(true)
        }
    }

    // ------------------------------------------------------------------
    // 缩放 / 布局 / 常亮
    // ------------------------------------------------------------------

    /**
     * 整体缩放 DSH 界面。
     *
     * 用 CSS `zoom` 而不是 WebSettings.setTextZoom：后者只放大字号，盒子/内边距
     * 不跟着长，结果是文字挤在一起、排版崩掉。`zoom` 把整棵子树连尺寸一起等比
     * 放大，才是真正的「整体大小」。
     *
     * `zoom` 不改变视口宽度，所以 DSH 的 matchMedia(max-width:1023px) 布局判定
     * 不受影响 —— 放大后仍是原来的手机/桌面形态，只是都变大。
     */
    private fun applyScale(scale: Float) {
        prefs.setScale(scale)
        ui = ui.copy(scale = prefs.scale())
        if (loaded) webView.evaluateJavascript(scaleJs(prefs.scale()), null)
    }

    private fun scaleJs(scale: Float): String {
        val v = String.format(java.util.Locale.US, "%.3f", scale)
        return "(function(){try{document.documentElement.style.zoom='" + v +
            "';}catch(e){}})();"
    }

    /**
     * 切换会话内布局。
     *
     * 由客户端自己实现（见 [PageInject]），**不依赖 dsh-pocket 的私有 localStorage 键** ——
     * 这样换成任何转发层（dsh-lan / 自建代理）都一样能用。
     *
     * 布局是在页面启动时按媒体查询定的，所以改完必须整页重载才生效。
     */
    private fun setLayout(mode: String) {
        prefs.setLayout(mode)
        ui = ui.copy(layout = mode)
        if (loaded) webView.reload()
    }

    /** 取主文档、注入、回给 WebView。任何一步不对就返回 null 交回原路。 */
    private fun rewriteMainDocument(url: Uri): WebResourceResponse? {
        val request = Request.Builder()
            .url(url.toString())
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        // docClient：短超时。这个函数在 WebView 的请求线程上同步跑，
        // 用默认 10 s/20 s 会让换路后的空白页持续到超时（见 docClient 注释）。
        docClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val contentType = resp.header("Content-Type") ?: return null
            if (!contentType.contains("text/html", ignoreCase = true)) return null

            val html = resp.body?.string() ?: return null            // 上游代理补过就不重复补，避免页面里出现两份 polyfill
            val script = PageInject.script(
                layout = prefs.layout(),
                needPolyfill = !PageInject.proxyAlreadyPolyfilled(html),
            )
            val out = PageInject.inject(html, script)

            // 响应体已经解压并改写，这三个头必须去掉，否则协议冲突
            val headers = LinkedHashMap<String, String>()
            for (name in resp.headers.names()) {
                when (name.lowercase()) {
                    "content-encoding", "content-length", "transfer-encoding" -> Unit
                    else -> headers[name] = resp.headers[name].orEmpty()
                }
            }

            return WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    private fun toggleAwake() {
        prefs.setKeepAwake(!prefs.keepAwake())
        applyKeepAwake()
        ui = ui.copy(awake = prefs.keepAwake())
    }

    private fun applyKeepAwake() {
        if (prefs.keepAwake()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------
    // 返回
    // ------------------------------------------------------------------

    /**
     * 换路后 8 秒还没等到首帧，就自己重载一次。
     *
     * 只在换路时调用；[WebViewClient.onPageCommitVisible] 一触发就把开关关掉，
     * 所以正常换路不会有任何多余动作。
     */
    private fun armAutoReloadAfterSwitch() {
        switchAutoReloadArmed = true
        val token = webView
        token.postDelayed({
            if (!switchAutoReloadArmed) return@postDelayed
            switchAutoReloadArmed = false
            if (ui.screen != Screen.Dsh || ui.dshReady) return@postDelayed
            // 首帧没来：换路那次导航废了，补一次。记一条事件方便用户截图反馈时对账。
            EventLog.event(this, "route", "no first frame after switch; auto reload")
            token.reload()
        }, 8_000L)
    }

    private fun handleBack() {
        when {
            ui.panelOpen -> ui = ui.copy(panelOpen = false)
            ui.settingsOpen -> ui = ui.copy(settingsOpen = false)
            // DSH 没有 URL 路由，canGoBack 基本都是 false → 退到设备选择页
            ui.screen == Screen.Dsh && webView.canGoBack() -> webView.goBack()
            // ★ 从 DSH 界面（含锁页）按返回，先回到「选择设备」，而不是一把退出 App。
            //   退出是用户在设备页再按一次返回时的事。
            ui.screen == Screen.Dsh || ui.screen == Screen.Locked -> {
                // ★ 回列表时重读连接并重探一遍：切换网络后局域网地址可能已经变了，
                //   列表要是还挂着旧地址，点进去必然连不上（用户实测反馈）。
                ui = ui.copy(screen = Screen.Connections)
                refreshConnections()
            }
            // 从设置里「更改地址」进来的，给一条退路
            ui.screen == Screen.Setup && loaded -> ui = ui.copy(screen = Screen.Dsh)
            else -> finish()
        }
    }

    /**
     * 进设备时的「没反应就自愈一次」。
     *
     * 用户实测：从设备列表点进去，偶尔第一下什么都没发生，得再点一次；
     * 或者进去后停在错误页、点左下角「重新连接」才恢复 —— 本质是首次导航
     * 的那一次请求早了（隧道刚起，或代理还没把 main document 注入完）。
     *
     * 4 秒还没有首帧就替用户再点一次（同一地址重新 loadUrl），只补一次。
     */
    private fun armEntryRetry(entry: String) {
        if (entryRetried) return
        val token = webView
        token.postDelayed({
            if (this.isFinishing) return@postDelayed
            if (ui.screen != Screen.Dsh || ui.dshReady || ui.dshError != null) return@postDelayed
            if (entryRetried) return@postDelayed
            entryRetried = true
            EventLog.event(this, "entry", "no first frame after enter; retry once")
            ensureThemeCookie(entry)
            token.loadUrl(entry, themeHeaders())
        }, 4_000L)
    }

    private fun startLoadTimeout() {
        clearLoadTimeout()
        val runnable = Runnable {
            if (ui.screen == Screen.Dsh && !ui.dshReady && ui.dshError == null) {
                ui = ui.copy(
                    dshError = "界面还没加载出来：可能是这台设备还没解锁，或者电脑上的 dsh web 没在运行。" +
                        "点「重新加载」会先走一次解锁（密码 / 生物识别）。",
                )
            }
        }
        loadTimeout = runnable
        handler.postDelayed(runnable, 12_000)
    }

    private fun clearLoadTimeout() {
        loadTimeout?.let { handler.removeCallbacks(it) }
        loadTimeout = null
    }

    private fun maybeHandleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND &&
            intent?.action != Intent.ACTION_SEND_MULTIPLE
        ) {
            return
        }
        when (ShareInbox.accept(this, intent)) {
            ShareInbox.Kind.Image ->
                // 图片先存着。等用户进 DSH 点「附件」，onShowFileChooser 会把它交出去 ——
                // 比让他"先存到文件、再在 DSH 里翻出来"少好几步。
                toast("图片已就绪 —— 回到 DSH 点「附件」就能发出去")

            ShareInbox.Kind.Text -> {
                val body = ShareInbox.consumePendingText(this) ?: return
                // 只有在**看起来就是 DSH 入口地址**时才当成地址连过去。
                // 判定刻意严：必须带显式端口、且后面不带路径。
                // 否则分享一个普通链接（GitHub 页面之类）会被误当成地址，
                // 把用户从当前会话里踢出去 —— 那正是分享内容进来时最不希望发生的事。
                if (looksLikeEntryAddress(body)) connect(body.trim())
                else writeToComposer(body)
            }

            null -> Unit
        }
    }

    /** 点一下页面上的「新会话」。 */
    private fun clickNewSession() {
        webView.evaluateJavascript(
            """(function(){
              try {
                var btns = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
                for (var i = 0; i < btns.length; i++) {
                  var t = (btns[i].innerText || '').trim();
                  if (t === '新会话' || t === 'New session' || t === 'New Session') {
                    btns[i].click();
                    return 'ok';
                  }
                }
                return 'not-found';
              } catch (e) { return 'error'; }
            })();""",
            null,
        )
    }

    /** 直接跳到最近用过的那个会话。走的是 notification 那套 localStorage 机制。 */
    private fun openMostRecentSession() {
        val last = prefs.lastSessionId()
        if (last.isNullOrEmpty()) {
            toast("还没有用过的会话")
            return
        }
        pendingSessionId = last
        if (loaded) {
            pendingSessionId = null
            webView.evaluateJavascript(setCurrentSessionJs(last), null)
            prefs.setLastSessionId(last)
        }
    }

    /**
     * 这个字符串看起来是不是一个 DSH 入口地址？
     *
     * 要求**同时**满足：有 scheme、有显式端口、路径为空或只有 `/`。
     * 端口是关键 —— DSH 的转发层永远带端口，而普通网页链接通常没有。
     */
    private fun looksLikeEntryAddress(s: String): Boolean {
        val t = s.trim().replace("\n", "").replace(" ", "")
        if (!t.startsWith("http://") && !t.startsWith("https://")) return false
        return Regex("^https?://[^/]+:\\d{2,5}/?$").matches(t)
    }
}

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

data class ShellState(
    val screen: Screen = Screen.Connecting,
    val entryUrl: String = "",
    /** 握手是否在飞 —— 连接页的按钮靠它转圈。 */
    val connecting: Boolean = false,
    val setupError: String? = null,
    val dshReady: Boolean = false,
    val dshError: String? = null,
    /**
     * 主文档加载失败的原因（null = 没问题）。
     *
     * 为什么不能只用 dshReady：Chrome 对**错误页也会调 onPageFinished**，
     * 于是「错误 → dshReady=false → onPageFinished → dshReady=true」，
     * 覆盖层刚出现就被撤掉，用户看到的是 WebView 的原生错误页。
     */
    val pageError: String? = null,
    val layout: String = Prefs.LAYOUT_AUTO,
    val scale: Float = Prefs.SCALE_DEFAULT,
    val awake: Boolean = false,
    val settingsOpen: Boolean = false,
    val notifyEnabled: Boolean = false,
    /** 通知有问题时的说明；null = 一切正常。设置面板会把它显示出来。 */
    val notifyProblem: String? = null,
    val panelOpen: Boolean = false,
    val crashReport: String? = null,
    /** 检查更新的结果：远端最新版本号（null = 还不知道）。 */
    val updateLatest: String? = null,
    /** 远端是否比本机新。 */
    val updateNewer: Boolean = false,
    /** 正在手动检查。 */
    val updateChecking: Boolean = false,
    /** 本次结果建议的下载地址（镜像优先，官方兜底）。 */
    val updateApkUrl: String = UpdateCheck.APK_URL,

    /** 闸门界面：prompt（去过生物识别） / password（要手输） / busy。 */
    val lockStage: String = "prompt",
    val lockError: String? = null,
    /** 用户选的解锁方式（AppLock.Mode.id）。 */
    val lockModeState: String = "auto",
    /** 这台设备实际可用什么。 */
    val lockDevice: String = "",
    /** 这台设备支持什么解锁方式（AppLock.describe 的结果）。 */
    val lockDeviceMethod: String = "",
    /** 本机有没有存过密码 —— 决定下次能否直接用人脸/指纹。 */
    val lockHasStored: Boolean = false,

    /** 连接列表与各自的探测状态。 */
    val connections: List<Connection> = emptyList(),
    val connStatuses: Map<String, ConnectionProbe.Status> = emptyMap(),
    val probing: Boolean = false,
    val activeConnId: String? = null,

    /** 应用内下载的状态：idle / downloading / ready / failed。 */
    val updatePhase: String = "idle",
    val updateBytes: Long = 0L,
    val updateTotal: Long = 0L,
    val updateError: String? = null,
)

// ---------------------------------------------------------------------------
// 组装
// ---------------------------------------------------------------------------

@Composable
private fun Shell(
    state: ShellState,
    webView: WebView,
    /**
     * 装 WebView 的宿主容器。
     *
     * AndroidView 必须挂它、而不是 `webView` 字段本身：Compose 的 factory 只跑一次，
     * 字段后来指向了别的 WebView 它也不知道 —— 那样常驻池建了也不会真的换过去。
     * 挂一个稳定的 FrameLayout，由 MainActivity.activate() 换里面的孩子。
     */
    webHost: android.widget.FrameLayout,
    onConnect: (String?) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onChangeUrl: () -> Unit,
    onCancelSetup: () -> Unit,
    onSetupBack: () -> Unit,
    onLayout: (String) -> Unit,
    onScale: (Float) -> Unit,
    onToggleAwake: () -> Unit,
    onClearSession: () -> Unit,
    onToggleNotify: () -> Unit,
    onTestNotify: () -> Unit,
    onNotifySettings: () -> Unit,
    onScan: () -> Unit,
    onVoice: () -> Unit,
    /** 交访问密码（闸门解锁）。 */
    onUnlock: (String) -> Unit,
    /** 重新拉起生物识别。 */
    onRetryUnlock: () -> Unit,
    /** 更换解锁方式。 */
    onLockMode: (String) -> Unit,
    onOpenConnections: () -> Unit,
    onOpenSetupForAdd: () -> Unit,
    /** 从连接管理回到 DSH 界面。 */
    onBackToDsh: () -> Unit,
    onSwitchConn: (Connection) -> Unit,
    onProbeConn: () -> Unit,
    onSaveConn: (Connection) -> Unit,
    onDeleteConn: (Connection) -> Unit,
    updateLatest: String?,
    updateNewer: Boolean,
    updateChecking: Boolean,
    updateApkUrl: String,
    onCheckUpdate: () -> Unit,
    onPinWidget: () -> Unit,
    lockModeState: String,
    lockDevice: String,
    /** 应用内下载/安装的状态。 */
    updatePhase: String,
    updateBytes: Long,
    updateTotal: Long,
    updateError: String?,
    onInstall: () -> Unit,
    onDownload: (String) -> Unit,
    onOpenPanel: () -> Unit,
    onClosePanel: () -> Unit,
    onClearNotices: () -> Unit,
    onOpenNotice: (String) -> Unit,
    onDismissCrash: () -> Unit,
) {
    // ★ 返回键按「来的路」退：从设备列表进的添加页、从会话进的设备列表，
    //   都只退一级。以前一律走 onBack()，于是「添加设备」界面按返回直接退出 App。
    BackHandler {
        when (state.screen) {
            // 添加/编辑设备也属于「选电脑」这条线：返回应当回到列表，
            // 而不是把整个 App 关掉（用户第一次点「+」就撞到过这个问题）。
            Screen.Setup -> onSetupBack()
            // ★ 设备选择页按返回就退出 App（用户明确要求）：以前 dshReady 还是 true
            //   时会退回 DSH，于是「列表 ↔ DSH」来回跳。
            Screen.Connections -> onBack()
            else -> onBack()
        }
    }

    val streamLiveState by SessionWatcher.live.collectAsStateWithLifecycle()
    val streamErrorState by SessionWatcher.lastError.collectAsStateWithLifecycle()
    val lastEventAtState by SessionWatcher.lastEventAt.collectAsStateWithLifecycle()
    val streamModeState by SessionWatcher.mode.collectAsStateWithLifecycle()
    val notices by SessionWatcher.notices.collectAsStateWithLifecycle()
    val unseen by SessionWatcher.unseen.collectAsStateWithLifecycle()
    val live by SessionWatcher.live.collectAsStateWithLifecycle()

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val stripTotal = StripHeight + statusBarTop
    val showStrip = state.screen == Screen.Dsh

    Box(Modifier.fillMaxSize()) {
        // DSH 界面（常驻）。顶部让出原生状态条的高度。
        AndroidView(
            // ★ 挂**宿主容器**而不是 webView 字段本身。
            //   Compose 的 factory 只跑一次，字段后来指向了别的 WebView 它也不知道 ——
            //   那样池子建了也不会真的换过去。挂一个稳定的 FrameLayout，
            //   由 activate() 去换它里面的孩子。
            factory = { webHost },
            modifier = Modifier
                .fillMaxSize()
                .then(if (showStrip) Modifier.padding(top = stripTotal) else Modifier)
                // 键盘弹起时把 WebView 整体压上去。DSH 的输入框是 position:fixed;bottom:0，
                // WebView 不缩它就被压在键盘底下 —— 看不到自己正在打什么。
                // 用 union（取两侧较大值）而不是两个 padding 相加：键盘弹起时系统同时报
                // 导航栏和输入法两条 inset，简单相加会多顶出一条导航栏的高度。
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime),
                ),
        )

        when (state.screen) {
            Screen.Connecting -> ConnectingScreen(host = hostOf(state.entryUrl))

            Screen.Setup -> SetupScreen(
                initialUrl = state.entryUrl.ifEmpty { Prefs.DEFAULT_URL },
                error = state.setupError,
                connecting = state.connecting,
                onConnect = { onConnect(it) },
                onScan = onScan,
                onCancel = if (state.dshReady) onCancelSetup else null,
            )

            Screen.Connections -> ConnectionsScreen(
                connections = state.connections,
                statuses = state.connStatuses,
                activeId = state.activeConnId,
                probing = state.probing,
                onSwitch = onSwitchConn,
                onProbeAll = onProbeConn,
                onAdd = onOpenSetupForAdd,
                onEdit = onSaveConn,
                onDelete = onDeleteConn,
                onBack = onBackToDsh,
                onOpenSettings = onOpenSettings,
                // 顶部的系统 inset 由页面自己算（它能判断窗口有没有贴着屏幕顶，
                // 分屏时不该再补一份，见 rememberTopInset）
                modifier = Modifier,
            )

            Screen.Locked -> LockScreen(
                host = hostOf(state.entryUrl),
                stage = state.lockStage,
                error = state.lockError,
                deviceMethod = state.lockDeviceMethod,
                hasStoredPassword = state.lockHasStored,
                onUnlock = onUnlock,
                onRetryBiometric = onRetryUnlock,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(top = stripTotal),
            )

            Screen.Dsh -> if (state.pageError != null) {
                // 加载失败：给一个说人话的界面，而不是 WebView 的原生错误页
                PageErrorOverlay(
                    host = hostOf(state.entryUrl),
                    url = state.entryUrl,
                    detail = state.pageError,
                    onRetry = onReload,
                    onEditUrl = onChangeUrl,
                    onSettings = onOpenSettings,
                    modifier = Modifier.padding(top = stripTotal),
                )
            } else if (!state.dshReady) {
                DshLoadingOverlay(
                    host = hostOf(state.entryUrl),
                    error = state.dshError,
                    onReload = onReload,
                    onSettings = onOpenSettings,
                    modifier = Modifier.padding(top = stripTotal),
                )
            }
        }

        if (showStrip) {
            DshStatusStrip(
                host = hostOf(state.entryUrl),
                channel = channelOf(state.entryUrl),
                // ★ 绿点表示「这个页面连接是通的」，不是「事件流订阅成功」。
                //   事件流在部分 DSH 版本上退化成轮询（或干脆没连上），
                //   以前那样这条状态就一直是灰的 —— 和用户看到的「明明连着」矛盾。
                live = state.dshReady || live,
                unseen = unseen,
                onNotice = onOpenPanel,
                onReload = onReload,
                onSettings = onOpenSettings,
                onHome = onOpenConnections,
                onVoice = onVoice,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // 通知悬浮窗：锚在右上角，贴着状态条下沿
            if (state.panelOpen) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = with(density) {
                        IntOffset(x = (-10).dp.roundToPx(), y = stripTotal.roundToPx() + 6.dp.roundToPx())
                    },
                    onDismissRequest = onClosePanel,
                ) {
                    NoticePanel(
                        notices = notices,
                        notifyEnabled = state.notifyEnabled,
                        onEnableNotify = onToggleNotify,
                        onOpen = { onOpenNotice(it.sessionId) },
                        onClear = onClearNotices,
                    )
                }
            }
        }

        if (state.settingsOpen) {
            SettingsSheet(
                url = state.entryUrl,
                layout = state.layout,
                awake = state.awake,
                notifyEnabled = state.notifyEnabled,
                onToggleNotify = onToggleNotify,
                notifyProblem = state.notifyProblem,
                onTestNotify = onTestNotify,
                onNotifySettings = onNotifySettings,
                streamLive = streamLiveState,
                streamMode = streamModeState.name.lowercase(),
                streamError = streamErrorState,
                lastEventAt = lastEventAtState,
                scale = state.scale,
                onScale = onScale,
                onLayout = onLayout,
                onToggleAwake = onToggleAwake,
                onClearSession = onClearSession,
                onChangeUrl = onChangeUrl,
                onOpenConnections = onOpenConnections,
                connectionCount = state.connections.size,
                updateLatest = updateLatest,
                updateNewer = updateNewer,
                updateChecking = updateChecking,
                updateApkUrl = updateApkUrl,
                onCheckUpdate = onCheckUpdate,
                lockMode = lockModeState,
                lockDeviceMethod = lockDevice,
                onLockMode = onLockMode,
                onPinWidget = onPinWidget,
                updatePhase = updatePhase,
                updateBytes = updateBytes,
                updateTotal = updateTotal,
                updateError = updateError,
                onInstall = onInstall,
                onDownload = onDownload,
                currentVersion = BuildConfig.VERSION_NAME,
                pluginVersion = state.connStatuses[state.entryUrl]?.version,
                onDismiss = onCloseSettings,
            )
        }

        state.crashReport?.let { report ->
            CrashDialog(report = report, onDismiss = onDismissCrash)
        }
    }
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

/** 这条地址走的是哪条通道：局域网 / Tailscale / 其它（自建域名等）。 */
private fun channelOf(url: String): String = when {
    url.isBlank() -> ""
    com.dshgo.app.data.AutoRoute.isTailscaleUrl(url) -> "Tailscale"
    com.dshgo.app.data.AutoRoute.isLanUrl(url) -> "局域网"
    else -> ""
}
