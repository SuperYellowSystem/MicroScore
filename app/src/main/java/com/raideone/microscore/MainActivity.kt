package com.raideone.microscore

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatButton

class MainActivity : AppCompatActivity() {

    private lateinit var golemMediaProjectionResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var bossMediaProjectionResultLauncher: ActivityResultLauncher<Intent>

    private var golemWidgetService: GolemWidgetService? = null
    private var bossWidgetService: BossWidgetService? = null

    private var pendingServiceClass: Class<*>? = null
    private var pendingServiceConnection: ServiceConnection? = null

    init {
        System.loadLibrary("opencv_java4")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Constants.SPINNER_ITEMS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val spinner: Spinner = findViewById(R.id.spinner)
        spinner.adapter = adapter

        val sharedPref = this.getSharedPreferences(Constants.PREF_BOSS, Context.MODE_PRIVATE)

        val storedSpinnerValue = sharedPref.getInt(Constants.KEY_SPINNER_VALUE, 0)
        val index = Constants.SPINNER_ITEMS.indexOfFirst { it.value == storedSpinnerValue }
        if (index != -1) {
            spinner.setSelection(index)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val itemSelected = parent.getItemAtPosition(position) as SpinnerItem

                with(sharedPref.edit()) {
                    putInt(Constants.KEY_SPINNER_VALUE, itemSelected.value)
                    apply()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // No action taken
            }
        }

        // Setup boss widget
        setupService(
            BossWidgetService::class.java,
            bossServiceConnection,
            ::updateBossButtonText,
            R.id.button_boss
        )

        // Setup golem widget
        setupService(
            GolemWidgetService::class.java,
            golemServiceConnection,
            ::updateGolemButtonText,
            R.id.button_golem
        )

        setupReset()

        setButtonLongestWidth()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceRunning(BossWidgetService::class.java)) {
            unbindService(bossServiceConnection)
        }
        if (isServiceRunning(GolemWidgetService::class.java)) {
            unbindService(golemServiceConnection)
        }
    }

    private fun setupReset() {
        val spinner: Spinner = findViewById(R.id.spinner)
        val button: Button = findViewById(R.id.button_reset)
        button.setOnClickListener {
            // Set resetting flag to true
            BossWidgetService.isResetting = true
            GolemWidgetService.isResetting = true

            // Check if the services are running
            val isBossRunning = isServiceRunning(BossWidgetService::class.java)
            val isGolemRunning = isServiceRunning(GolemWidgetService::class.java)

            // Stop the services
            if (isBossRunning) {
                unbindService(bossServiceConnection)
                stopService(Intent(this, BossWidgetService::class.java))
            }
            if (isGolemRunning) {
                unbindService(golemServiceConnection)
                stopService(Intent(this, GolemWidgetService::class.java))
            }

            // Clear the shared preferences
            val bossSharedPreferences = getSharedPreferences(Constants.PREF_BOSS, Context.MODE_PRIVATE)
            val bossEditor = bossSharedPreferences.edit()
            bossEditor.clear()
            bossEditor.apply()

            val golemSharedPreferences = getSharedPreferences(Constants.PREF_GOLEM, Context.MODE_PRIVATE)
            val golemEditor = golemSharedPreferences.edit()
            golemEditor.clear()
            golemEditor.apply()

            val stateSharedPreferences = getSharedPreferences(Constants.PREF_SERVICE_STATE, Context.MODE_PRIVATE)
            val stateEditor = stateSharedPreferences.edit()
            stateEditor.clear()
            stateEditor.apply()

            // Update buttons
            updateBossButtonText(false)
            updateGolemButtonText(false)

            // Reset spinner
            spinner.setSelection(0)
        }
    }

    /* WIDGET SERVICES */
    private fun setupService(
        serviceClass: Class<*>,
        serviceConnection: ServiceConnection,
        updateButtonText: (Boolean) -> Unit,
        buttonId: Int
    ) {
        val isWidgetDisplayed = isServiceRunning(serviceClass)
        updateButtonText(isWidgetDisplayed)

        if (isWidgetDisplayed) {
            Intent(this, serviceClass).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }

        if(serviceClass == BossWidgetService::class.java) {
            bossMediaProjectionResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handleActivityResult(result, serviceClass, bossWidgetService)
            }
        } else if(serviceClass == GolemWidgetService::class.java) {
            golemMediaProjectionResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handleActivityResult(result, serviceClass, golemWidgetService)
            }
        }

        val button: Button = findViewById(buttonId)
        button.setOnClickListener {
            val isDisplayed = isServiceRunning(serviceClass)

            if (isDisplayed) {
                stopWidgetService(serviceClass, serviceConnection, updateButtonText)
                updateButtonText(false)
            } else {
                // If the other service is running, stop it before starting the current one
                if (serviceClass == GolemWidgetService::class.java && isServiceRunning(BossWidgetService::class.java)) {
                    stopWidgetService(BossWidgetService::class.java, bossServiceConnection, ::updateBossButtonText)
                } else if (serviceClass == BossWidgetService::class.java && isServiceRunning(GolemWidgetService::class.java)) {
                    stopWidgetService(GolemWidgetService::class.java, golemServiceConnection, ::updateGolemButtonText)
                }

                requestSystemAlertWindowPermission(serviceClass, serviceConnection)
                requestMediaProjectionPermission(serviceClass)
                updateButtonText(true)
            }
        }
    }

    private val golemServiceConnection = createServiceConnection(
        onServiceConnected = { service ->
            val binder = service as? GolemWidgetService.LocalBinder
            golemWidgetService = binder?.getService()
            updateGolemButtonText(isServiceRunning(GolemWidgetService::class.java))
        },
        onServiceDisconnected = {
            golemWidgetService = null
        }
    )

    private val bossServiceConnection = createServiceConnection(
        onServiceConnected = { service ->
            val binder = service as? BossWidgetService.LocalBinder
            bossWidgetService = binder?.getService()
            updateBossButtonText(isServiceRunning(BossWidgetService::class.java))
        },
        onServiceDisconnected = {
            bossWidgetService = null
        }
    )

    private fun handleActivityResult(result: ActivityResult, serviceClass: Class<*>, widgetService: WidgetService?) {
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d("DEBUG", "Service class: ${serviceClass.name}")

            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            result.data?.let { data ->
                val mediaProjection = mediaProjectionManager.getMediaProjection(result.resultCode, data)
                if (mediaProjection == null) {
                    Log.d("DEBUG", "mediaProjection is null at creation")
                } else {
                    Log.d("DEBUG", "mp is correctly created, let's go")
                }
                if (widgetService == null) {
                    Log.d("DEBUG", "widgetService is null")
                }
                widgetService?.setMediaProjection(mediaProjection)
            }
        }
    }

    private fun createServiceConnection(
        onServiceConnected: (service: IBinder?) -> Unit,
        onServiceDisconnected: () -> Unit
    ): ServiceConnection {
        return object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                onServiceConnected(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onServiceDisconnected()
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val sharedPreferences = getSharedPreferences(Constants.PREF_SERVICE_STATE, Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean(serviceClass.name, false)
    }

    private fun startWidgetService(serviceClass: Class<*>, serviceConnection: ServiceConnection) {
        Intent(this, serviceClass).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopWidgetService(serviceClass: Class<*>, serviceConnection: ServiceConnection, updateButtonText: (Boolean) -> Unit) {
        val widgetService = when (serviceClass) {
            GolemWidgetService::class.java -> golemWidgetService
            BossWidgetService::class.java -> bossWidgetService
            else -> null
        }

        widgetService?.let {
            unbindService(serviceConnection)
            stopService(Intent(this, serviceClass))
            updateButtonText(false)
        }
    }

    /* REQUEST PERMISSIONS */
    private val systemAlertWindowPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                pendingServiceClass?.let { serviceClass ->
                    pendingServiceConnection?.let { serviceConnection ->
                        startWidgetService(serviceClass, serviceConnection)
                    }
                }
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun requestSystemAlertWindowPermission(serviceClass: Class<*>, serviceConnection: ServiceConnection) {
        if (!Settings.canDrawOverlays(this)) {
            pendingServiceClass = serviceClass
            pendingServiceConnection = serviceConnection
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            systemAlertWindowPermissionLauncher.launch(intent)
        } else {
            startWidgetService(serviceClass, serviceConnection)
        }
    }

    private fun requestMediaProjectionPermission(serviceClass: Class<*>) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        if (serviceClass == BossWidgetService::class.java) {
            bossMediaProjectionResultLauncher.launch(intent)
        } else if (serviceClass == GolemWidgetService::class.java) {
            golemMediaProjectionResultLauncher.launch(intent)
        }
    }

    /* BUTTON */
    private fun updateGolemButtonText(isServiceRunning: Boolean) {
        val button = findViewById<Button>(R.id.button_golem)
        button.text = if (isServiceRunning) {
            getString(R.string.deactivate_btn)
        } else {
            getString(R.string.activate_btn)
        }
    }

    private fun updateBossButtonText(isServiceRunning: Boolean) {
        val button = findViewById<Button>(R.id.button_boss)
        button.text = if (isServiceRunning) {
            getString(R.string.deactivate_btn)
        } else {
            getString(R.string.activate_btn)
        }
    }

    private fun setButtonLongestWidth() {
        val string1 = getString(R.string.deactivate_btn)
        val string2 = getString(R.string.activate_btn)

        // Create an off-screen button
        val measureButton1 = Button(this)
        val measureButton2 = Button(this)
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        measureButton1.text = string1
        measureButton1.measure(widthMeasureSpec, heightMeasureSpec)
        val width1 = measureButton1.measuredWidth

        measureButton2.text = string2
        measureButton2.measure(widthMeasureSpec, heightMeasureSpec)
        val width2 = measureButton2.measuredWidth

        val buttonWidth = if (width1 > width2) width1 else width2

        // Set buttons width
        val bossButton = findViewById<AppCompatButton>(R.id.button_boss)
        val golemButton = findViewById<AppCompatButton>(R.id.button_golem)
        bossButton.layoutParams.width = buttonWidth
        golemButton.layoutParams.width = buttonWidth
    }
}