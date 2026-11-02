package com.example.hdmimonitor

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.widget.AspectRatioSurfaceView
import com.serenegiant.usb.Size as UvcSize

class MainActivity : AppCompatActivity() {

    private var cameraHelper: ICameraHelper? = null

    private lateinit var surfaceView: AspectRatioSurfaceView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var controls: View
    private lateinit var modeButton: Button
    private lateinit var audioButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsTask = Runnable {
        controls.visibility = View.GONE
        infoText.visibility = View.GONE
    }

    private var modes: List<VideoMode> = emptyList()
    private var currentMode: VideoMode? = null

    private val audio = AudioPassthrough()
    private var audioWanted = false

    // The welcome dialog shows on every launch (including auto-open from a USB
    // attach). Camera/USB setup waits until it is dismissed.
    private var welcomePending = true
    private var welcomeDialog: AlertDialog? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                initCameraHelper()
            } else {
                showStatus(getString(R.string.status_need_camera_permission))
            }
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableAudio()
            } else {
                Toast.makeText(this, R.string.toast_audio_permission, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface)
        statusText = findViewById(R.id.status)
        infoText = findViewById(R.id.info)
        controls = findViewById(R.id.controls)
        modeButton = findViewById(R.id.btnMode)
        audioButton = findViewById(R.id.btnAudio)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                cameraHelper?.addSurface(holder.surface, false)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraHelper?.removeSurface(holder.surface)
            }
        })

        findViewById<View>(R.id.root).setOnClickListener {
            if (controls.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideControlsTask)
                hideControlsTask.run()
            } else {
                showControls()
            }
        }
        modeButton.setOnClickListener {
            showModeDialog()
            showControls()
        }
        audioButton.setOnClickListener {
            if (audioWanted) {
                audioWanted = false
                audio.stop()
                updateAudioButton()
            } else if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                enableAudio()
            } else {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            showControls()
        }
        updateAudioButton()
        showControls()
        showWelcome()
    }

    override fun onStart() {
        super.onStart()
        if (!welcomePending) startCameraFlow()
    }

    override fun onDestroy() {
        welcomeDialog?.dismiss()
        welcomeDialog = null
        super.onDestroy()
    }

    private fun showWelcome() {
        val view = layoutInflater.inflate(R.layout.dialog_welcome, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        view.findViewById<Button>(R.id.btnOkay).setOnClickListener {
            welcomePending = false
            dialog.dismiss()
            welcomeDialog = null
            startCameraFlow()
        }
        welcomeDialog = dialog
        dialog.show()
    }

    private fun startCameraFlow() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            initCameraHelper()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        super.onStop()
        audio.stop()
        clearCameraHelper()
        showStatus(getString(R.string.status_waiting))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- camera

    private fun initCameraHelper() {
        if (cameraHelper != null) return
        cameraHelper = CameraHelper().also { it.setStateCallback(stateCallback) }
    }

    private fun clearCameraHelper() {
        cameraHelper?.release()
        cameraHelper = null
        currentMode = null
        modes = emptyList()
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            onCameraOpened()
        }

        override fun onCameraClose(device: UsbDevice) {
            cameraHelper?.removeSurface(surfaceView.holder.surface)
        }

        override fun onDeviceClose(device: UsbDevice) {
        }

        override fun onDetach(device: UsbDevice) {
            audio.stop()
            showStatus(getString(R.string.status_unplugged))
        }

        override fun onCancel(device: UsbDevice) {
            showStatus(getString(R.string.status_usb_denied))
        }

        override fun onError(device: UsbDevice, e: CameraException) {
            showStatus(getString(R.string.status_error, e.code))
        }
    }

    private fun onCameraOpened() {
        val helper = cameraHelper ?: return

        // The list of supported formats only exists once the camera is open, so the
        // best mode is chosen here, before preview starts.
        try {
            modes = VideoMode.buildModes(helper.getSupportedFormatList())
            val best = VideoMode.pickBest(modes)
            val current = helper.getPreviewSize()
            if (best != null && (current == null || !best.matches(current))) {
                helper.setPreviewSize(best.toSize())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not select best mode, using camera default", t)
        }

        helper.startPreview()
        val size = helper.getPreviewSize()
        if (size != null) {
            surfaceView.setAspectRatio(size.width, size.height)
            currentMode = modes.firstOrNull { it.matches(size) }
            updateInfo(size)
        }
        helper.addSurface(surfaceView.holder.surface, false)

        statusText.visibility = View.GONE
        showControls()

        if (audioWanted) {
            // Give Android a moment to enumerate the card's USB audio device.
            handler.postDelayed({ startAudioIfWanted() }, 1500)
        }
    }

    // ----------------------------------------------------------------- modes

    private fun showModeDialog() {
        val helper = cameraHelper ?: return
        if (!helper.isCameraOpened() || modes.isEmpty()) return

        val sorted = modes.sortedWith(
            compareByDescending<VideoMode> { it.width * it.height }
                .thenByDescending { it.fps }
                .thenBy { it.isMjpeg }
        )
        val labels = sorted.map { it.label }.toTypedArray()
        val checked = sorted.indexOfFirst { it == currentMode }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_mode_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                applyMode(sorted[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun applyMode(mode: VideoMode) {
        val helper = cameraHelper ?: return
        try {
            helper.stopPreview()
            helper.setPreviewSize(mode.toSize())
            helper.startPreview()
            currentMode = mode
            surfaceView.setAspectRatio(mode.width, mode.height)
            infoText.text = mode.label
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply mode ${mode.label}", t)
        }
    }

    private fun updateInfo(size: UvcSize) {
        val mode = currentMode
        infoText.text = mode?.label ?: "${size.width}x${size.height} @ ${size.fps} fps"
    }

    // ----------------------------------------------------------------- audio

    private fun enableAudio() {
        audioWanted = true
        startAudioIfWanted()
        updateAudioButton()
    }

    private fun startAudioIfWanted() {
        if (!audioWanted || audio.isRunning) return
        if (!audio.start(this)) {
            audioWanted = false
            Toast.makeText(this, R.string.toast_no_usb_audio, Toast.LENGTH_LONG).show()
            updateAudioButton()
        }
    }

    private fun updateAudioButton() {
        audioButton.setText(if (audioWanted) R.string.btn_audio_on else R.string.btn_audio_off)
    }

    // -------------------------------------------------------------------- ui

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        if (currentMode != null || infoText.text.isNotEmpty()) {
            infoText.visibility = View.VISIBLE
        }
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, 4000)
    }

    companion object {
        private const val TAG = "HdmiMonitor"
    }
}
