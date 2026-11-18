package com.funcron.hdmi_in_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.SeekBar
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
import com.serenegiant.usb.Size as UvcSize
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    /** How the picture is fitted to the screen. */
    private enum class ScaleMode { FIT, FILL, STRETCH }

    private var cameraHelper: ICameraHelper? = null

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var controls: View
    private lateinit var modeButton: Button
    private lateinit var scaleButton: Button
    private lateinit var audioButton: Button
    private lateinit var volumeLabel: TextView
    private lateinit var volumeSeek: SeekBar

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsTask = Runnable {
        controls.visibility = View.GONE
        infoText.visibility = View.GONE
    }

    // Refreshes the fps line once a second, but only while the overlay is on screen.
    private val statsTask = object : Runnable {
        override fun run() {
            if (infoText.visibility != View.VISIBLE) return
            refreshInfo()
            handler.postDelayed(this, 1000)
        }
    }

    private var modes: List<VideoMode> = emptyList()
    private var currentMode: VideoMode? = null

    private var scaleMode = ScaleMode.FIT
    private var videoWidth = 0
    private var videoHeight = 0

    private var infoBase = ""

    private val audio = AudioPassthrough()
    private var audioWanted = false

    // Auto swap (see maybeAutoSwap): some capture cards send a loud, peaking audio burst
    // after they connect, and it calms down once the video mode changes. So the first time
    // audio starts, the app hops to a neighbouring resolution and straight back.
    private var autoSwapDone = false
    private var swapOriginal: VideoMode? = null
    private var swapTemp: VideoMode? = null

    private val swapBackTask = Runnable {
        val original = swapOriginal
        val temp = swapTemp
        swapOriginal = null
        swapTemp = null
        // only go back if nobody picked another mode in the meantime
        if (original != null && cameraHelper != null && currentMode == temp) applyMode(original)
    }

    private val swapStartTask = Runnable {
        val original = swapOriginal
        val temp = swapTemp
        if (original == null || temp == null || cameraHelper == null || currentMode != original) {
            swapOriginal = null
            swapTemp = null
            return@Runnable
        }
        applyMode(temp, remember = false)
        handler.postDelayed(swapBackTask, SWAP_HOLD_MS)
    }

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
        scaleButton = findViewById(R.id.btnScale)
        audioButton = findViewById(R.id.btnAudio)
        volumeLabel = findViewById(R.id.volumeLabel)
        volumeSeek = findViewById(R.id.volumeSeek)

        // saved settings
        scaleMode = ScaleMode.values().getOrElse(prefs.getInt(KEY_SCALE, 0)) { ScaleMode.FIT }
        val volume = prefs.getInt(KEY_VOLUME, 100).coerceIn(0, 200)
        audio.gain = volume / 100f
        volumeSeek.progress = volume
        updateVolumeLabel(volume)
        audioWanted = prefs.getBoolean(KEY_AUDIO, false) &&
            hasPermission(Manifest.permission.RECORD_AUDIO)

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

        val root = findViewById<View>(R.id.root)
        root.setOnClickListener {
            // fallback if the Android "Open app?" prompt was dismissed: tap to allow access
            pendingDevice?.let { requestPendingDevice(it) }
            if (controls.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideControlsTask)
                hideControlsTask.run()
            } else {
                showControls()
            }
        }
        // re-fit the picture when the screen size changes (rotation, split screen)
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) applyScale()
        }

        modeButton.setOnClickListener {
            showModeDialog()
            showControls()
        }
        scaleButton.setOnClickListener {
            val all = ScaleMode.values()
            scaleMode = all[(scaleMode.ordinal + 1) % all.size]
            prefs.edit().putInt(KEY_SCALE, scaleMode.ordinal).apply()
            applyScale()
            updateScaleButton()
            showControls()
        }
        audioButton.setOnClickListener {
            if (audioWanted) {
                audioWanted = false
                prefs.edit().putBoolean(KEY_AUDIO, false).apply()
                audio.stop()
                updateAudioButton()
            } else if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                enableAudio()
            } else {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            showControls()
        }
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateVolumeLabel(progress)
                if (fromUser) {
                    audio.gain = progress / 100f
                    prefs.edit().putInt(KEY_VOLUME, progress).apply()
                    showControls()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                showControls()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                showControls()
            }
        })

        updateAudioButton()
        updateScaleButton()
        showControls()
        showWelcome()
    }

    override fun onStart() {
        super.onStart()
        // The camera starts right away, even while the welcome dialog is still on screen.
        startCameraFlow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Accepting Android's "Open app?" prompt (or having it set as default) delivers
        // the attach event here and grants USB access at the same time, so no second
        // "Allow" prompt is needed.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            pendingDevice?.let { handler.post { requestPendingDevice(it) } }
        }
    }

    override fun onDestroy() {
        welcomeDialog?.dismiss()
        welcomeDialog = null
        super.onDestroy()
    }

    // --------------------------------------------------------------- welcome

    private fun appVersionName(): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

    /** Shown on every launch; the camera starts behind it, so it never blocks anything. */
    private fun showWelcome() {
        val view = layoutInflater.inflate(R.layout.dialog_welcome, null)
        // version comes from versionName in app/build.gradle, so it only needs bumping there
        view.findViewById<TextView>(R.id.welcomeVersion).text =
            getString(R.string.welcome_version, appVersionName() ?: "?")
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        view.findViewById<Button>(R.id.btnOkay).setOnClickListener {
            dialog.dismiss()
            welcomeDialog = null
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
        pendingDevice = null
        handler.removeCallbacks(pendingTask)
        handler.removeCallbacks(statsTask)
        cancelAutoSwap()
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

    /** A plugged-in card that is waiting for the user to answer Android's prompt. */
    private var pendingDevice: UsbDevice? = null
    private val pendingTask = Runnable { pendingDevice?.let { requestPendingDevice(it) } }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            val usb = getSystemService(USB_SERVICE) as UsbManager
            if (usb.hasPermission(device)) {
                pendingDevice = null
                cameraHelper?.selectDevice(device)
            } else {
                // Android is probably showing its own "Open app?" prompt right now. Asking
                // for access too would stack a second, nearly identical dialog, so wait for
                // the answer (onNewIntent) instead.
                pendingDevice = device
                showStatus(getString(R.string.status_tap_ok))
                // If no prompt was answered (e.g. the app was opened by hand with the card
                // already plugged in), ask for access ourselves after a short wait.
                handler.removeCallbacks(pendingTask)
                handler.postDelayed(pendingTask, PENDING_WAIT_MS)
            }
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
            pendingDevice = null
            handler.removeCallbacks(pendingTask)
            cancelAutoSwap()
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

    /** Selects the waiting device; the library asks for USB access itself if still needed. */
    private fun requestPendingDevice(device: UsbDevice) {
        handler.removeCallbacks(pendingTask)
        pendingDevice = null
        cameraHelper?.selectDevice(device)
    }

    private fun onCameraOpened() {
        val helper = cameraHelper ?: return
        cancelAutoSwap()
        autoSwapDone = false          // a new connection gets its own swap

        // The list of supported formats only exists once the camera is open, so the
        // mode is chosen here, before preview starts: the one used last time if this
        // card still offers it, otherwise the best automatic pick.
        try {
            modes = VideoMode.selectable(VideoMode.buildModes(helper.getSupportedFormatList()))
            val wanted = preferredMode()
            val current = helper.getPreviewSize()
            if (wanted != null && (current == null || !wanted.matches(current))) {
                helper.setPreviewSize(wanted.toSize())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not select a mode, using camera default", t)
        }

        helper.startPreview()
        val size = helper.getPreviewSize()
        if (size != null) {
            setVideoSize(size.width, size.height)
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

    /** The mode saved from last time if this card has it, otherwise the automatic best. */
    private fun preferredMode(): VideoMode? {
        val w = prefs.getInt(KEY_MODE_W, 0)
        if (w > 0) {
            val h = prefs.getInt(KEY_MODE_H, 0)
            val fps = prefs.getInt(KEY_MODE_FPS, 0)
            val type = prefs.getInt(KEY_MODE_TYPE, -1)
            val saved = modes.firstOrNull {
                it.width == w && it.height == h && it.fps == fps && it.frameType == type
            }
            if (saved != null) return saved
        }
        return VideoMode.pickBest(modes)
    }

    // ----------------------------------------------------------------- modes

    /**
     * Two lists in one dialog: resolutions on the left, the frame rates the selected
     * resolution supports on the right (limited to 15/20/24/25/30/50/60). Nothing changes
     * until Apply is pressed.
     */
    private fun showModeDialog() {
        val helper = cameraHelper ?: return
        if (!helper.isCameraOpened() || modes.isEmpty()) return

        val resolutions = VideoMode.resolutions(modes)
        if (resolutions.isEmpty()) return

        val view = layoutInflater.inflate(R.layout.dialog_mode, null)
        val resList = view.findViewById<ListView>(R.id.listResolutions)
        val fpsList = view.findViewById<ListView>(R.id.listFps)

        var selectedRes = currentMode?.resolution?.takeIf { it in resolutions } ?: resolutions.first()
        var selectedFps: Int? = currentMode?.fps
        var fpsOptions: List<Int> = emptyList()

        fun refreshFpsList() {
            fpsOptions = modes.filter { it.resolution == selectedRes }.map { it.fps }.distinct().sorted()
            // keep the frame rate when the new resolution offers it, otherwise the closest one
            val wanted = selectedFps ?: fpsOptions.lastOrNull() ?: 0
            selectedFps = fpsOptions.minByOrNull { abs(it - wanted) }
            fpsList.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_single_choice,
                fpsOptions.map { getString(R.string.fps_item, it) }
            )
            val index = fpsOptions.indexOf(selectedFps)
            if (index >= 0) {
                fpsList.setItemChecked(index, true)
                fpsList.setSelection(index)
            }
        }

        resList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            resolutions.map { it.label }
        )
        val resIndex = resolutions.indexOf(selectedRes)
        resList.setItemChecked(resIndex, true)
        resList.setSelection(resIndex)
        resList.setOnItemClickListener { _, _, position, _ ->
            selectedRes = resolutions[position]
            refreshFpsList()
        }
        fpsList.setOnItemClickListener { _, _, position, _ ->
            selectedFps = fpsOptions[position]
        }
        refreshFpsList()

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_mode_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_apply) { _, _ ->
                val chosen = modes.firstOrNull { it.resolution == selectedRes && it.fps == selectedFps }
                if (chosen != null && chosen != currentMode) applyMode(chosen)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun applyMode(mode: VideoMode, remember: Boolean = true) {
        val helper = cameraHelper ?: return
        try {
            helper.stopPreview()
            helper.setPreviewSize(mode.toSize())
            helper.startPreview()
            currentMode = mode
            setVideoSize(mode.width, mode.height)
            setInfoBase(mode.label)
            if (remember) {
                prefs.edit()
                    .putInt(KEY_MODE_W, mode.width)
                    .putInt(KEY_MODE_H, mode.height)
                    .putInt(KEY_MODE_FPS, mode.fps)
                    .putInt(KEY_MODE_TYPE, mode.frameType)
                    .apply()
            }
            // the video stream just restarted, which is when some cards send a loud burst
            audio.fadeIn()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply mode ${mode.label}", t)
        }
    }

    private fun updateInfo(size: UvcSize) {
        val mode = currentMode
        setInfoBase(mode?.label ?: "${size.width}x${size.height} @ ${size.fps} fps")
    }

    // ----------------------------------------------------------------- scale

    private fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        applyScale()
    }

    /**
     * Fit: whole picture visible, black bars where the shapes differ.
     * Fill: picture covers the screen, edges are cropped.
     * Stretch: picture is distorted to exactly match the screen.
     */
    private fun applyScale() {
        val root = findViewById<View>(R.id.root) ?: return
        val cw = root.width
        val ch = root.height
        if (cw <= 0 || ch <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val w: Int
        val h: Int
        if (scaleMode == ScaleMode.STRETCH) {
            w = cw
            h = ch
        } else {
            val scaleX = cw.toFloat() / videoWidth
            val scaleY = ch.toFloat() / videoHeight
            val scale = if (scaleMode == ScaleMode.FIT) minOf(scaleX, scaleY) else maxOf(scaleX, scaleY)
            w = (videoWidth * scale).roundToInt()
            h = (videoHeight * scale).roundToInt()
        }

        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    private fun updateScaleButton() {
        scaleButton.setText(
            when (scaleMode) {
                ScaleMode.FIT -> R.string.btn_scale_fit
                ScaleMode.FILL -> R.string.btn_scale_fill
                ScaleMode.STRETCH -> R.string.btn_scale_stretch
            }
        )
    }

    // ----------------------------------------------------------------- audio

    private fun enableAudio() {
        audioWanted = true
        prefs.edit().putBoolean(KEY_AUDIO, true).apply()
        startAudioIfWanted()
        updateAudioButton()
    }

    private fun startAudioIfWanted() {
        if (!audioWanted || audio.isRunning) return
        if (!audio.start(this)) {
            audioWanted = false
            Toast.makeText(this, R.string.toast_no_usb_audio, Toast.LENGTH_LONG).show()
            updateAudioButton()
        } else {
            maybeAutoSwap()
        }
    }

    /**
     * Once per camera connection, right after audio first starts: switch to the next lower
     * resolution (same pixel format, nearest frame rate), then back to the mode that was
     * active. If the current one is already the lowest, the next higher one is used.
     * Backs off if the user changes mode meanwhile, and the swap itself is never saved
     * as the remembered mode.
     */
    private fun maybeAutoSwap() {
        if (autoSwapDone) return
        autoSwapDone = true
        val original = currentMode ?: return
        val area = original.width * original.height
        val sameFormat = VideoMode.resolutions(modes).filter { it.frameType == original.frameType }
        val other = sameFormat.firstOrNull { it.width * it.height < area }
            ?: sameFormat.lastOrNull { it.width * it.height > area }
            ?: return
        val temp = modes.filter { it.resolution == other }
            .minByOrNull { abs(it.fps - original.fps) } ?: return
        swapOriginal = original
        swapTemp = temp
        handler.postDelayed(swapStartTask, SWAP_DELAY_MS)
    }

    private fun cancelAutoSwap() {
        handler.removeCallbacks(swapStartTask)
        handler.removeCallbacks(swapBackTask)
        swapOriginal = null
        swapTemp = null
    }

    private fun updateAudioButton() {
        audioButton.setText(if (audioWanted) R.string.btn_audio_on else R.string.btn_audio_off)
    }

    private fun updateVolumeLabel(percent: Int) {
        volumeLabel.text = getString(R.string.volume_label, percent)
    }

    // -------------------------------------------------------------------- ui

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun setInfoBase(text: String) {
        infoBase = text
        refreshInfo()
    }

    /** Mode label, plus a live fps line while the overlay is visible. */
    private fun refreshInfo() {
        val stats = previewStatsText()
        infoText.text = if (stats != null) "$infoBase\n$stats" else infoBase
    }

    // Looked up by reflection so the app still builds against the stock UVCAndroid library,
    // which has no such method. The patched library used by the CI build does.
    private val statsMethod: Method? by lazy {
        try {
            Class.forName("com.serenegiant.usb.UVCCamera").getMethod("getPreviewStats")
        } catch (t: Throwable) {
            null
        }
    }

    private fun previewStatsText(): String? {
        val method = statsMethod ?: return null
        return try {
            val s = method.invoke(null) as? LongArray ?: return null
            if (s.size < 4 || s[3] < 0) return null          // nothing measured yet
            if (s[3] > 2500) {
                getString(R.string.stats_no_frames)
            } else {
                String.format(
                    Locale.US, "received %.1f   shown %.1f   dropped %d",
                    s[0] / 10.0, s[1] / 10.0, s[2]
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        if (currentMode != null || infoBase.isNotEmpty()) {
            infoText.visibility = View.VISIBLE
        }
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, 4000)
        handler.removeCallbacks(statsTask)
        if (infoText.visibility == View.VISIBLE) handler.post(statsTask)
    }

    companion object {
        private const val TAG = "HdmiInAndroid"
        private const val SWAP_DELAY_MS = 1000L     // after audio starts, before swapping
        private const val PENDING_WAIT_MS = 3000L
        private const val SWAP_HOLD_MS = 2000L      // time spent on the temporary mode
        private const val KEY_SCALE = "scale"
        private const val KEY_VOLUME = "volume"
        private const val KEY_AUDIO = "audio_on"
        private const val KEY_MODE_W = "mode_w"
        private const val KEY_MODE_H = "mode_h"
        private const val KEY_MODE_FPS = "mode_fps"
        private const val KEY_MODE_TYPE = "mode_type"
    }
}
