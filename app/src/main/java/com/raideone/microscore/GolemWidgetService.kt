package com.raideone.microscore

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Observer
import com.raideone.microscore.databinding.GolemWidgetLayoutBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GolemWidgetService : Service(), WidgetService {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var tesseractHandler: TesseractHandler
    private lateinit var screenCaptureHandler: ScreenCaptureHandler
    private lateinit var imageProcessor: ImageProcessor

    private lateinit var notificationHandler: NotificationHandler

    private var _binding: GolemWidgetLayoutBinding? = null
    private val binding get() = _binding!!

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0.0f
    private var initialTouchY: Float = 0.0f

    private val binder = LocalBinder()

    private val imageIds = listOf(
        R.drawable.golem_1,
        R.drawable.golem_2,
        R.drawable.golem_3,
        R.drawable.golem_4,
        R.drawable.golem_5
    )

    private val points = listOf(
        4, 6, 8, 8, 16
    )

    private val valueIds = listOf(
        R.id.nb_golem_1,
        R.id.nb_golem_2,
        R.id.nb_golem_3,
        R.id.nb_golem_4,
        R.id.nb_golem_5
    )

    private val labelIds = listOf(
        R.id.golem_1,
        R.id.golem_2,
        R.id.golem_3,
        R.id.golem_4,
        R.id.golem_5
    )

    private val imageViewIds = listOf(
        R.id.img_preview_1,
        R.id.img_preview_2,
        R.id.img_preview_3,
        R.id.img_preview_4,
        R.id.img_preview_5
    )

    private lateinit var valueTextViews: Map<Int, TextView>

    private val observer = Observer<Pair<Int, String>> { data ->
        Log.d(TAG, "Observer triggered: (${data.first}:${data.second})")
        if (valueTextViews.size <= 5 && data.first >= 0) {
            val twQty = valueTextViews[data.first]
            if (twQty != null){
                val base = data.second.toIntOrNull() ?: 0
                twQty.text = getString(R.string.golem_base_content, base)
            } else {
                Log.e(TAG, "Received out of range index: ${data.first}")
            }
        }
    }

    companion object {
        var isResetting = false
        const val TAG = "GOLEM_WIDGET"
    }

    inner class LocalBinder : Binder() {
        fun getService(): GolemWidgetService = this@GolemWidgetService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        setupView()
        setupWindowManager()
        sendServiceState(true)
        setupUserInteractionListener()
        setupNotificationHandler()
        setupImageProcessing()
    }

    override fun onDestroy() {
        if (!isResetting) {
            // Save widget position
            val sharedPreferences = getSharedPreferences(Constants.PREF_GOLEM, Context.MODE_PRIVATE)
            sharedPreferences.edit().apply {
                putInt(Constants.KEY_POS_X, layoutParams.x)
                putInt(Constants.KEY_POS_Y, layoutParams.y)
                apply()
            }
        }

        // Cleanup
        tesseractHandler.recycle()
        tesseractHandler.removeObserver(observer)

        isResetting = false
        sendServiceState(false)
        super.onDestroy()
        _binding = null
        windowManager.removeView(floatingView)
    }

    override fun setMediaProjection(mediaProjection: MediaProjection) {
        Log.d(TAG, "setMediaProjection called with: $mediaProjection")
        screenCaptureHandler.setMediaProjection(mediaProjection)
    }

    /* on create functions */
    private fun setupView() {
        val inflater = LayoutInflater.from(this)
        _binding = GolemWidgetLayoutBinding.inflate(inflater)
        floatingView = binding.root
    }

    // Set up the layout parameters for the WindowManager
    private fun setupWindowManager() {
        layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT)
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT)
        }
        layoutParams.gravity = Gravity.TOP or Gravity.START

        setupWidgetPosition()

        // Create the WindowManager instance and add the floating view
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, layoutParams)

        val labelTextViews = labelIds.mapIndexed { index, id ->
            index to floatingView.findViewById<TextView>(id)
        }.toMap()

        labelTextViews.forEach { (index, textView) ->
            textView.text = getString(R.string.golem_base, index + 1)
        }

        valueTextViews = valueIds.mapIndexed { index, id ->
            index to floatingView.findViewById<TextView>(id)
        }.toMap()

        valueTextViews.forEach { (_, textView) ->
            textView.text = getString(R.string.golem_base_content, 0)
        }

        val textViewScore = floatingView.findViewById<TextView>(R.id.golem_score_content)
        textViewScore.text = getString(R.string.golem_score_content, 0)
    }

    private fun setupWidgetPosition() {
        val sharedPreferences = getSharedPreferences(Constants.PREF_GOLEM, Context.MODE_PRIVATE)
        layoutParams.x = sharedPreferences.getInt(Constants.KEY_POS_X, 0)
        layoutParams.y = sharedPreferences.getInt(Constants.KEY_POS_Y, 100)
    }

    // Handle user interaction with the floating view, e.g., dragging or button clicks
    private fun setupUserInteractionListener() {
        setTouchListener()
        setButtonListener()
    }

    // Add persistent notification to remove widget flickering when closing app
    private fun setupNotificationHandler() {
        notificationHandler = NotificationHandler(this, NotificationHandler.NTF_CHAN_ID_GOLEM)
        notificationHandler.createNotificationChannel()
        val notification = notificationHandler.buildNotification()
        startForeground(NotificationHandler.NTF_ID_GOLEM, notification)
    }

    private fun setupImageProcessing() {
        screenCaptureHandler = ScreenCaptureHandler(this)

        tesseractHandler = TesseractHandler()
        tesseractHandler.initTesseract(this)
        tesseractHandler.observeResult(observer)

        imageProcessor = ImageProcessor(resources, tesseractHandler, screenCaptureHandler)
    }

    /* User interaction */
    private fun setTouchListener() {
        floatingView.findViewById<View>(R.id.floating_widget_container).setOnTouchListener(object : View.OnTouchListener {
            private var isClick: Boolean = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                return when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isClick = true
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        isClick = false
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            v?.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }
        })
    }

    private fun setButtonListener() {
        floatingView.findViewById<Button>(R.id.button_golem_action).setOnClickListener {
            Log.d(TAG, "Button clicked")
            calculateScore()
        }
    }

    /* Communication with other activities */
    private fun sendServiceState(isRunning: Boolean) {
        val sharedPreferences = getSharedPreferences(Constants.PREF_SERVICE_STATE, Context.MODE_PRIVATE)
        sharedPreferences.edit().putBoolean(this::class.java.name, isRunning).apply()
    }

    /* Widget logic */
    private fun calculateScore() {
        screenCaptureHandler.setUpVirtualDisplay()
        val start = Handler(Looper.getMainLooper())
        start.postDelayed({
            CoroutineScope(Dispatchers.IO).launch {
                val bitmaps = imageProcessor.startCapture(imageIds, ImageProcessor.TYPE_GOLEM)
                withContext(Dispatchers.Main) {
                    displayCroppedImage(bitmaps)
                    displayScore()
                }
            }
        }, 1000 * 3)
    }

    private fun displayScore() {
        var score = 0
        valueTextViews.forEach { (index, tw) ->
            val stringValue = tw.text.toString()
            val value = stringValue.toIntOrNull() ?: 0
            score += value * points[index]
        }

        val textViewScore = floatingView.findViewById<TextView>(R.id.golem_score_content)
        textViewScore.text = getString(R.string.golem_score_content, score)
    }

    private fun displayCroppedImage(croppedBitmaps: List<Bitmap?>) {
        Log.d(TAG, "Displaying image")
        val imageViews = imageViewIds.mapIndexed { index, id ->
            index to floatingView.findViewById<ImageView>(id)
        }.toMap()

        imageViews.forEach { (index, imageView) ->
            if (index >= croppedBitmaps.size) return
            imageView.setImageBitmap(croppedBitmaps[index])
            imageView.visibility = View.VISIBLE
        }
    }
}