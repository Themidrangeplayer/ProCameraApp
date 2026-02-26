package com.pro.videocam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.ThumbnailUtils
import android.os.*
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pro.videocam.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController

    private var allLenses = listOf<LensInfo>()
    private var availableResolutions = listOf<VideoQuality>()
    private var availableFps = listOf<Int>()
    private var selectedLens: LensInfo? = null
    private var selectedResIndex = 0
    private var selectedFpsIndex = 0

    private var isoManual = false
    private var shutterManual = false
    private var focusManual = false
    private var wbManual = false
    private var settingsPanelOpen = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = cameraController.getRecordingElapsedMs()
            val s = elapsed / 1000
            binding.recordingTimer.text = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
            timerHandler.postDelayed(this, 500)
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) initCamera()
        else { Toast.makeText(this, "Camera + audio permissions required", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissionsAndInit()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun checkPermissionsAndInit() {
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) initCamera() else permissionsLauncher.launch(missing.toTypedArray())
    }

    private fun initCamera() {
        cameraController = CameraController(this, binding.textureView) { status ->
            runOnUiThread { binding.tvStatusBar.text = status }
        }
        if (binding.textureView.isAvailable) {
            setupCameraAfterTextureReady()
        } else {
            binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = setupCameraAfterTextureReady()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        setupButtons()
    }

    private fun setupCameraAfterTextureReady() {
        allLenses = cameraController.discoverAllLenses()
        if (allLenses.isEmpty()) { Toast.makeText(this, "No cameras found", Toast.LENGTH_SHORT).show(); return }
        buildLensButtons()
        val defaultLens = allLenses.firstOrNull { it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK }
            ?: allLenses.first()
        selectLens(defaultLens)
    }

    // ─── Lens Buttons ─────────────────────────────────────────────────────────

    private fun buildLensButtons() {
        binding.lensContainer.removeAllViews()
        allLenses.forEachIndexed { i, lens ->
            val btn = TextView(this).apply {
                text = lens.label
                textSize = 11f
                setPaddingRelative(20, 10, 20, 10)
                setTextColor(if (lens.isHidden) 0xFFFFD700.toInt() else 0xFFAAAAAA.toInt())
                setBackgroundResource(R.drawable.pill_bg_dark)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener { selectLens(lens) }
            }
            binding.lensContainer.addView(btn)
        }
    }

    private fun selectLens(lens: LensInfo) {
        selectedLens = lens
        allLenses.forEachIndexed { i, l ->
            val v = binding.lensContainer.getChildAt(i) as? TextView ?: return@forEachIndexed
            if (l == lens) { v.setTextColor(0xFF000000.toInt()); v.setBackgroundResource(R.drawable.pill_bg_accent) }
            else { v.setTextColor(if (l.isHidden) 0xFFFFD700.toInt() else 0xFFAAAAAA.toInt()); v.setBackgroundResource(R.drawable.pill_bg_dark) }
        }
        availableResolutions = cameraController.getAvailableResolutions(lens.cameraId)
        availableFps = cameraController.getAvailableFpsRanges(lens.cameraId)
        selectedResIndex = 0
        selectedFpsIndex = availableFps.indexOfFirst { it == 30 }.takeIf { it >= 0 } ?: 0
        buildResolutionButtons()
        buildFpsButtons()
        updateHeaderDisplay()
        updateSliderRanges(lens)
        openCurrentCamera()
    }

    private fun updateSliderRanges(lens: LensInfo) {
        binding.sliderIso.tag = cameraController.getIsoRange(lens.cameraId)
        binding.sliderShutter.tag = cameraController.getShutterRange(lens.cameraId)
        binding.sliderFocus.tag = cameraController.getMinFocusDist(lens.cameraId)
    }

    // ─── Resolution / FPS ─────────────────────────────────────────────────────

    private fun buildResolutionButtons() {
        binding.resolutionContainer.removeAllViews()
        availableResolutions.forEachIndexed { i, res ->
            binding.resolutionContainer.addView(makeChip(res.label, i == selectedResIndex) {
                selectedResIndex = i; buildResolutionButtons(); updateHeaderDisplay(); openCurrentCamera()
            })
        }
    }

    private fun buildFpsButtons() {
        binding.fpsContainer.removeAllViews()
        availableFps.forEachIndexed { i, fps ->
            binding.fpsContainer.addView(makeChip("${fps}fps", i == selectedFpsIndex) {
                selectedFpsIndex = i; buildFpsButtons(); updateHeaderDisplay(); openCurrentCamera()
            })
        }
    }

    private fun makeChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setPaddingRelative(16, 8, 16, 8)
            setTextColor(if (selected) 0xFF000000.toInt() else 0xFFAAAAAA.toInt())
            setBackgroundResource(if (selected) R.drawable.pill_bg_accent else R.drawable.pill_bg_dark)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
            setOnClickListener { onClick() }
        }
    }

    private fun updateHeaderDisplay() {
        binding.tvResolution.text = availableResolutions.getOrNull(selectedResIndex)?.label ?: "—"
        binding.tvFps.text = "${availableFps.getOrNull(selectedFpsIndex) ?: 30}fps"
    }

    private fun openCurrentCamera() {
        val lens = selectedLens ?: return
        val res = availableResolutions.getOrNull(selectedResIndex) ?: return
        val fps = availableFps.getOrNull(selectedFpsIndex) ?: 30
        cameraController.openCamera(lens, res, fps)
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            if (cameraController.isRecording) stopRecording() else startRecording()
        }

        binding.btnFlip.setOnClickListener {
            val currentFacing = selectedLens?.facing ?: return@setOnClickListener
            val opposite = if (currentFacing == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK)
                android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
            else android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
            allLenses.firstOrNull { it.facing == opposite }?.let { selectLens(it) }
        }

        binding.btnGallery.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        binding.btnSettings.setOnClickListener { toggleSettingsPanel() }

        // ISO
        binding.btnAutoIso.setOnClickListener {
            isoManual = !isoManual
            binding.btnAutoIso.text = if (isoManual) "M" else "A"
            binding.sliderIso.visibility = if (isoManual) View.VISIBLE else View.GONE
            if (!isoManual) { cameraController.manualIso = -1; binding.tvIsoValue.text = "AUTO"; cameraController.updateParameters() }
        }
        binding.sliderIso.setOnSeekBarChangeListener(seekListener { progress ->
            val range = cameraController.getIsoRange(selectedLens?.cameraId ?: return@seekListener)
            val iso = range.lower + (range.upper - range.lower) * progress / 100
            cameraController.manualIso = iso
            binding.tvIsoValue.text = "ISO$iso"
            cameraController.updateParameters()
        })

        // Shutter
        binding.btnAutoShutter.setOnClickListener {
            shutterManual = !shutterManual
            binding.btnAutoShutter.text = if (shutterManual) "M" else "A"
            binding.sliderShutter.visibility = if (shutterManual) View.VISIBLE else View.GONE
            if (!shutterManual) { cameraController.manualShutterNs = -1; binding.tvShutterValue.text = "AUTO"; cameraController.updateParameters() }
        }
        binding.sliderShutter.setOnSeekBarChangeListener(seekListener { progress ->
            val range = cameraController.getShutterRange(selectedLens?.cameraId ?: return@seekListener)
            val logMin = Math.log10(range.lower.toDouble())
            val logMax = Math.log10(range.upper.toDouble())
            val ns = Math.pow(10.0, logMin + (logMax - logMin) * progress / 100.0).toLong()
            cameraController.manualShutterNs = ns
            val denom = (1_000_000_000.0 / ns).toInt().coerceAtLeast(1)
            binding.tvShutterValue.text = if (denom <= 1) "1s" else "1/$denom"
            cameraController.updateParameters()
        })

        // Focus
        binding.btnAutoFocus.setOnClickListener {
            focusManual = !focusManual
            binding.btnAutoFocus.text = if (focusManual) "MF" else "AF"
            binding.sliderFocus.visibility = if (focusManual) View.VISIBLE else View.GONE
            if (!focusManual) { cameraController.manualFocusDist = -1f; binding.tvFocusValue.text = "AUTO"; cameraController.updateParameters() }
        }
        binding.sliderFocus.setOnSeekBarChangeListener(seekListener { progress ->
            val maxDist = cameraController.getMinFocusDist(selectedLens?.cameraId ?: return@seekListener)
            val dist = maxDist * progress / 100f
            cameraController.manualFocusDist = dist
            binding.tvFocusValue.text = if (dist < 0.1f) "∞" else "%.1fm".format(1f / dist)
            cameraController.updateParameters()
        })

        // White Balance
        binding.btnAutoWb.setOnClickListener {
            wbManual = !wbManual
            binding.btnAutoWb.text = if (wbManual) "MWB" else "AWB"
            binding.sliderWb.visibility = if (wbManual) View.VISIBLE else View.GONE
            if (!wbManual) { cameraController.manualWbTemperature = -1; binding.tvWbValue.text = "AUTO"; cameraController.updateParameters() }
        }
        binding.sliderWb.setOnSeekBarChangeListener(seekListener { progress ->
            val k = 2000 + 6000 * progress / 100
            cameraController.manualWbTemperature = k
            binding.tvWbValue.text = "${k}K"
            cameraController.updateParameters()
        })

        // Switches
        binding.switchHdr.setOnCheckedChangeListener { _, checked ->
            cameraController.hdrEnabled = checked
            if (checked && !cameraController.supportsHdr(selectedLens?.cameraId ?: return@setOnCheckedChangeListener))
                Toast.makeText(this, "HDR not natively supported — will attempt anyway", Toast.LENGTH_LONG).show()
        }
        binding.switchLog.setOnCheckedChangeListener { _, checked ->
            cameraController.logFormatEnabled = checked
            cameraController.updateParameters()
            if (checked) Toast.makeText(this, "LOG format: flat tonemap active", Toast.LENGTH_SHORT).show()
        }
        binding.switchMaxBitrate.setOnCheckedChangeListener { _, checked ->
            cameraController.maxBitrateEnabled = checked
        }
    }

    private fun seekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) onChanged(progress) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    // ─── Recording ────────────────────────────────────────────────────────────

    private fun startRecording() {
        val file = cameraController.startRecording() ?: run {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show(); return
        }
        binding.btnRecord.setBackgroundResource(R.drawable.record_btn_active)
        binding.recordingDot.visibility = View.VISIBLE
        binding.recordingTimer.visibility = View.VISIBLE
        val blink = AlphaAnimation(1f, 0f).apply { duration = 600; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }
        binding.recordingDot.startAnimation(blink)
        timerHandler.post(timerRunnable)
    }

    private fun stopRecording() {
        cameraController.stopRecording()
        binding.btnRecord.setBackgroundResource(R.drawable.record_btn)
        binding.recordingDot.clearAnimation()
        binding.recordingDot.visibility = View.GONE
        binding.recordingTimer.visibility = View.GONE
        timerHandler.removeCallbacks(timerRunnable)
        Toast.makeText(this, "Video saved to ProCam folder", Toast.LENGTH_SHORT).show()
        updateThumbnail()
    }

    private fun updateThumbnail() {
        Thread {
            val dir = cameraController.getOutputDirectory()
            val last = dir.listFiles()?.filter { it.extension == "mp4" }?.maxByOrNull { it.lastModified() }
            last?.let { f ->
                val bmp = ThumbnailUtils.createVideoThumbnail(f.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                runOnUiThread { bmp?.let { binding.ivThumbnail.setImageBitmap(it) } }
            }
        }.start()
    }

    private fun toggleSettingsPanel() {
        settingsPanelOpen = !settingsPanelOpen
        binding.settingsPanel.animate().translationX(if (settingsPanelOpen) 0f else 300f).setDuration(250).start()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() { super.onResume(); hideSystemUI() }

    override fun onPause() {
        super.onPause()
        if (::cameraController.isInitialized && cameraController.isRecording) stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        if (::cameraController.isInitialized) cameraController.release()
    }
}
