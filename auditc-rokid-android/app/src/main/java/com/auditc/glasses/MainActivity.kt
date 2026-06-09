package com.auditc.glasses

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val healthClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor()
    private val jsonMediaType = "application/json".toMediaType()

    private lateinit var headerConnectionDot: View
    private lateinit var currentStepText: TextView
    private lateinit var statusBoxText: TextView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView
    private lateinit var micButton: View
    private lateinit var logObservationButton: View
    private lateinit var generateReportButton: View

    private lateinit var tts: TextToSpeech
    private var toneGenerator: ToneGenerator? = null
    private var audioRecord: AudioRecord? = null
    private var recordingFuture: Future<*>? = null
    private var watchdogFuture: ScheduledFuture<*>? = null
    private var healthCheckFuture: ScheduledFuture<*>? = null

    private val sopSteps = listOf(
        "Sample Collection",
        "RNA Extraction",
        "Reverse Transcription",
        "PCR Master Mix Preparation",
        "Thermal Cycling",
        "Detection and Analysis",
    )
    private val sessionSteps = sopSteps.mapIndexed { index, name ->
        SessionStepState(stepNumber = index + 1, stepName = name)
    }.toMutableList()
    private var currentStep = 0
    private var technicianName: String? = null
    private var technicianNameTimestamp: String? = null
    private var pendingNameCandidate: String? = null
    private var pendingVoiceMode = VoiceMode.GENERAL
    private var deferredVoiceMode: VoiceMode? = null
    private var sessionStarted = false
    private var recordingActive = false
    private var overlayTimer: Runnable? = null
    private var recordingStopRunnable: Runnable? = null
    private var lastUiHeartbeatMs = 0L
    @Volatile private var restartInProgress = false
    private var isExitingApp = false
    private var lastOverlayBackground: String? = null
    private var lastOverlayText: String? = null
    private var lastOverlayTextSizeSp: Float? = null
    private var lastOverlayVisible = false
    private var backendConnected = false
    private var connectionWarningVisible = false
    private var activeBackend = BACKEND_USB
    private var activeBackendLabel = "USB"

    private val uiHeartbeatRunnable = object : Runnable {
        override fun run() {
            lastUiHeartbeatMs = SystemClock.elapsedRealtime()
            if (!isFinishing && !isDestroyed) {
                handler.postDelayed(this, UI_HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            deferredVoiceMode?.let { beginVoiceCapture(it) }
        } else {
            updateStatusText("(microphone permission denied)")
            showOverlay(
                background = "#CCFF0000",
                text = "MICROPHONE ACCESS REQUIRED",
                durationMs = 2500,
            )
        }
        deferredVoiceMode = null
    }

    private val keyReceiver = KeyReceiver().apply {
        listener = { type ->
            when (type) {
                KeyType.CLICK -> startGeneralVoiceRecognition()
                KeyType.LONG_PRESS -> {
                    if (ensureSessionStarted()) generateReport("pdf")
                }
                KeyType.TWO_FINGER_SINGLE_TAP -> {
                    if (ensureSessionStarted()) startObservationVoiceCapture()
                }
                KeyType.TWO_FINGER_SWIPE_FORWARD -> {
                    if (ensureSessionStarted()) advanceStep(1)
                }
                KeyType.TWO_FINGER_SWIPE_BACK -> {
                    if (ensureSessionStarted()) advanceStep(-1)
                }
                KeyType.AI_START -> {
                    if (ensureSessionStarted()) generateReport("pdf")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        setContentView(R.layout.activity_main)

        headerConnectionDot = findViewById(R.id.connectionDot)
        currentStepText = findViewById(R.id.currentStepText)
        statusBoxText = findViewById(R.id.statusBoxText)
        overlay = findViewById(R.id.overlay)
        overlayText = findViewById(R.id.overlayText)
        micButton = findViewById(R.id.micButton)
        logObservationButton = findViewById(R.id.logObservationButton)
        generateReportButton = findViewById(R.id.generateReportButton)

        micButton.setOnClickListener { startGeneralVoiceRecognition() }
        logObservationButton.setOnClickListener {
            if (ensureSessionStarted()) {
                startObservationVoiceCapture()
            }
        }
        generateReportButton.setOnClickListener {
            if (ensureSessionStarted()) {
                generateReport("pdf")
            }
        }

        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.ENGLISH
            }
        }

        registerReceiver(
            keyReceiver,
            IntentFilter().apply {
                KeyType.entries.forEach { addAction(it.action) }
                priority = 100
            }
        )

        restoreProgressState(savedInstanceState)
        updateStepDisplay()
        updateConnectionStatus(false)
        setControlsEnabled(sessionStarted)
        if (!sessionStarted) {
            updateStatusText("(awaiting technician name)")
        }

        setupCxrBridge()
        uiScope.launch {
            val backendMode = withContext(Dispatchers.IO) { requestHealthStatus() }
            applyBackendMode(backendMode, announce = true)
            if (savedInstanceState == null && !sessionStarted) {
                startTechnicianNameFlow()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startWatchdog()
        startHealthChecks()
    }

    override fun onPause() {
        stopHealthChecks()
        stopWatchdog()
        super.onPause()
        if (!restartInProgress && !isExitingApp && !isFinishing && !isDestroyed) {
            handler.post {
                try {
                    getSystemService(ActivityManager::class.java)
                        ?.appTasks
                        ?.firstOrNull()
                        ?.moveToFront()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveProgressState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreProgressState(savedInstanceState)
        updateStepDisplay()
        setControlsEnabled(sessionStarted)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(keyReceiver)
        } catch (_: Exception) {
        }
        stopWatchdog()
        stopRecordingSession()
        stopHealthChecks()
        overlayTimer?.let { handler.removeCallbacks(it) }
        toneGenerator?.release()
        toneGenerator = null
        uiScope.cancel()
        audioExecutor.shutdownNow()
        watchdogExecutor.shutdownNow()
        if (::tts.isInitialized) {
            tts.shutdown()
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                startGeneralVoiceRecognition()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun startWatchdog() {
        lastUiHeartbeatMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(uiHeartbeatRunnable)
        handler.post(uiHeartbeatRunnable)
        watchdogFuture?.cancel(false)
        watchdogFuture = watchdogExecutor.scheduleAtFixedRate(
            {
                val elapsed = SystemClock.elapsedRealtime() - lastUiHeartbeatMs
                if (!restartInProgress && elapsed > UI_WATCHDOG_TIMEOUT_MS) {
                    requestActivityRestart()
                }
            },
            UI_WATCHDOG_TIMEOUT_MS,
            UI_WATCHDOG_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(uiHeartbeatRunnable)
        watchdogFuture?.cancel(true)
        watchdogFuture = null
    }

    private fun startHealthChecks() {
        healthCheckFuture?.cancel(false)
        healthCheckFuture = watchdogExecutor.scheduleAtFixedRate(
            {
                val backendMode = requestHealthStatus()
                handler.post { applyBackendMode(backendMode, announce = false) }
            },
            HEALTH_CHECK_INITIAL_DELAY_MS,
            HEALTH_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopHealthChecks() {
        healthCheckFuture?.cancel(true)
        healthCheckFuture = null
    }

    private fun requestHealthStatus(): BackendMode {
        return when {
            probeBackend(BACKEND_USB) -> BackendMode.USB
            probeBackend(BACKEND_WIFI) -> BackendMode.WIFI
            else -> BackendMode.DISCONNECTED
        }
    }

    private fun probeBackend(baseUrl: String): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()
        return try {
            healthClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun applyBackendMode(mode: BackendMode, announce: Boolean) {
        val previousBackend = activeBackend
        when (mode) {
            BackendMode.USB -> {
                activeBackend = BACKEND_USB
                activeBackendLabel = "USB"
                backendConnected = true
                connectionWarningVisible = false
                updateConnectionStatus(true)
                updateStatusText(technicianName?.let { "Technician: $it" } ?: "Connected via USB")
                if (announce || previousBackend != BACKEND_USB) {
                    showOverlay(
                        background = "#CC00FF41",
                        text = "Connected via USB",
                        durationMs = 1600,
                        textSizeSp = 28f,
                    )
                }
            }
            BackendMode.WIFI -> {
                activeBackend = BACKEND_WIFI
                activeBackendLabel = "WiFi"
                backendConnected = true
                connectionWarningVisible = false
                updateConnectionStatus(true)
                updateStatusText(technicianName?.let { "Technician: $it" } ?: "Connected via WiFi")
                if (announce || previousBackend != BACKEND_WIFI) {
                    showOverlay(
                        background = "#CC00FF41",
                        text = "Connected via WiFi",
                        durationMs = 1600,
                        textSizeSp = 28f,
                    )
                }
            }
            BackendMode.DISCONNECTED -> {
                backendConnected = false
                updateConnectionStatus(false)
                updateStatusText("Connection lost")
                if (!connectionWarningVisible) {
                    connectionWarningVisible = true
                    showPersistentOverlay(
                        background = "#CCFF0000",
                        text = CONNECTION_LOST_MESSAGE,
                        textSizeSp = 28f,
                    )
                }
            }
        }
    }

    private fun requestActivityRestart() {
        if (restartInProgress || isFinishing || isDestroyed) {
            return
        }
        restartInProgress = true
        handler.post {
            showPersistentOverlay(
                background = "#CC000000",
                text = "Restarting...",
                textSizeSp = 30f,
            )
            handler.postDelayed(
                {
                    if (!isFinishing && !isDestroyed) {
                        recreate()
                    }
                },
                RESTART_OVERLAY_DURATION_MS,
            )
        }
    }

    private fun ensureSessionStarted(): Boolean {
        if (sessionStarted) {
            return true
        }
        startTechnicianNameFlow()
        return false
    }

    private fun startGeneralVoiceRecognition() {
        if (ensureSessionStarted()) {
            beginVoiceCapture(VoiceMode.GENERAL)
        }
    }

    private fun startObservationVoiceCapture() {
        beginVoiceCapture(VoiceMode.OBSERVATION)
    }

    private fun startTechnicianNameFlow() {
        if (sessionStarted || isFinishing || isDestroyed) {
            return
        }
        pendingNameCandidate = null
        beginVoiceCapture(VoiceMode.CAPTURE_NAME)
    }

    private fun beginVoiceCapture(mode: VoiceMode) {
        pendingVoiceMode = mode
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            deferredVoiceMode = mode
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        showPromptOverlay(mode)
        playBeep()

        recordingStopRunnable?.let { handler.removeCallbacks(it) }
        handler.postDelayed(
            { startAudioRecording() },
            BEEP_DELAY_MS,
        )
    }

    private fun startAudioRecording() {
        if (recordingActive) {
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            handleRecognitionFailure("Microphone unavailable.")
            return
        }

        val bufferSize = max(minBufferSize, SAMPLE_RATE_HZ * 2)
        val outputFile = File(cacheDir, "voice-input-${System.currentTimeMillis()}.wav")

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                handleRecognitionFailure("Microphone unavailable.")
                return
            }

            audioRecord = recorder
            recordingActive = true
            updateStatusText(
                when (pendingVoiceMode) {
                    VoiceMode.CAPTURE_NAME -> "Listening for technician name..."
                    VoiceMode.CONFIRM_NAME -> "Listening for confirmation..."
                    VoiceMode.OBSERVATION -> "Listening for observation..."
                    VoiceMode.GENERAL -> "Listening..."
                }
            )

            recorder.startRecording()
            recordingFuture = audioExecutor.submit {
                writeWaveFile(recorder, outputFile, bufferSize)
            }

            val stopRunnable = Runnable { stopAudioRecording(outputFile) }
            recordingStopRunnable = stopRunnable
            handler.postDelayed(stopRunnable, RECORDING_DURATION_MS)
        } catch (_: Exception) {
            stopRecordingSession()
            handleRecognitionFailure("Microphone unavailable.")
        }
    }

    private fun stopAudioRecording(outputFile: File) {
        val recorder = audioRecord ?: return
        recordingActive = false
        recordingStopRunnable?.let { handler.removeCallbacks(it) }
        recordingStopRunnable = null
        audioRecord = null

        try {
            recorder.stop()
        } catch (_: Exception) {
        }
        recorder.release()

        val future = recordingFuture
        recordingFuture = null
        updateStatusText("Transcribing...")
        showPersistentOverlay(
            background = "#CC000000",
            text = "TRANSCRIBING...",
            textSizeSp = 30f,
        )

        uiScope.launch {
            val transcript = withContext(Dispatchers.IO) {
                try {
                    future?.get()
                } catch (_: Exception) {
                }
                requestTranscript(outputFile)
            }
            outputFile.delete()
            if (transcript.isNullOrBlank()) {
                handleRecognitionFailure("Transcription failed.")
            } else {
                updateStatusText(transcript)
                routeRecognizedText(transcript)
            }
        }
    }

    private fun stopRecordingSession() {
        recordingActive = false
        recordingStopRunnable?.let { handler.removeCallbacks(it) }
        recordingStopRunnable = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        recordingFuture?.cancel(true)
        recordingFuture = null
    }

    private fun requestTranscript(audioFile: File): String? {
        for (backend in orderedBackends()) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url("$backend/transcribe")
                .post(requestBody)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use
                    }
                    val payload = JSONObject(response.body?.string().orEmpty())
                    val transcript = payload.optString("text")
                        .ifBlank { payload.optString("transcript") }
                        .trim()
                        .ifBlank { null }
                    if (transcript != null) {
                        markBackendUsed(backend)
                        return transcript
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun writeWaveFile(recorder: AudioRecord, outputFile: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        FileOutputStream(outputFile).use { output ->
            output.write(ByteArray(WAV_HEADER_SIZE))
            while (recordingActive) {
                val bytesRead = recorder.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    output.write(buffer, 0, bytesRead)
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    break
                }
            }
            output.flush()
        }
        updateWaveHeader(outputFile)
    }

    private fun updateWaveHeader(audioFile: File) {
        val totalAudioLen = audioFile.length() - WAV_HEADER_SIZE
        val totalDataLen = totalAudioLen + 36
        val byteRate = SAMPLE_RATE_HZ * CHANNEL_COUNT * BITS_PER_SAMPLE / 8

        RandomAccessFile(audioFile, "rw").use { file ->
            file.seek(0)
            file.writeBytes("RIFF")
            file.writeIntLE(totalDataLen.toInt())
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            file.writeIntLE(16)
            file.writeShortLE(1)
            file.writeShortLE(CHANNEL_COUNT.toShort())
            file.writeIntLE(SAMPLE_RATE_HZ)
            file.writeIntLE(byteRate)
            file.writeShortLE((CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort())
            file.writeShortLE(BITS_PER_SAMPLE.toShort())
            file.writeBytes("data")
            file.writeIntLE(totalAudioLen.toInt())
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(
            byteArrayOf(
                (value and 0xff).toByte(),
                ((value shr 8) and 0xff).toByte(),
                ((value shr 16) and 0xff).toByte(),
                ((value shr 24) and 0xff).toByte(),
            )
        )
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        write(
            byteArrayOf(
                (value.toInt() and 0xff).toByte(),
                ((value.toInt() shr 8) and 0xff).toByte(),
            )
        )
    }

    private fun handleRecognitionFailure(message: String) {
        when (pendingVoiceMode) {
            VoiceMode.CAPTURE_NAME -> {
                updateStatusText(message)
                showOverlay(
                    background = "#CCFF0000",
                    text = "NAME NOT HEARD. TRY AGAIN.",
                    durationMs = 1800,
                )
                handler.postDelayed({ startTechnicianNameFlow() }, 1900)
            }
            VoiceMode.CONFIRM_NAME -> {
                updateStatusText(message)
                showOverlay(
                    background = "#CCFF0000",
                    text = "SAY YES OR NO",
                    durationMs = 1800,
                )
                handler.postDelayed({ beginVoiceCapture(VoiceMode.CONFIRM_NAME) }, 1900)
            }
            VoiceMode.OBSERVATION -> {
                updateStatusText(message)
                showOverlay(
                    background = "#CCFF0000",
                    text = "OBSERVATION NOT HEARD",
                    durationMs = 1800,
                )
            }
            VoiceMode.GENERAL -> {
                updateStatusText(message)
                showOverlay(
                    background = "#CCFF0000",
                    text = "VOICE INPUT NOT HEARD",
                    durationMs = 1500,
                )
            }
        }
    }

    private fun showPromptOverlay(mode: VoiceMode) {
        when (mode) {
            VoiceMode.CAPTURE_NAME -> {
                showPersistentOverlay(
                    background = "#CC000000",
                    text = "Say your name after the beep",
                )
                updateStatusText("Preparing name capture...")
            }
            VoiceMode.CONFIRM_NAME -> {
                val candidate = pendingNameCandidate ?: "UNKNOWN"
                showPersistentOverlay(
                    background = "#CC000000",
                    text = "Your name is $candidate\nSay Yes to confirm or No to try again",
                    textSizeSp = 28f,
                )
                updateStatusText("Confirming technician name...")
            }
            VoiceMode.OBSERVATION -> {
                showPersistentOverlay(
                    background = "#CC000000",
                    text = "State your observation after the beep",
                    textSizeSp = 28f,
                )
                updateStatusText("Preparing observation capture...")
            }
            VoiceMode.GENERAL -> {
                showPersistentOverlay(
                    background = "#66000000",
                    text = "Speak now",
                    textSizeSp = 30f,
                )
                updateStatusText("Preparing voice command...")
            }
        }
    }

    private fun playBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS.toInt())
    }

    private fun routeRecognizedText(spokenText: String) {
        when (pendingVoiceMode) {
            VoiceMode.CAPTURE_NAME -> {
                pendingNameCandidate = spokenText
                beginVoiceCapture(VoiceMode.CONFIRM_NAME)
            }
            VoiceMode.CONFIRM_NAME -> handleNameConfirmation(spokenText)
            VoiceMode.OBSERVATION -> {
                hideOverlay()
                logObservation(spokenText)
            }
            VoiceMode.GENERAL -> {
                hideOverlay()
                handleSpokenText(spokenText)
            }
        }
    }

    private fun handleNameConfirmation(spokenText: String) {
        val normalized = spokenText.lowercase(Locale.ENGLISH)
        when {
            normalized.contains("yes") -> {
                val confirmedName = pendingNameCandidate
                if (!confirmedName.isNullOrBlank()) {
                    saveTechnicianName(confirmedName)
                } else {
                    startTechnicianNameFlow()
                }
            }
            normalized.contains("no") -> {
                showOverlay(
                    background = "#CCFF0000",
                    text = "LET'S TRY AGAIN",
                    durationMs = 1500,
                )
                handler.postDelayed({ startTechnicianNameFlow() }, 1600)
            }
            else -> {
                showOverlay(
                    background = "#CCFF0000",
                    text = "SAY YES OR NO",
                    durationMs = 1500,
                )
                handler.postDelayed({ beginVoiceCapture(VoiceMode.CONFIRM_NAME) }, 1600)
            }
        }
    }

    private fun saveTechnicianName(name: String) {
        technicianName = name.trim().ifBlank { null }
        val displayName = technicianName ?: return
        technicianNameTimestamp = currentUtcTimestamp()
        sessionStarted = true
        pendingNameCandidate = null
        setControlsEnabled(true)
        updateStatusText("Technician: $displayName")
        showOverlay(
            background = "#CC00FF41",
            text = "YOUR NAME IS $displayName",
            durationMs = 1800,
            textSizeSp = 28f,
        )
        tts.speak("Welcome $displayName", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun handleSpokenText(spokenText: String) {
        val normalized = spokenText.lowercase(Locale.ENGLISH)
        when {
            normalized.contains("exit app") || normalized.contains("quit app") -> exitApp()
            normalized.contains("generate txt report") || normalized.contains("generate text report") -> generateReport("txt")
            normalized.contains("generate pdf report") -> generateReport("pdf")
            normalized.contains("generate report") -> generateReport("pdf")
            else -> verifyStep(spokenText)
        }
    }

    private fun verifyStep(spokenText: String) {
        showPersistentOverlay(
            background = "#66000000",
            text = "VERIFYING STEP...",
            textSizeSp = 28f,
        )
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { requestStepVerification(spokenText) }
            if (result == null) {
                showFailOverlay()
            } else {
                updateSessionStep(currentStep, spokenText, result.result, result.timestamp, result.stepName)
                if (result.result == "pass") {
                    showPassOverlay()
                    sendResultToPhone(result.result, result.stepName)
                } else {
                    showFailOverlay()
                    sendResultToPhone(result.result, result.stepName)
                }
            }
        }
    }

    private fun requestStepVerification(spokenText: String): StepResultPayload? {
        for (backend in orderedBackends()) {
            val body = JSONObject().apply {
                put("spoken_text", spokenText)
                put("step_number", currentStep + 1)
                technicianName?.let { put("user_name", it) }
            }.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$backend/verify-step")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    markBackendUsed(backend)
                    return StepResultPayload(
                        result = json.optString("result", "fail"),
                        stepName = json.optString("step_name", sessionSteps.getOrNull(currentStep)?.stepName.orEmpty()),
                        timestamp = json.optString("timestamp", currentUtcTimestamp()),
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun logObservation(text: String) {
        showPersistentOverlay(
            background = "#66000000",
            text = "LOGGING OBSERVATION...",
            textSizeSp = 28f,
        )
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { requestObservationLog(text) }
            if (result == null) {
                showFailOverlay()
            } else if (result.flagged) {
                updateSessionStep(currentStep, text, "fail", result.timestamp, null)
                showIssueFlaggedOverlay()
            } else {
                showOverlay(
                    background = "#CC00FF41",
                    text = "OBSERVATION LOGGED",
                    durationMs = 1800,
                )
                tts.speak("Observation logged.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun requestObservationLog(text: String): ObservationResultPayload? {
        for (backend in orderedBackends()) {
            val body = JSONObject().apply {
                put("spoken_text", text)
                put("step_number", currentStep + 1)
                technicianName?.let { put("user_name", it) }
            }.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$backend/log-observation")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val entry = json.optJSONObject("entry")
                    markBackendUsed(backend)
                    return ObservationResultPayload(
                        flagged = json.optBoolean("flagged", false),
                        timestamp = entry?.optString("timestamp", currentUtcTimestamp()) ?: currentUtcTimestamp(),
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun generateReport(format: String) {
        val effectiveName = technicianName ?: "Audit C Glasses"
        showPersistentOverlay(
            background = "#66000000",
            text = "Generating report...",
            textSizeSp = 28f,
        )
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { requestReportGeneration(format, effectiveName) }
            if (result == null || !result.errorMessage.isNullOrBlank()) {
                showOverlay(
                    background = "#CCFF0000",
                    text = "Report failed - check Mac terminal",
                    durationMs = 2500,
                    textSizeSp = 28f,
                )
            } else {
                val fileName = result.fileName ?: result.reportPath ?: "report.$format"
                updateStatusText(fileName)
                showOverlay(
                    background = "#CC00FF41",
                    text = "Report saved on Mac - $fileName",
                    durationMs = 3000,
                    textSizeSp = 24f,
                )
                tts.speak(
                    "$format report generated successfully.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    null,
                )
            }
        }
    }

    private fun requestReportGeneration(format: String, effectiveName: String): ReportResultPayload? {
        val sessionJson = buildSessionJson(effectiveName)
        for (backend in orderedBackends()) {
            val body = JSONObject().apply {
                put("technician_name", effectiveName)
                put("user_name", effectiveName)
                put("format", format)
                put("session", sessionJson)
            }.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$backend/generate-report")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    markBackendUsed(backend)
                    return ReportResultPayload(
                        fileName = json.optString("file_name").ifBlank { null },
                        reportPath = json.optString("report_path").ifBlank { null },
                        errorMessage = json.optString("error").ifBlank { null },
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun exitApp() {
        if (isExitingApp) {
            return
        }
        isExitingApp = true
        showPersistentOverlay(
            background = "#CC000000",
            text = "Goodbye",
            textSizeSp = 30f,
        )
        handler.postDelayed(
            { finish() },
            EXIT_OVERLAY_DURATION_MS,
        )
    }

    private fun setControlsEnabled(enabled: Boolean) {
        micButton.isEnabled = enabled
        logObservationButton.isEnabled = enabled
        generateReportButton.isEnabled = enabled
        micButton.alpha = if (enabled) 1f else 0.45f
        logObservationButton.alpha = if (enabled) 1f else 0.45f
        generateReportButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateConnectionStatus(connected: Boolean) {
        val color = if (connected) "#00FF41" else "#FF0000"
        headerConnectionDot.setBackgroundColor(Color.parseColor(color))
    }

    private fun updateStepDisplay() {
        val step = sessionSteps.getOrNull(currentStep)
        val label = if (step != null) {
            "Step ${step.stepNumber}: ${step.stepName}"
        } else {
            "(no step)"
        }
        setTextIfChanged(currentStepText, label)
    }

    private fun advanceStep(delta: Int) {
        currentStep = (currentStep + delta).coerceIn(0, sopSteps.size - 1)
        updateStepDisplay()
    }

    private fun showPassOverlay() {
        showOverlay(
            background = "#CC00FF41",
            text = "VERIFIED",
            durationMs = 2000,
        )
        if (currentStep == sopSteps.lastIndex) {
            tts.speak("All steps verified. Audit report is ready.", TextToSpeech.QUEUE_FLUSH, null, null)
            handler.postDelayed({ showCompletionReport() }, 2000)
        } else {
            tts.speak("Step verified. Proceeding to next step.", TextToSpeech.QUEUE_FLUSH, null, null)
            advanceStep(1)
        }
    }

    private fun showFailOverlay() {
        showOverlay(
            background = "#CCFF0000",
            text = "WARNING - ISSUE DETECTED",
            durationMs = 3000,
        )
        tts.speak("Warning. Issue detected. Please review this step.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun showIssueFlaggedOverlay() {
        showOverlay(
            background = "#CCFF0000",
            text = "ISSUE FLAGGED",
            durationMs = 3000,
        )
        tts.speak("Warning. Issue detected. Please review this step.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun showOverlay(
        background: String,
        text: String,
        durationMs: Long,
        textSizeSp: Float = 34f,
    ) {
        showPersistentOverlay(background, text, textSizeSp)
        val hide = Runnable { hideOverlay() }
        overlayTimer?.let { handler.removeCallbacks(it) }
        overlayTimer = hide
        handler.postDelayed(hide, durationMs)
    }

    private fun showPersistentOverlay(
        background: String,
        text: String,
        textSizeSp: Float = 34f,
    ) {
        overlayTimer?.let { handler.removeCallbacks(it) }
        overlayTimer = null
        applyOverlayState(background, text, textSizeSp, true)
    }

    private fun hideOverlay() {
        overlayTimer?.let { handler.removeCallbacks(it) }
        overlayTimer = null
        applyOverlayState(
            background = lastOverlayBackground ?: "#000000",
            text = lastOverlayText.orEmpty(),
            textSizeSp = lastOverlayTextSizeSp ?: 34f,
            visible = false,
        )
    }

    private fun applyOverlayState(
        background: String,
        text: String,
        textSizeSp: Float,
        visible: Boolean,
    ) {
        if (
            lastOverlayBackground == background &&
            lastOverlayText == text &&
            lastOverlayTextSizeSp == textSizeSp &&
            lastOverlayVisible == visible
        ) {
            return
        }
        lastOverlayBackground = background
        lastOverlayText = text
        lastOverlayTextSizeSp = textSizeSp
        lastOverlayVisible = visible
        overlay.setBackgroundColor(Color.parseColor(background))
        overlayText.textSize = textSizeSp
        setTextIfChanged(overlayText, text)
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showCompletionReport() {
        showPersistentOverlay(
            background = "#FF000000",
            text = buildString {
                appendLine("AUDIT C REPORT")
                appendLine()
                sessionSteps.forEach { step ->
                    appendLine("STEP ${step.stepNumber}: ${step.status.uppercase(Locale.ENGLISH)}")
                }
                appendLine()
                append("REPORT READY")
            },
            textSizeSp = 24f,
        )
    }

    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) {
            view.text = value
        }
    }

    private fun updateStatusText(text: String) {
        setTextIfChanged(statusBoxText, text)
    }

    private fun saveProgressState(bundle: Bundle) {
        bundle.putString(STATE_TECHNICIAN_NAME, technicianName)
        bundle.putString(STATE_TECHNICIAN_NAME_TIMESTAMP, technicianNameTimestamp)
        bundle.putString(STATE_PENDING_NAME, pendingNameCandidate)
        bundle.putString(STATE_PENDING_VOICE_MODE, pendingVoiceMode.name)
        bundle.putInt(STATE_CURRENT_STEP, currentStep)
        bundle.putBoolean(STATE_SESSION_STARTED, sessionStarted)
        bundle.putString(STATE_STATUS_TEXT, statusBoxText.text.toString())
        bundle.putString(STATE_ACTIVE_BACKEND, activeBackend)
        bundle.putString(STATE_SESSION_STEPS, sessionStepsToJson().toString())
    }

    private fun restoreProgressState(bundle: Bundle?) {
        if (bundle == null) {
            return
        }
        technicianName = bundle.getString(STATE_TECHNICIAN_NAME)
        technicianNameTimestamp = bundle.getString(STATE_TECHNICIAN_NAME_TIMESTAMP)
        pendingNameCandidate = bundle.getString(STATE_PENDING_NAME)
        currentStep = bundle.getInt(STATE_CURRENT_STEP, 0)
        sessionStarted = bundle.getBoolean(STATE_SESSION_STARTED, false)
        activeBackend = bundle.getString(STATE_ACTIVE_BACKEND, BACKEND_USB)
        activeBackendLabel = if (activeBackend == BACKEND_WIFI) "WiFi" else "USB"
        bundle.getString(STATE_PENDING_VOICE_MODE)
            ?.let { value -> pendingVoiceMode = runCatching { VoiceMode.valueOf(value) }.getOrDefault(VoiceMode.GENERAL) }
        restoreSessionSteps(bundle.getString(STATE_SESSION_STEPS))
        updateStatusText(bundle.getString(STATE_STATUS_TEXT, statusBoxText.text?.toString() ?: "(awaiting input)"))
    }

    private fun buildSessionJson(effectiveName: String): JSONObject {
        return JSONObject().apply {
            put("user_name", effectiveName)
            put("user_name_timestamp", technicianNameTimestamp ?: currentUtcTimestamp())
            put("steps", sessionStepsToJson())
        }
    }

    private fun sessionStepsToJson(): JSONArray {
        val array = JSONArray()
        sessionSteps.forEach { step ->
            array.put(
                JSONObject().apply {
                    put("step_number", step.stepNumber)
                    put("step_name", step.stepName)
                    put("voice_input", step.voiceInput)
                    put("status", step.status)
                    put("timestamp", step.timestamp)
                }
            )
        }
        return array
    }

    private fun restoreSessionSteps(serializedSteps: String?) {
        if (serializedSteps.isNullOrBlank()) {
            return
        }
        runCatching {
            val array = JSONArray(serializedSteps)
            for (index in 0 until minOf(array.length(), sessionSteps.size)) {
                val item = array.optJSONObject(index) ?: continue
                sessionSteps[index].stepName = item.optString("step_name", sessionSteps[index].stepName)
                sessionSteps[index].voiceInput = item.optString("voice_input", "")
                sessionSteps[index].status = item.optString("status", "pending")
                sessionSteps[index].timestamp = item.optString("timestamp", "")
            }
        }
    }

    private fun updateSessionStep(index: Int, voiceInput: String, status: String, timestamp: String, stepName: String?) {
        val step = sessionSteps.getOrNull(index) ?: return
        step.voiceInput = voiceInput
        step.status = status
        step.timestamp = timestamp.ifBlank { currentUtcTimestamp() }
        if (!stepName.isNullOrBlank()) {
            step.stepName = stepName
        }
        updateStepDisplay()
    }

    private fun orderedBackends(): List<String> {
        return if (activeBackend == BACKEND_WIFI) {
            listOf(BACKEND_WIFI, BACKEND_USB)
        } else {
            listOf(BACKEND_USB, BACKEND_WIFI)
        }
    }

    private fun markBackendUsed(backend: String) {
        activeBackend = backend
        activeBackendLabel = if (backend == BACKEND_USB) "USB" else "WiFi"
        backendConnected = true
        connectionWarningVisible = false
    }

    private fun currentUtcTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ENGLISH)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private fun setupCxrBridge() {
        try {
            cxrBridge.setStatusListener(statusListener)
            cxrBridge.subscribe("audit_c_commands", msgCallback)
        } catch (_: Exception) {
            updateConnectionStatus(false)
        }
    }

    private val cxrBridge = com.rokid.cxr.CXRServiceBridge()

    private val statusListener = object : com.rokid.cxr.CXRServiceBridge.StatusListener {
        override fun onConnected(name: String, type: Int) {
            runOnUiThread { updateConnectionStatus(true) }
        }

        override fun onDisconnected() {
            runOnUiThread { updateConnectionStatus(false) }
        }

        override fun onARTCStatus(health: Float, reset: Boolean) = Unit
    }

    private val msgCallback = object : com.rokid.cxr.CXRServiceBridge.MsgCallback {
        override fun onReceive(name: String, args: com.rokid.cxr.Caps, value: ByteArray?) = Unit
    }

    fun sendResultToPhone(result: String, stepName: String) {
        try {
            val caps = com.rokid.cxr.Caps().apply {
                write(result)
                write(stepName)
            }
            cxrBridge.sendMessage("audit_c_result", caps)
        } catch (_: Exception) {
        }
    }

    enum class KeyType(val action: String) {
        CLICK("com.android.action.ACTION_SPRITE_BUTTON_CLICK"),
        LONG_PRESS("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"),
        TWO_FINGER_SINGLE_TAP("com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"),
        TWO_FINGER_SWIPE_FORWARD("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"),
        TWO_FINGER_SWIPE_BACK("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"),
        AI_START("com.android.action.ACTION_AI_START"),
    }

    class KeyReceiver : BroadcastReceiver() {
        var listener: ((KeyType) -> Unit)? = null

        override fun onReceive(context: Context?, intent: Intent?) {
            val type = KeyType.entries.firstOrNull { it.action == intent?.action } ?: return
            listener?.invoke(type)
            abortBroadcast()
        }
    }

    private data class StepResultPayload(
        val result: String,
        val stepName: String,
        val timestamp: String
    )

    private data class ObservationResultPayload(
        val flagged: Boolean,
        val timestamp: String
    )

    private data class ReportResultPayload(
        val fileName: String?,
        val reportPath: String?,
        val errorMessage: String?
    )

    private data class SessionStepState(
        val stepNumber: Int,
        var stepName: String,
        var voiceInput: String = "",
        var status: String = "pending",
        var timestamp: String = ""
    )

    private enum class BackendMode {
        USB,
        WIFI,
        DISCONNECTED
    }

    companion object {
        const val BACKEND_USB = "http://127.0.0.1:8000"
        const val BACKEND_WIFI = "http://192.168.1.100:8000"
        private const val SAMPLE_RATE_HZ = 16000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
        private const val RECORDING_DURATION_MS = 5000L
        private const val BEEP_DELAY_MS = 400L
        private const val BEEP_DURATION_MS = 180L
        private const val UI_WATCHDOG_TIMEOUT_MS = 8000L
        private const val UI_WATCHDOG_CHECK_INTERVAL_MS = 2000L
        private const val UI_HEARTBEAT_INTERVAL_MS = 1000L
        private const val RESTART_OVERLAY_DURATION_MS = 500L
        private const val EXIT_OVERLAY_DURATION_MS = 1000L
        private const val HEALTH_CHECK_INITIAL_DELAY_MS = 0L
        private const val HEALTH_CHECK_INTERVAL_MS = 30000L
        private const val CONNECTION_LOST_MESSAGE = "Connection lost - replug USB cable"
        private const val STATE_TECHNICIAN_NAME = "state_technician_name"
        private const val STATE_TECHNICIAN_NAME_TIMESTAMP = "state_technician_name_timestamp"
        private const val STATE_PENDING_NAME = "state_pending_name"
        private const val STATE_PENDING_VOICE_MODE = "state_pending_voice_mode"
        private const val STATE_CURRENT_STEP = "state_current_step"
        private const val STATE_SESSION_STARTED = "state_session_started"
        private const val STATE_STATUS_TEXT = "state_status_text"
        private const val STATE_ACTIVE_BACKEND = "state_active_backend"
        private const val STATE_SESSION_STEPS = "state_session_steps"
    }

    private enum class VoiceMode {
        GENERAL,
        CAPTURE_NAME,
        CONFIRM_NAME,
        OBSERVATION
    }
}
