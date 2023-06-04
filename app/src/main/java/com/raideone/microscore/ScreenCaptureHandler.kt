package com.raideone.microscore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log

class ScreenCaptureHandler(private val context: Context) {

    companion object {
        const val TAG = "SCREEN_CAPTURE"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val windowWidth: Int by lazy {
        DisplayUtils.getScreenWidth(context)
    }
    private val windowHeight: Int by lazy {
        DisplayUtils.getScreenHeight(context)
    }
    private val screenDensity: Int by lazy {
        DisplayUtils.getScreenDensity(context)
    }

    fun setMediaProjection(mediaProjection: MediaProjection) {
        this.mediaProjection = mediaProjection
    }

    fun setUpVirtualDisplay() {
        Log.d(TAG, "Setting up a VirtualDisplay: $windowWidth x $windowHeight ($screenDensity)")
        imageReader = ImageReader.newInstance(windowWidth, windowHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            windowWidth,
            windowHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null)
    }

    fun captureScreen(): Bitmap? {
        Log.d(TAG, "captureScreen called")
        val image = imageReader?.acquireLatestImage() ?: return null
        val width = image.width
        val height = image.height
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        var bitmap: Bitmap? = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap?.copyPixelsFromBuffer(buffer)
        bitmap = bitmap?.let { Bitmap.createBitmap(it, 0, 0, width, height) }
        image.close()
        return bitmap
    }

    fun stopScreenCapture() {
        Log.d(TAG, "stopCapture called")
        virtualDisplay?.release()
        virtualDisplay = null
    }
}