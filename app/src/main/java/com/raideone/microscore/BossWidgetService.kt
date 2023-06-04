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
import com.raideone.microscore.databinding.BossWidgetLayoutBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BossWidgetService : Service(), WidgetService {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var tesseractHandler: TesseractHandler
    private lateinit var screenCaptureHandler: ScreenCaptureHandler
    private lateinit var imageProcessor: ImageProcessor

    private lateinit var notificationHandler: NotificationHandler

    private var _binding: BossWidgetLayoutBinding? = null
    private val binding get() = _binding!!

    private var multiplier: Double = 0.0

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0.0f
    private var initialTouchY: Float = 0.0f

    private val binder = LocalBinder()

    private val imageIds = listOf(
        R.drawable.boss_point
    )

    private val observer = Observer<Pair<Int, String>> { data ->

        Log.d(GolemWidgetService.TAG, "Observer triggered: (${data.first}:${data.second})")
        if (data.first >= 0) {
            val textViewBase = floatingView.findViewById<TextView>(R.id.boss_base_content)
            val textViewScore = floatingView.findViewById<TextView>(R.id.boss_score_content)

            val base = data.second.toIntOrNull() ?: 0
            val score = (base * multiplier).toInt()

            textViewBase.text = getString(R.string.boss_base_content, base)
            textViewScore.text = getString(R.string.boss_score_content, score)
        }
    }

    companion object {
        var isResetting = false
        const val TAG = "BOSS_WIDGET"
    }

    inner class LocalBinder : Binder() {
        fun getService(): BossWidgetService = this@BossWidgetService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        setupView()
        setupWindowManager()
        sendServiceState(true)
        setupMultiplier()
        setupUserInteractionListener()
        setupNotificationHandler()
        setupImageProcessing()
    }

    override fun onDestroy() {
        if (!isResetting) {
            // Save widget position
            val sharedPreferences = getSharedPreferences(Constants.PREF_BOSS, Context.MODE_PRIVATE)
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
        _binding = BossWidgetLayoutBinding.inflate(inflater)
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
    }

    private fun setupWidgetPosition() {
        val sharedPreferences = getSharedPreferences(Constants.PREF_BOSS, Context.MODE_PRIVATE)
        layoutParams.x = sharedPreferences.getInt(Constants.KEY_POS_X, 0)
        layoutParams.y = sharedPreferences.getInt(Constants.KEY_POS_Y, 100)
    }

    private fun setupMultiplier() {
        val sharedPreferences = getSharedPreferences(Constants.PREF_BOSS, Context.MODE_PRIVATE)
        val storedSpinnerValue = sharedPreferences.getInt(Constants.KEY_SPINNER_VALUE, 0)

        multiplier = (storedSpinnerValue / 100.0) + 1
        binding.bossMultiplierContent.text = getString(R.string.boss_multiplier_content, multiplier)

        binding.bossScoreContent.text = getString(R.string.boss_score_content, 0)
        binding.bossBaseContent.text = getString(R.string.boss_base_content, 0)
    }

    // Handle user interaction with the floating view, e.g., dragging or button clicks
    private fun setupUserInteractionListener() {
        setTouchListener()
        setButtonListener()
    }

    // Add persistent notification to remove widget flickering when closing app
    private fun setupNotificationHandler() {
        notificationHandler = NotificationHandler(this, NotificationHandler.NTF_CHAN_ID_BOSS)
        notificationHandler.createNotificationChannel()
        val notification = notificationHandler.buildNotification()
        startForeground(NotificationHandler.NTF_ID_BOSS, notification)
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
        floatingView.findViewById<Button>(R.id.button_boss_action).setOnClickListener {
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
                val bitmaps = imageProcessor.startCapture(imageIds, ImageProcessor.TYPE_BOSS)
                withContext(Dispatchers.Main) {
                    displayCroppedImage(bitmaps)
                }
            }
        }, 1000 * 3)
    }

    /* DEBUGGING */
    private fun displayCroppedImage(croppedBitmaps: List<Bitmap?>) {
        if (croppedBitmaps.isEmpty()) {
            Log.e(TAG, "Error in displayCroppedImage: croppedBitmaps is empty")
            return
        }

        Log.d(TAG, "Displaying image")
        val imgView = floatingView.findViewById<ImageView>(R.id.img_preview)

        imgView.setImageBitmap(croppedBitmaps.first())
        imgView.visibility = View.VISIBLE
    }
}