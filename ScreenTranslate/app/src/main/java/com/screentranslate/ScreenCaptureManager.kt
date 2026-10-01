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
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * 基于 MediaProjection API 的屏幕截图管理器。
 *
 * 使用流程：
 * 1. 在 Activity 中通过 [MediaProjectionManager.createScreenCaptureIntent] 发起授权。
 * 2. 在 onActivityResult 中拿到 resultCode 与 data，调用 [setUp]。
 * 3. 调用 [capture] 获取当前屏幕 Bitmap。
 *
 * [capture] 为挂起函数，内部在 [Dispatchers.IO] 上执行，不会阻塞调用线程。
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
     * 若首帧未就绪，会短暂重试最多 [MAX_RETRIES] 次。
     *
     * 挂起函数：在 IO 线程执行阻塞操作，使用 [delay] 替代 Thread.sleep 避免阻塞主线程。
     */
    suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        var image: Image? = null
        var attempt = 0
        try {
            // 首帧可能未就绪，重试几次（使用 delay 不阻塞线程）
            while (attempt < MAX_RETRIES) {
                image = reader.acquireLatestImage()
                if (image != null) break
                attempt++
                delay(RETRY_INTERVAL_MS)
            }
            if (image == null) return@withContext null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            // 防御：pixelStride 为 0 会导致除零；rowPadding 为负说明 stride 异常
            if (pixelStride <= 0) {
                return@withContext null
            }
            val rowPadding = rowStride - pixelStride * screenWidth
            if (rowPadding < 0) {
                return@withContext null
            }

            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            try {
                // 显式重置 buffer 位置，确保 copyPixelsFromBuffer 从头读取
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            } catch (e: Exception) {
                bitmap.recycle()
                return@withContext null
            }

            // 如果有行填充，裁剪掉
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

    companion object {
        /** 截屏最大重试次数 */
        private const val MAX_RETRIES = 5
        /** 每次重试间隔（毫秒） */
        private const val RETRY_INTERVAL_MS = 50L
    }
}
