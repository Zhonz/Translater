package com.screentranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.screentranslate.model.AppConfig
import com.screentranslate.model.TextBlock
import com.screentranslate.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮窗服务。
 *
 * 提供一个可拖动的悬浮按钮，点击后弹出三模式菜单：
 * 1. 单次翻译 —— 截屏一次，OCR + AI 翻译，覆盖显示结果。
 * 2. 持续翻译 —— 周期性截屏翻译，再次点击关闭。
 * 3. 设置 —— 打开主界面配置 AI 服务商与提示词。
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var menuView: View? = null

    private lateinit var captureManager: ScreenCaptureManager
    private lateinit var ocrManager: OcrManager
    private lateinit var translationManager: TranslationManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var prefsManager: PrefsManager
    private lateinit var termInjector: TermInjector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var isContinuousMode = false
    private var continuousJob: Job? = null

    // 屏幕尺寸（用于菜单定位）
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    companion object {
        private const val CHANNEL_ID = "screen_translate_service"
        private const val NOTIFICATION_ID = 1001

        /** 持续翻译间隔（毫秒） */
        private const val CONTINUOUS_INTERVAL_MS = 3000L

        /** MediaProjection 授权结果暂存（由 MainActivity 设置） */
        @JvmStatic
        var pendingResultCode: Int = 0
        @JvmStatic
        var pendingData: Intent? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            pendingResultCode = resultCode
            pendingData = data
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        captureManager = ScreenCaptureManager(this)
        ocrManager = OcrManager()
        translationManager = TranslationManager()
        overlayManager = OverlayManager(this)
        prefsManager = PrefsManager(this)
        termInjector = TermInjector(this)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        startForegroundWithNotification()
        showFloatingButton()

        // 初始化屏幕捕获
        val data = pendingData
        if (data != null && pendingResultCode != 0) {
            captureManager.setUp(pendingResultCode, data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isContinuousMode = false
        continuousJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        overlayManager.clear()
        removeFloatingButton()
        removeMenu()
        captureManager.release()
        ocrManager.close()
        translationManager.close()
        serviceScope.cancel()
    }

    // ==================== 前台服务通知 ====================

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ==================== 悬浮按钮 ====================

    private fun showFloatingButton() {
        if (floatingButton != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        val button = view.findViewById<ImageView>(R.id.ivFloatingButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 200
            y = screenHeight / 3
        }

        // 拖动 + 点击
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTime = 0L

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) +
                        kotlin.math.abs(event.rawY - initialTouchY)
                    if (elapsed < 300 && moved < 20) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
            floatingButton = view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFloatingButton() {
        floatingButton?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        floatingButton = null
    }

    // ==================== 菜单 ====================

    private fun toggleMenu() {
        if (menuView != null) {
            removeMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        val view = LayoutInflater.from(this).inflate(R.layout.floating_menu, null)
        val menuRoot = view.findViewById<LinearLayout>(R.id.menuRoot)

        // 菜单项点击
        view.findViewById<View>(R.id.btnModeSingle).setOnClickListener {
            removeMenu()
            doSingleTranslate()
        }
        view.findViewById<View>(R.id.btnModeContinuous).setOnClickListener {
            removeMenu()
            toggleContinuousTranslate()
        }
        view.findViewById<View>(R.id.btnModeSettings).setOnClickListener {
            removeMenu()
            openSettings()
        }
        view.findViewById<View>(R.id.btnClose).setOnClickListener {
            removeMenu()
            stopSelf()
        }

        // 定位在悬浮按钮旁边
        val btnParams = floatingButton?.let {
            (it.layoutParams as WindowManager.LayoutParams)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (btnParams?.x ?: (screenWidth - 200)) - 200
            y = btnParams?.y ?: (screenHeight / 3)
        }

        // 菜单宽度
        menuRoot.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val menuWidth = menuRoot.measuredWidth.let { if (it <= 0) 400 else it }
        if (params.x < 0) params.x = 0
        if (params.x + menuWidth > screenWidth) params.x = screenWidth - menuWidth

        try {
            windowManager.addView(view, params)
            menuView = view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeMenu() {
        menuView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        menuView = null
    }

    // ==================== 翻译流程 ====================

    /**
     * 执行一次完整的截屏 -> OCR -> 翻译 -> 覆盖流程（异步，完成后回调）。
     */
    private fun runTranslateOnce(onComplete: (() -> Unit)? = null) {
        serviceScope.launch {
            translateOnceSuspend()
            onComplete?.invoke()
        }
    }

    /**
     * 核心翻译流程（挂起函数，供单次/持续模式复用）。
     *
     * 重计算工作（截屏、OCR、术语匹配、AI 翻译）在 [Dispatchers.Default] 上执行，
     * Toast 提示切回 [Dispatchers.Main]，避免阻塞主线程。
     */
    private suspend fun translateOnceSuspend() {
        if (!captureManager.isReady) {
            showToast("截屏权限未就绪，请重新启动服务")
            return
        }

        val bitmap = captureManager.capture()
        if (bitmap == null) {
            showToast("截屏失败")
            return
        }

        val blocks: List<TextBlock> = try {
            withContext(Dispatchers.Default) {
                ocrManager.recognize(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            // OCR 完成后立即回收截屏，释放内存
            bitmap.recycle()
        }

        if (blocks.isEmpty()) {
            overlayManager.clear()
            return
        }

        // 跳过已是中文的文字块，减少 AI 调用量
        val (toTranslate, indices) = blocks.mapIndexedNotNull { idx, b ->
            if (containsChinese(b.text)) null else (b to idx)
        }.unzip()

        if (toTranslate.isEmpty()) {
            // 全部已是中文，直接显示原文
            overlayManager.showTranslations(blocks, blocks.map { it.text })
            return
        }

        val config = prefsManager.loadConfig()
        // 构建术语上下文：扫描待翻译文本，注入命中的 Project Moon 术语（可在设置中关闭）
        val srcTexts = toTranslate.map { it.text }
        val termContext = if (prefsManager.isProjectMoonTermsEnabled()) {
            termInjector.buildTermContext(srcTexts)
        } else {
            ""
        }
        val translatedList = translationManager.translate(srcTexts, config, termContext)

        // 组装结果：已中文的保留原文，其余用翻译结果
        val results = MutableList(blocks.size) { "" }
        for (i in indices.indices) {
            results[indices[i]] = translatedList.getOrNull(i) ?: ""
        }
        for (i in blocks.indices) {
            if (results[i].isBlank() && containsChinese(blocks[i].text)) {
                results[i] = blocks[i].text
            }
        }

        overlayManager.showTranslations(blocks, results)
    }

    /** 在主线程显示 Toast */
    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@FloatingWindowService, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun doSingleTranslate() {
        Toast.makeText(this, R.string.translating, Toast.LENGTH_SHORT).show()
        runTranslateOnce {
            Toast.makeText(this, R.string.translate_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleContinuousTranslate() {
        if (isContinuousMode) {
            isContinuousMode = false
            continuousJob?.cancel()
            overlayManager.clear()
            Toast.makeText(this, R.string.continuous_off, Toast.LENGTH_SHORT).show()
        } else {
            isContinuousMode = true
            Toast.makeText(this, R.string.continuous_on, Toast.LENGTH_SHORT).show()
            startContinuousLoop()
        }
    }

    private fun startContinuousLoop() {
        serviceScope.launch {
            while (isContinuousMode) {
                try {
                    translateOnceSuspend()
                } catch (e: Exception) {
                    // 单次翻译异常不应中断持续翻译循环
                    e.printStackTrace()
                }
                if (!isContinuousMode) break
                // 等待间隔
                val endTime = System.currentTimeMillis() + CONTINUOUS_INTERVAL_MS
                while (isContinuousMode && System.currentTimeMillis() < endTime) {
                    kotlinx.coroutines.delay(200)
                }
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /** 判断文本是否包含中文字符 */
    private fun containsChinese(text: String): Boolean {
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF) return true
        }
        return false
    }
}
