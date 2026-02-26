package com.pro.videocam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

data class LensInfo(
    val cameraId: String,
    val label: String,
    val focalLength: Float,
    val facing: Int,
    val isHidden: Boolean = false
)

data class VideoQuality(
    val width: Int,
    val height: Int,
    val label: String
)

class CameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val onStatusChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "CameraController"
        private const val MAX_PROBE_ID = 12
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handlerThread = HandlerThread("CameraThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null

    var isRecording = false
    var currentLens: LensInfo? = null
    var currentResolution: VideoQuality? = null
    var currentFps: Int = 30

    // -1 / -1f = AUTO
    var manualIso: Int = -1
    var manualShutterNs: Long = -1L
    var manualFocusDist: Float = -1f
    var manualWbTemperature: Int = -1

    var hdrEnabled: Boolean = false
    var logFormatEnabled: Boolean = false
    var maxBitrateEnabled: Boolean = true

    private var recordingStartTime: Long = 0L

    // ─── Lens Discovery ───────────────────────────────────────────────────────

    fun discoverAllLenses(): List<LensInfo> {
        val lenses = mutableListOf<LensInfo>()
        val knownIds = mutableSetOf<String>()

        try {
            cameraManager.cameraIdList.forEach { id ->
                knownIds.add(id)
                buildLensInfo(id, false)?.let { lenses.add(it) }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error listing cameras", e)
        }

        // Probe for hidden lenses (e.g. telephoto on Nothing CMF Phone 2 Pro)
        for (i in 0..MAX_PROBE_ID) {
            val probeId = i.toString()
            if (probeId in knownIds) continue
            try {
                buildLensInfo(probeId, true)?.let {
                    Log.d(TAG, "Found hidden camera ID=$probeId label=${it.label}")
                    lenses.add(it)
                    knownIds.add(probeId)
                }
            } catch (_: Exception) {}
        }

        return lenses.sortedWith(compareBy(
            { it.isHidden },
            { it.facing != CameraMetadata.LENS_FACING_BACK },
            { it.focalLength }
        ))
    }

    private fun buildLensInfo(cameraId: String, isHidden: Boolean): LensInfo? {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return null
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f
            val label = makeLensLabel(facing, focal, isHidden)
            LensInfo(cameraId, label, focal, facing, isHidden)
        } catch (_: Exception) { null }
    }

    private fun makeLensLabel(facing: Int, focal: Float, hidden: Boolean): String {
        if (facing == CameraMetadata.LENS_FACING_FRONT) return "Front"
        val zoom = when {
            focal < 2.0f  -> "UW"
            focal < 4.5f  -> "1×"
            focal < 8.0f  -> "2×"
            focal < 15.0f -> "3×"
            else           -> "5×"
        }
        return if (hidden) "★$zoom" else zoom
    }

    fun getAvailableResolutions(cameraId: String): List<VideoQuality> {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            (map.getOutputSizes(MediaRecorder::class.java) ?: return emptyList())
                .filter { it.width >= 640 }
                .sortedByDescending { it.width * it.height }
                .map { sizeToQuality(it) }
                .distinctBy { it.label }
        } catch (_: Exception) { emptyList() }
    }

    private fun sizeToQuality(size: Size): VideoQuality {
        val label = when {
            size.width >= 3840 -> "4K"
            size.width >= 2720 -> "2.7K"
            size.width >= 1920 -> "FHD"
            size.width >= 1280 -> "HD"
            else               -> "${size.height}p"
        }
        return VideoQuality(size.width, size.height, label)
    }

    fun getAvailableFpsRanges(cameraId: String): List<Int> {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            (chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return listOf(30))
                .filter { it.lower == it.upper && it.upper >= 24 }
                .map { it.upper }
                .distinct()
                .sorted()
        } catch (_: Exception) { listOf(30) }
    }

    fun supportsHdr(cameraId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            profiles?.supportedProfiles?.isNotEmpty() == true
        } catch (_: Exception) { false }
    }

    fun getIsoRange(cameraId: String): Range<Int> = try {
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
    } catch (_: Exception) { Range(100, 3200) }

    fun getShutterRange(cameraId: String): Range<Long> = try {
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 1_000_000_000L)
    } catch (_: Exception) { Range(1_000_000L, 1_000_000_000L) }

    fun getMinFocusDist(cameraId: String): Float = try {
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
    } catch (_: Exception) { 10f }

    // ─── Camera Session ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun openCamera(lens: LensInfo, resolution: VideoQuality, fps: Int) {
        currentLens = lens
        currentResolution = resolution
        currentFps = fps
        closeCamera()
        try {
            cameraManager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    onStatusChanged("Camera error $error")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            onStatusChanged("Cannot open camera: ${e.message}")
        }
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        val res = currentResolution ?: return
        val pw = minOf(res.width, 1920)
        val ph = pw * res.height / res.width
        st.setDefaultBufferSize(pw, ph)
        val surface = Surface(st).also { previewSurface = it }

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyManualParameters(builder)
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureRequestBuilder = builder
                    try { session.setRepeatingRequest(builder.build(), null, handler) }
                    catch (e: CameraAccessException) { Log.e(TAG, "Preview error", e) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatusChanged("Preview config failed")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            onStatusChanged("Preview error: ${e.message}")
        }
    }

    // ─── Recording ────────────────────────────────────────────────────────────

    fun startRecording(): File? {
        val camera = cameraDevice ?: return null
        val res = currentResolution ?: return null
        val st = textureView.surfaceTexture ?: return null

        val dir = getOutputDirectory()
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val outputFile = File(dir, "VID_${sdf.format(Date())}.mp4")

        val mr = createMediaRecorder(res, outputFile) ?: return null
        mediaRecorder = mr

        st.setDefaultBufferSize(res.width, res.height)
        val previewSurf = Surface(st).also { previewSurface = it }
        val recorderSurf = mr.surface
        val surfaces = listOf(previewSurf, recorderSurf)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hdrEnabled) {
                startHdrSession(camera, surfaces, res, mr)
            } else {
                startStandardSession(camera, surfaces, res, mr)
            }
        } catch (e: Exception) {
            onStatusChanged("Session error: ${e.message}")
            return null
        }
        return outputFile
    }

    private fun createMediaRecorder(res: VideoQuality, outputFile: File): MediaRecorder? {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                 else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                val useHevc = isHevcSupported()
                setVideoEncoder(if (useHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48000)
                setAudioChannels(2)
                setAudioEncodingBitRate(320_000)
                setVideoSize(res.width, res.height)
                setVideoFrameRate(currentFps)
                setVideoEncodingBitRate(
                    if (maxBitrateEnabled) calcMaxBitrate(res.width, res.height, currentFps, useHevc)
                    else calcStandardBitrate(res.width)
                )
                setOutputFile(outputFile.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            onStatusChanged("Recorder prepare failed: ${e.message}")
            mr.release()
            null
        }
    }

    private fun startStandardSession(camera: CameraDevice, surfaces: List<Surface>, res: VideoQuality, mr: MediaRecorder) {
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).also { b ->
                    surfaces.forEach { b.addTarget(it) }
                    applyManualParameters(b)
                    applyLogFormat(b)
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))
                }
                captureRequestBuilder = builder
                try {
                    session.setRepeatingRequest(builder.build(), null, handler)
                    mr.start()
                    isRecording = true
                    recordingStartTime = SystemClock.elapsedRealtime()
                    onStatusChanged("● REC  ${res.label} @ ${currentFps}fps")
                } catch (e: Exception) { onStatusChanged("Record start error: ${e.message}") }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                onStatusChanged("Record session config failed")
            }
        }, handler)
    }

    @SuppressLint("NewApi")
    private fun startHdrSession(camera: CameraDevice, surfaces: List<Surface>, res: VideoQuality, mr: MediaRecorder) {
        val chars = cameraManager.getCameraCharacteristics(currentLens!!.cameraId)
        val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        val supported = profiles?.supportedProfiles ?: emptySet()

        val hdrProfile = when {
            supported.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) ->
                android.hardware.camera2.params.DynamicRangeProfiles.HLG10
            supported.contains(android.hardware.camera2.params.DynamicRangeProfiles.HDR10) ->
                android.hardware.camera2.params.DynamicRangeProfiles.HDR10
            else -> null
        }

        if (hdrProfile == null) {
            onStatusChanged("HDR not supported, using SDR")
            startStandardSession(camera, surfaces, res, mr)
            return
        }

        val outputConfigs = surfaces.map { surface ->
            OutputConfiguration(surface).apply { dynamicRangeProfile = hdrProfile }
        }
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).also { b ->
                        surfaces.forEach { b.addTarget(it) }
                        applyManualParameters(b)
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))
                    }
                    captureRequestBuilder = builder
                    try {
                        session.setRepeatingRequest(builder.build(), null, handler)
                        mr.start()
                        isRecording = true
                        recordingStartTime = SystemClock.elapsedRealtime()
                        onStatusChanged("● HDR REC  ${res.label} @ ${currentFps}fps")
                    } catch (e: Exception) { onStatusChanged("HDR start error: ${e.message}") }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatusChanged("HDR session failed, falling back to SDR")
                    startStandardSession(camera, surfaces, res, mr)
                }
            }
        )
        camera.createCaptureSession(sessionConfig)
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { mediaRecorder?.stop(); mediaRecorder?.reset(); mediaRecorder?.release() } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder stop error", e)
        }
        mediaRecorder = null
        currentLens?.let { lens -> currentResolution?.let { res -> openCamera(lens, res, currentFps) } }
    }

    // ─── Manual Parameters ────────────────────────────────────────────────────

    fun applyManualParameters(builder: CaptureRequest.Builder) {
        when {
            manualIso > 0 -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                val shutterNs = if (manualShutterNs > 0) manualShutterNs else 1_000_000_000L / currentFps
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            }
            manualShutterNs > 0 -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
                val isoRange = getIsoRange(currentLens?.cameraId ?: return)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange.lower)
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
        }

        if (manualFocusDist >= 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDist)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        if (manualWbTemperature > 0) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            val (rg, bg) = tempToGains(manualWbTemperature)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS,
                android.hardware.camera2.params.RggbChannelVector(rg, 1f, 1f, bg))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    }

    private fun applyLogFormat(builder: CaptureRequest.Builder) {
        if (!logFormatEnabled) return
        val curve = FloatArray(64)
        for (i in 0 until 32) {
            val x = i / 31f
            val y = if (x < 0.01f) x * 3f
                    else (Math.log10(x * 9.0 + 1.0) / Math.log10(10.0)).toFloat() * 0.8f + 0.01f
            curve[i * 2] = x
            curve[i * 2 + 1] = y.coerceIn(0f, 1f)
        }
        val tc = android.hardware.camera2.params.TonemapCurve(curve, curve, curve)
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        builder.set(CaptureRequest.TONEMAP_CURVE, tc)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
    }

    fun updateParameters() {
        val builder = captureRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            applyManualParameters(builder)
            applyLogFormat(builder)
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (_: CameraAccessException) {}
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun tempToGains(k: Int): Pair<Float, Float> {
        val t = k.toFloat()
        val rg = when {
            t <= 3000f -> 1.8f
            t <= 5500f -> 1.0f + (t - 3000f) / 2500f * 0.3f
            else       -> 0.85f + (t - 5500f) / 3000f * 0.3f
        }.coerceIn(0.5f, 3f)
        val bg = when {
            t <= 3000f -> 0.7f
            t <= 5500f -> 0.7f + (t - 3000f) / 2500f * 0.5f
            else       -> 1.2f + (t - 5500f) / 3000f * 0.5f
        }.coerceIn(0.5f, 3f)
        return Pair(rg, bg)
    }

    private fun calcMaxBitrate(w: Int, h: Int, fps: Int, hevc: Boolean): Int {
        val mult = if (hevc) 0.6f else 1.0f
        return (w * h * fps / 8 * mult).toInt().coerceIn(8_000_000, 160_000_000)
    }

    private fun calcStandardBitrate(w: Int): Int = when {
        w >= 3840 -> 60_000_000
        w >= 2560 -> 30_000_000
        w >= 1920 -> 20_000_000
        else      -> 10_000_000
    }

    private fun isHevcSupported(): Boolean =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
            !codec.isEncoder && codec.supportedTypes.any { it.contains("hevc", true) }
        }

    fun getOutputDirectory(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
            "ProCam"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRecordingElapsedMs() = if (isRecording) SystemClock.elapsedRealtime() - recordingStartTime else 0L

    fun closeCamera() {
        try { captureSession?.close(); captureSession = null } catch (_: Exception) {}
        try { cameraDevice?.close(); cameraDevice = null } catch (_: Exception) {}
    }

    fun release() {
        closeCamera()
        mediaRecorder?.release(); mediaRecorder = null
        handlerThread.quitSafely()
        cameraExecutor.shutdown()
    }
}
