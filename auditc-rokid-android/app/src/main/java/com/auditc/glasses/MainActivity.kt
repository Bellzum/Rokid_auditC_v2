package com.auditc.glasses

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
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
    private var recordingThread: Thread? = null

    private val sopSteps = listOf(
        "Step 1: Sample Collection",
        "Step 2: Reagent Preparation",
        "Step 3: Sample Transfer",
        "Step 4: PCR Machine Run",
        "Step 5: Result Analysis",
    )
    private var currentStep = 0
    private val stepPassed = MutableList(sopSteps.size) { false }
    private var technicianName: String? = null
    private var pendingNameCandidate: String? = null
    private var pendingVoiceMode = VoiceMode.GENERAL
    private var deferredVoiceMode: VoiceMode? = null
    private var sessionStarted = false
    private var recordingActive = false
    private var overlayTimer: Runnable? = null
    private var recordingStopRunnable: Runnable? = null

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            deferredVoiceMode?.let { beginVoiceCapture(it) }
        } else {
            statusBoxText.text = "(microphone permission denied)"
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

        updateStepDisplay()
        updateConnectionStatus(false)
        setControlsEnabled(false)

        micButton.setOnClickListener {
            startGeneralVoiceRecognition()
        }
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

        setupCxrBridge()
        handler.post { startTechnicianNameFlow() }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && !isDestroyed) {
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

    override fun onDestroy() {
        unregisterReceiver(keyReceiver)
        stopRecordingSession()
        overlayTimer?.let { handler.removeCallbacks(it) }
        toneGenerator?.release()
        toneGenerator = null
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
        handler.postDelayed({
            startAudioRecording()
        }, BEEP_DELAY_MS)
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
            statusBoxText.text = when (pendingVoiceMode) {
                VoiceMode.CAPTURE_NAME -> "Listening for technician name..."
                VoiceMode.CONFIRM_NAME -> "Listening for confirmation..."
                VoiceMode.OBSERVATION -> "Listening for observation..."
                VoiceMode.GENERAL -> "Listening..."
            }

            recorder.startRecording()
            val worker = Thread {
                writeWaveFile(recorder, outputFile, bufferSize)
            }
            recordingThread = worker
            worker.start()

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

        val worker = recordingThread
        recordingThread = null
        statusBoxText.text = "Transcribing..."
        Thread {
            try {
                worker?.join()
            } catch (_: InterruptedException) {
            }
            transcribeRecordedAudio(outputFile)
        }.start()
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
        recordingThread = null
    }

    private fun transcribeRecordedAudio(audioFile: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/transcribe")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Transcription failed: ${response.code}")
                }
                val payload = JSONObject(response.body?.string().orEmpty())
                val spokenText = payload
                    .optString("text")
                    .ifBlank { payload.optString("transcript") }
                    .trim()
                if (spokenText.isBlank()) {
                    throw IllegalStateException("No speech recognized")
                }
                runOnUiThread {
                    statusBoxText.text = spokenText
                    routeRecognizedText(spokenText)
                }
            }
        } catch (_: Exception) {
            runOnUiThread {
                handleRecognitionFailure("Transcription failed.")
            }
        } finally {
            audioFile.delete()
        }
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
        write(byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        ))
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        write(byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte(),
        ))
    }

    private fun handleRecognitionFailure(message: String) {
        when (pendingVoiceMode) {
            VoiceMode.CAPTURE_NAME -> {
                statusBoxText.text = message
                showOverlay(
                    background = "#CCFF0000",
                    text = "NAME NOT HEARD. TRY AGAIN.",
                    durationMs = 1800,
                )
                handler.postDelayed({ startTechnicianNameFlow() }, 1900)
            }
            VoiceMode.CONFIRM_NAME -> {
                statusBoxText.text = message
                showOverlay(
                    background = "#CCFF0000",
                    text = "SAY YES OR NO",
                    durationMs = 1800,
                )
                handler.postDelayed({ beginVoiceCapture(VoiceMode.CONFIRM_NAME) }, 1900)
            }
            VoiceMode.OBSERVATION -> {
                statusBoxText.text = message
                showOverlay(
                    background = "#CCFF0000",
                    text = "OBSERVATION NOT HEARD",
                    durationMs = 1800,
                )
            }
            VoiceMode.GENERAL -> {
                statusBoxText.text = message
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
                statusBoxText.text = "Preparing name capture..."
            }
            VoiceMode.CONFIRM_NAME -> {
                val candidate = pendingNameCandidate ?: "UNKNOWN"
                showPersistentOverlay(
                    background = "#CC000000",
                    text = "Your name is $candidate\nSay Yes to confirm or No to try again",
                    textSizeSp = 28f,
                )
                statusBoxText.text = "Confirming technician name..."
            }
            VoiceMode.OBSERVATION -> {
                showPersistentOverlay(
                    background = "#CC000000",
                    text = "State your observation after the beep",
                    textSizeSp = 28f,
                )
                statusBoxText.text = "Preparing observation capture..."
            }
            VoiceMode.GENERAL -> {
                showPersistentOverlay(
                    background = "#66000000",
                    text = "Speak now",
                    textSizeSp = 30f,
                )
                statusBoxText.text = "Preparing voice command..."
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
        sessionStarted = true
        pendingNameCandidate = null
        setControlsEnabled(true)
        statusBoxText.text = "Technician: $displayName"
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
            normalized.contains("generate txt report") || normalized.contains("generate text report") -> {
                generateReport("txt")
            }
            normalized.contains("generate pdf report") -> {
                generateReport("pdf")
            }
            normalized.contains("generate report") -> {
                generateReport("pdf")
            }
            else -> {
                verifyStep(spokenText)
            }
        }
    }

    private fun verifyStep(spokenText: String) {
        val body = JSONObject().apply {
            put("spoken_text", spokenText)
            put("step_number", currentStep + 1)
            technicianName?.let { put("user_name", it) }
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$BASE_URL/verify-step")
            .post(body)
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string().orEmpty())
                    val result = json.optString("result", "fail")
                    val stepName = json.optString("step_name", sopSteps.getOrNull(currentStep).orEmpty())
                    runOnUiThread {
                        if (result == "pass") {
                            showPassOverlay()
                            sendResultToPhone(result, stepName)
                        } else {
                            showFailOverlay()
                            sendResultToPhone(result, stepName)
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    showFailOverlay()
                }
            }
        }.start()
    }

    private fun logObservation(text: String) {
        val body = JSONObject().apply {
            put("spoken_text", text)
            put("step_number", currentStep + 1)
            technicianName?.let { put("user_name", it) }
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$BASE_URL/log-observation")
            .post(body)
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string().orEmpty())
                    val entry = json.optJSONObject("entry")
                    val flagged = entry?.optBoolean("flagged", false) ?: false
                    runOnUiThread {
                        if (flagged) {
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
            } catch (_: Exception) {
                runOnUiThread { showFailOverlay() }
            }
        }.start()
    }

    private fun generateReport(format: String) {
        val effectiveName = technicianName ?: "Audit C Glasses"
        val body = JSONObject().apply {
            put("technician_name", effectiveName)
            put("user_name", effectiveName)
            put("format", format)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$BASE_URL/generate-report")
            .post(body)
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Report generation failed: ${response.code}")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val reportPath = json.optString("report_path", "pathguard_report.$format")
                    runOnUiThread {
                        statusBoxText.text = reportPath
                        showOverlay(
                            background = "#CC00FF41",
                            text = "${format.uppercase(Locale.ENGLISH)} REPORT READY",
                            durationMs = 2500,
                        )
                        tts.speak(
                            "${format.uppercase(Locale.ENGLISH)} report generated successfully.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            null,
                        )
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { showFailOverlay() }
            }
        }.start()
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
        currentStepText.text = sopSteps.getOrNull(currentStep) ?: "(no step)"
    }

    private fun advanceStep(delta: Int) {
        currentStep = (currentStep + delta).coerceIn(0, sopSteps.size - 1)
        updateStepDisplay()
    }

    private fun showPassOverlay() {
        stepPassed[currentStep] = true
        showOverlay(
            background = "#CC00FF41",
            text = "✓ VERIFIED",
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
            text = "⚠ WARNING — ISSUE DETECTED",
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
        val hide = Runnable { overlay.visibility = View.GONE }
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
        overlay.setBackgroundColor(Color.parseColor(background))
        overlayText.textSize = textSizeSp
        overlayText.text = text
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlayTimer?.let { handler.removeCallbacks(it) }
        overlayTimer = null
        overlay.visibility = View.GONE
    }

    private fun showCompletionReport() {
        overlayTimer?.let { handler.removeCallbacks(it) }
        overlayTimer = null
        overlay.setBackgroundColor(Color.BLACK)
        overlayText.textSize = 24f
        overlayText.text = buildString {
            appendLine("AUDIT C REPORT")
            appendLine()
            stepPassed.forEachIndexed { index, passed ->
                appendLine("STEP ${index + 1}: ${if (passed) "PASS" else "PENDING"}")
            }
            appendLine()
            append("ALL STEPS VERIFIED")
        }
        overlay.visibility = View.VISIBLE
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

    companion object {
        private const val BASE_URL = "http://127.0.0.1:8000"
        private const val SAMPLE_RATE_HZ = 16000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
        private const val RECORDING_DURATION_MS = 5000L
        private const val BEEP_DELAY_MS = 400L
        private const val BEEP_DURATION_MS = 180L
    }

    private enum class VoiceMode {
        GENERAL,
        CAPTURE_NAME,
        CONFIRM_NAME,
        OBSERVATION,
    }
}
