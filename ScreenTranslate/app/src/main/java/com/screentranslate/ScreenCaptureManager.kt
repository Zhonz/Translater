package com.screentranslate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * 基于 MediaProjection API 的屏幕截图管理器。
 *
 * Android 14+ 适配：
 * - 注册 MediaProjection.Callback，处理系统主动停止投屏的场景
 * - VirtualDisplay 按需启停：仅在截屏时激活，空闲时释放，避免屏幕共享指示器持续显示
 * - 持续翻译模式由调用方通过 [keepDisplayAlive] 控制
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var density: Int = 0

    /** 投屏是否被系统停止（用户撤销授权等），停止后需重新请求 */
    @Volatile
    var projectionStopped = false
        private set

    /** 是否已完成 MediaProjection 授权初始化 */
    val isReady: Boolean get() = mediaProjection != null

    /** VirtualDisplay 是否已激活（正在镜像屏幕） */
    val isDisplayActive: Boolean get() = virtualDisplay != null

    /** 投屏被系统停止时的回调 */
    var onProjectionStopped: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection 被系统停止")
            projectionStopped = true
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection = null
            onProjectionStopped?.invoke()
        }
    }

    /**
     * 初始化屏幕捕获（注册 MediaProjection，但不立即创建 VirtualDisplay）。
     *
     * Android 14+ 适配：VirtualDisplay 延迟到实际截屏时才创建，
     * 避免屏幕共享指示器在非截屏时段持续显示。
     *
     * @param resultCode MediaProjection 授权返回的 resultCode
     * @param data MediaProjection 授权返回的 intent
     */
    fun setUp(resultCode: Int, data: Intent) {
        release()

        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.densityDpi

        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        // 注册 Callback，监听系统主动停止投屏
        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        projectionStopped = false
        Log.d(TAG, "MediaProjection 初始化完成，VirtualDisplay 延迟创建")
    }

    /**
     * 激活 VirtualDisplay（开始镜像屏幕）。
     * 已激活时直接返回，避免重复创建。
     */
    private fun ensureDisplayActive() {
        if (virtualDisplay != null) return
        if (mediaProjection == null) return

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenTranslateCapture",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.d(TAG, "VirtualDisplay 已激活")
    }

    /**
     * 释放 VirtualDisplay（停止镜像屏幕，隐藏屏幕共享指示器）。
     * 投屏授权仍然保留，后续截屏可重新激活。
     */
    fun releaseDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        Log.d(TAG, "VirtualDisplay 已释放")
    }

    /**
     * 捕获当前屏幕，返回 Bitmap（可能为 null）。
     * 自动激活 VirtualDisplay，截屏后根据 [keepAlive] 决定是否释放。
     *
     * @param keepAlive true 表示持续翻译模式，截屏后保留 VirtualDisplay；
     *                  false 表示单次截屏，完成后释放以隐藏屏幕共享指示器
     */
    suspend fun capture(keepAlive: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        if (mediaProjection == null) return@withContext null

        // 按需激活 VirtualDisplay
        ensureDisplayActive()

        val reader = imageReader ?: return@withContext null
        var image: Image? = null
        var attempt = 0
        try {
            // 首帧可能未就绪，重试几次
            while (attempt < MAX_RETRIES) {
                image = reader.acquireLatestImage()
                if (image != null) break
                attempt++
                delay(RETRY_INTERVAL_MS)
            }
            if (image == null) {
                // 首帧未就绪时再给一次机会
                delay(150)
                image = reader.acquireLatestImage()
            }
            if (image == null) return@withContext null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            if (pixelStride <= 0) return@withContext null
            val rowPadding = rowStride - pixelStride * screenWidth
            if (rowPadding < 0) return@withContext null

            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            try {
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            } catch (e: Exception) {
                bitmap.recycle()
                return@withContext null
            }

            if (rowPadding != 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                if (cropped != bitmap) bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            image?.close()
            // 单次截屏后释放 VirtualDisplay，避免屏幕共享指示器持续显示
            if (!keepAlive) {
                releaseDisplay()
            }
        }
    }

    /** 完全释放所有资源（投屏 + VirtualDisplay） */
    fun release() {
        releaseDisplay()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        projectionStopped = false
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val MAX_RETRIES = 5
        private const val RETRY_INTERVAL_MS = 50L
    }
}
