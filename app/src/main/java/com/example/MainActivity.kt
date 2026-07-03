package com.example

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var viewModelInstance: HarmonographViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Hide status bars and navigation bars for immersive, distraction-free drawing
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            MyApplicationTheme {
                val viewModel: HarmonographViewModel = viewModel()
                viewModelInstance = viewModel
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HarmonographAppScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppVisible = true
        val prefs = getSharedPreferences("harmonograph_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("app_active", true).apply()
        
        val proj = prefs.getFloat("draw_progress", 0f)
        val isD = prefs.getBoolean("is_drawing", true)
        viewModelInstance?.let { vm ->
            vm.loadActiveSettingsFromPrefs()
            vm.setDrawingState(isD)
            vm.jumpToProgress(proj)
        }

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        isAppVisible = false
        val prefs = getSharedPreferences("harmonograph_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("app_active", false).apply()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val vm = viewModelInstance ?: return
        val settings = vm.uiState.value.settings
        if (settings.gyroEnabled) {
            val sensitivity = settings.gyroSensitivity.current
            val pyDelta = Math.toDegrees(event.values[0].toDouble()).toFloat() * 0.016f * sensitivity * 2.5f
            val ywDelta = Math.toDegrees(event.values[1].toDouble()).toFloat() * 0.016f * sensitivity * 2.5f
            vm.addGyroOffsets(-ywDelta, -pyDelta)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        @Volatile var isAppVisible = false
    }
}
