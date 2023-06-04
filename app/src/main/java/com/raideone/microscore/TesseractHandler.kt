package com.raideone.microscore

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.util.Locale

class TesseractHandler {

    companion object {
        const val TAG = "TESSERACT"

        const val TESS_ENGINE = TessBaseAPI.OEM_LSTM_ONLY
        const val TESS_LANG = "eng"
    }

    private var tessApi: TessBaseAPI
    private val processing = MutableLiveData(false)
    private val progress = MutableLiveData<String>()
    private val result = MutableLiveData<Pair<Int, String>>()
    private var tessInit = false
    private var stopped = false

    init {
        tessApi = TessBaseAPI { progressValues: TessBaseAPI.ProgressValues ->
            progress.postValue(
                "Progress: " + progressValues.percent + " %"
            )
        }
    }

    private fun isProcessing(): Boolean {
        return java.lang.Boolean.TRUE == processing.value
    }

    fun initTesseract(context: Context) {
        val assets = Assets()
        assets.extractAssets(context)
        val dataPath = assets.getTessDataPath(context)

        Log.i(TAG, "Initializing Tesseract with: dataPath = [$dataPath]")
        try {
            tessInit = tessApi.init(dataPath, TESS_LANG, TESS_ENGINE)
            tessApi.setVariable("tessedit_char_whitelist", "0123456789")
        } catch (e: IllegalArgumentException) {
            tessInit = false
            Log.e(TAG, "Cannot initialize Tesseract:", e)
        }
    }

    suspend fun extractBase(croppedBitmap: Bitmap, index: Int) {
        if (!tessInit) {
            Log.e(TAG, "recognizeImage: Tesseract is not initialized")
            return
        }
        if (isProcessing()) {
            Log.e(TAG, "recognizeImage: Processing is in progress")
            return
        }
        result.postValue(Pair(-1, ""))
        processing.postValue(true)
        progress.postValue("Processing...")
        stopped = false

        try {
            withContext(Dispatchers.Default) {
                Log.d(TAG, "Started new coroutine for OCR")
                tessApi.setImage(croppedBitmap)
                val startTime = SystemClock.uptimeMillis()

                tessApi.getHOCRText(0)

                val text = tessApi.utF8Text
                result.postValue(Pair(index, text))
                processing.postValue(false)
                if (stopped) {
                    Log.d(TAG, "Progress stopped")
                    progress.postValue("Stopped.")
                } else {
                    val duration = SystemClock.uptimeMillis() - startTime
                    Log.d(TAG, "Progress completed")
                    progress.postValue(
                        String.format(
                            Locale.ENGLISH,
                            "Completed in %.3fs.", duration / 1000f
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in extractBase", e)
        }
    }

    private fun stop() {
        if (!isProcessing()) {
            return
        }
        tessApi.stop()
        progress.value = "Stopping..."
        stopped = true
    }

    fun recycle() {
        if (isProcessing()){
            stop()
        }
        tessApi.recycle()
    }

    fun observeResult(observer: Observer<Pair<Int, String>>) {
        result.observeForever(observer)
    }

    fun removeObserver(observer: Observer<Pair<Int, String>>) {
        result.removeObserver(observer)
    }
}
