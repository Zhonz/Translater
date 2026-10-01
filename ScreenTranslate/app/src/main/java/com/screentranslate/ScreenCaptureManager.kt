package com.screentranslate

import android.app.Activity
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
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer

/**
 * 基于 MediaProjection API 的屏幕截图管理器。
 *
 * 使用流程：
 * 1. 在 Activity 中通过 [MediaProjectionManager.createScreenCaptureIntent] 发起授权。
 * 2. 在 onActivityResult 中拿到 resultCode 与 data，调用 [setUp]。
 * 3. 调用 [capture] 获取当前屏幕 Bitmap。
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var density: Int = 0

    /** 是否已完成 MediaProjection 初始化 */
    val isReady: Boolean get() = mediaProjection != null && virtualDisplay != null

    /**
     * 初始化屏幕捕获。
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

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenTranslateCapture",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    /**
     * 捕获当前屏幕，返回 Bitmap（可能为 null）。
     */
    fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        // 取最新的一帧
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 如果有行填充，裁剪掉
            return if (rowPadding != 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            image?.close()
        }
    }

    /** 释放资源 */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
