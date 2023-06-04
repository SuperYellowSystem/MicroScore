package com.raideone.microscore

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

class ImageProcessor(private val resources: Resources,
                     private val tesseractHandler: TesseractHandler,
                     private val screenCaptureHandler: ScreenCaptureHandler) {

    companion object {
        const val TAG = "IMAGE_PROCESSOR"
        const val TYPE_BOSS = 1
        const val TYPE_GOLEM = 2
    }

    suspend fun startCapture(imageIds: List<Int>, type: Int): List<Bitmap?> = coroutineScope {
        // Preparations
        val croppedBitmaps = mutableListOf<Bitmap?>()
        val templateList = arrayListOf<Bitmap>()

        imageIds.forEach { id ->
            val tmpBitmap = BitmapFactory.decodeResource(resources, id)
            templateList.add(tmpBitmap)
        }

        // Take screenshot
        val bitmap: Bitmap? = screenCaptureHandler.captureScreen()
        screenCaptureHandler.stopScreenCapture()
        bitmap?.let { bm ->
            val mat = Mat()
            Utils.bitmapToMat(bm, mat)

            try {
                templateList.forEachIndexed { index, templateBitmap ->
                    val job = launch {
                        val position = searchImage(mat, templateBitmap)
                        val croppedBitmap = takeLocalizedScreenshot(bm, position, templateBitmap, type)
                        val resizedBitmap = rescaleBitmap(croppedBitmap, 300)
                        val binarizedBitmap = binarize(resizedBitmap)
                        tesseractHandler.extractBase(binarizedBitmap, index)
                        croppedBitmaps.add(croppedBitmap)
                        templateBitmap.recycle()
                    }

                    job.join() // wait for the current job to finish before moving to the next
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in startCapture", e)
            }
        }
        return@coroutineScope croppedBitmaps
    }

    private fun searchImage(mat: Mat, templateBitmap: Bitmap): org.opencv.core.Point {
        // Prepare template
        val templateMat = Mat()
        try {
            Utils.bitmapToMat(templateBitmap, templateMat)
            Log.d(TAG, "Successfully converted bitmap to templateMat")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert bitmap to templateMat", e)
            return org.opencv.core.Point()
        }

        val resultCols = mat.cols() - templateMat.cols() + 1
        val resultRows = mat.rows() - templateMat.rows() + 1
        val result = Mat(resultRows, resultCols, CvType.CV_32FC1)
        try {
            // Perform template matching
            Imgproc.matchTemplate(mat, templateMat, result, Imgproc.TM_CCOEFF_NORMED)
            Log.d(TAG, "Successfully performed template matching")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform template matching", e)
            return org.opencv.core.Point()
        }

        // Find best match location
        val minMaxLoc = Core.minMaxLoc(result)
        return minMaxLoc.maxLoc
    }

    private fun takeLocalizedScreenshot(screenBitmap: Bitmap, position: org.opencv.core.Point, templateBitmap: Bitmap ,type: Int): Bitmap {
        var offsetX = 0
        var offsetY = 0
        var width = 230
        var height = 50

        if (type == TYPE_BOSS){
            offsetX = templateBitmap.width + 10
            width = 230
            height = 50
        }
        else if (type == TYPE_GOLEM) {
            offsetX = templateBitmap.width - 15
            offsetY = 10
            width = 70
            height = 50
        }

        val left = (position.x + offsetX).toInt().coerceAtLeast(0)
        val top = (position.y + offsetY).toInt().coerceAtLeast(0)

        Log.d(TAG, "Left: $left, top: $top, width: $width, height: $height")
        Log.d(TAG, "screenBitmap width: ${screenBitmap.width}")

        // Check if the width exceeds the bitmap width
        if (left + width > screenBitmap.width) {
            width = screenBitmap.width - left
        }

        // Check if the height exceeds the bitmap height
        if (top + height > screenBitmap.height) {
            height = screenBitmap.height - top
        }

        return Bitmap.createBitmap(screenBitmap, left, top, width, height)
    }

    private fun binarize(bitmap: Bitmap): Bitmap {
        // Convert bitmap to Mat
        val src = Mat(bitmap.height, bitmap.width, CvType.CV_8UC1)
        Utils.bitmapToMat(bitmap, src)

        // Convert the image to greyscale
        Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2GRAY)

        // Threshold the image to binary
        Imgproc.threshold(src, src, 125.0, 255.0, Imgproc.THRESH_BINARY)

        // Convert Mat to bitmap
        val resultBitmap = Bitmap.createBitmap(src.cols(), src.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(src, resultBitmap)

        return resultBitmap
    }

    private fun rescaleBitmap(bitmap: Bitmap, newWidth: Int): Bitmap {
        val aspectRatio: Float = bitmap.width.toFloat() / bitmap.height.toFloat()
        val newHeight: Int = (newWidth / aspectRatio).roundToInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, false)
    }
}
