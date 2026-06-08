package com.example.pathguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * Emulator-friendly demo substitute for the Rokid glasses app.
 *
 * Manifest permissions needed:
 * - android.permission.INTERNET
 * - android.permission.RECORD_AUDIO
 */
class PathGuardDemoActivity : AppCompatActivity(), RecognitionListener {

    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent

    private lateinit var headerText: TextView
    private lateinit var currentStepText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var statusText: TextView
    private lateinit var overlayContainer: FrameLayout
    private lateinit var overlayTitleText: TextView
    private lateinit var overlaySubtitleText: TextView
    private lateinit var micButton: Button
    private lateinit var logButton: Button
    private lateinit var reportButton: Button

    private var isListening = false
    private var currentStepIndex = 0
    private var captureMode = CaptureMode.VERIFY_STEP

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceCapture()
            } else {
                statusText.text = "Microphone permission denied."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        configureSpeechRecognizer()
        updateCurrentStepUi()
    }

    override fun onDestroy() {
        speechRecognizer.destroy()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0a0a0a"))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.START
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(28, 36, 28, 36)
        }

        headerText = TextView(this).apply {
            text = "PathGuard | COVID-19 PCR QC"
            setTextColor(Color.parseColor("#5aff8a"))
            textSize = 12f
            alpha = 0.9f
        }

        currentStepText = TextView(this).apply {
            setTextColor(Color.parseColor("#65ff7a"))
            textSize = 22f
            gravity = Gravity.START
            setPadding(0, 18, 0, 0)
        }

        transcriptText = TextView(this).apply {
            setTextColor(Color.parseColor("#d6ffd8"))
            textSize = 20f
            gravity = Gravity.START
            text = "Awaiting voice input..."
            setPadding(0, 120, 0, 16)
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#87b7ff"))
            textSize = 14f
            gravity = Gravity.START
            text = "Ready"
            setPadding(0, 0, 0, 0)
        }

        micButton = Button(this).apply {
            text = "MIC"
            textSize = 24f
            setBackgroundColor(Color.parseColor("#1d3c24"))
            setTextColor(Color.WHITE)
            minimumHeight = 180
            setOnClickListener {
                captureMode = CaptureMode.VERIFY_STEP
                ensureAudioPermissionAndListen()
            }
        }

        logButton = Button(this).apply {
            text = "Log Observation"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1b1f2d"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                captureMode = CaptureMode.LOG_OBSERVATION
                ensureAudioPermissionAndListen()
            }
        }

        reportButton = Button(this).apply {
            text = "Generate Report"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1b1f2d"))
            setTextColor(Color.WHITE)
            setOnClickListener { generateReport() }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
            addView(logButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(this@PathGuardDemoActivity).apply {
                layoutParams = LinearLayout.LayoutParams(20, 1)
            })
            addView(reportButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        bottomContainer.addView(buttonRow)
        bottomContainer.addView(
            micButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(headerText)
        content.addView(currentStepText)
        content.addView(transcriptText)
        content.addView(statusText)
        content.addView(spacer)
        content.addView(bottomContainer)

        overlayTitleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 34f
            gravity = Gravity.CENTER
        }

        overlaySubtitleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        overlayContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            alpha = 0f
            addView(
                LinearLayout(this@PathGuardDemoActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    addView(overlayTitleText)
                    addView(overlaySubtitleText)
                }
            )
        }

        root.addView(content)
        root.addView(overlayContainer)
        setContentView(root)
    }

    private fun configureSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(this@PathGuardDemoActivity)
        }

        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak the current SOP step")
        }
    }

    private fun ensureAudioPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceCapture()
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceCapture() {
        if (isListening) {
            speechRecognizer.stopListening()
            return
        }
        isListening = true
        statusText.text = if (captureMode == CaptureMode.VERIFY_STEP) {
            "Listening for SOP verification..."
        } else {
            "Listening for observation..."
        }
        micButton.text = "STOP"
        speechRecognizer.startListening(speechIntent)
    }

    private fun stopVoiceCapture() {
        isListening = false
        micButton.text = "MIC"
    }

    private fun sendVerifyRequest(spokenText: String) {
        networkExecutor.execute {
            try {
                val responseJson = postJson(
                    path = "/verify-step",
                    payload = JSONObject().apply {
                        put("spoken_text", spokenText)
                    }
                )

                val result = responseJson.optString("result")
                val stepName = responseJson.optString("step_name", currentStepName())

                mainHandler.post {
                    statusText.text = "Backend result: $result"
                    if (result.equals("pass", ignoreCase = true)) {
                        showPassOverlay(stepName)
                    } else {
                        showFailOverlay()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "verify-step request failed", error)
                mainHandler.post {
                    statusText.text = "Verify failed: ${error.message}"
                    showOverlay(
                        title = "WARNING",
                        subtitle = "Backend connection error",
                        titleColor = Color.RED,
                        subtitleColor = Color.WHITE,
                        backgroundColor = Color.parseColor("#dd3c0000"),
                        dismissAfterMs = 3_000L
                    )
                }
            }
        }
    }

    private fun logObservation(spokenText: String) {
        statusText.text = "Sending observation..."
        networkExecutor.execute {
            try {
                postJson(
                    path = "/log-observation",
                    payload = JSONObject().apply {
                        put("spoken_text", spokenText)
                        put("step_number", currentStepIndex + 1)
                    }
                )
                mainHandler.post {
                    statusText.text = "Observation logged for step ${currentStepIndex + 1}."
                }
            } catch (error: Exception) {
                Log.e(TAG, "log-observation request failed", error)
                mainHandler.post {
                    statusText.text = "Observation failed: ${error.message}"
                    showOverlay(
                        title = "WARNING",
                        subtitle = "Observation log failed",
                        titleColor = Color.RED,
                        subtitleColor = Color.WHITE,
                        backgroundColor = Color.parseColor("#dd3c0000"),
                        dismissAfterMs = 3_000L
                    )
                }
            }
        }
    }

    private fun generateReport() {
        statusText.text = "Generating report..."
        networkExecutor.execute {
            try {
                val responseJson = postJson(
                    path = "/generate-report",
                    payload = JSONObject().apply {
                        put("specimen_id", DEMO_SPECIMEN_ID)
                        put("technician_name", DEMO_TECHNICIAN_NAME)
                    }
                )
                val reportPath = responseJson.optString("report_path", "Unknown path")
                mainHandler.post {
                    statusText.text = "Report generated: $reportPath"
                }
            } catch (error: Exception) {
                Log.e(TAG, "generate-report request failed", error)
                mainHandler.post {
                    statusText.text = "Report failed: ${error.message}"
                    showOverlay(
                        title = "WARNING",
                        subtitle = "Report generation failed",
                        titleColor = Color.RED,
                        subtitleColor = Color.WHITE,
                        backgroundColor = Color.parseColor("#dd3c0000"),
                        dismissAfterMs = 3_000L
                    )
                }
            }
        }
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$BACKEND_BASE_URL$path")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $responseBody")
            }
            return JSONObject(responseBody)
        }
    }

    private fun showPassOverlay(stepName: String) {
        showOverlay(
            title = "\u2713 STEP CONFIRMED",
            subtitle = stepName,
            titleColor = Color.parseColor("#00ff66"),
            subtitleColor = Color.parseColor("#ddffdf"),
            backgroundColor = Color.parseColor("#e0003a12"),
            dismissAfterMs = 2_000L
        ) {
            advanceStep(stepName)
        }
    }

    private fun showFailOverlay() {
        showOverlay(
            title = "\u26A0 WARNING",
            subtitle = "WRONG STEP — Please verify",
            titleColor = Color.parseColor("#ff5252"),
            subtitleColor = Color.WHITE,
            backgroundColor = Color.parseColor("#e03a0000"),
            dismissAfterMs = 3_000L
        )
    }

    private fun showOverlay(
        title: String,
        subtitle: String,
        titleColor: Int,
        subtitleColor: Int,
        backgroundColor: Int,
        dismissAfterMs: Long,
        onDismiss: (() -> Unit)? = null,
    ) {
        overlayTitleText.text = title
        overlaySubtitleText.text = subtitle
        overlayTitleText.setTextColor(titleColor)
        overlaySubtitleText.setTextColor(subtitleColor)
        overlayContainer.setBackgroundColor(backgroundColor)
        overlayContainer.visibility = View.VISIBLE
        overlayContainer.animate().cancel()
        overlayContainer.alpha = 0f
        overlayContainer.animate().alpha(1f).setDuration(220L).start()

        mainHandler.removeCallbacksAndMessages(OVERLAY_TOKEN)
        mainHandler.postAtTime({
            overlayContainer.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    overlayContainer.visibility = View.GONE
                    onDismiss?.invoke()
                }
                .start()
        }, OVERLAY_TOKEN, SystemClock.uptimeMillis() + dismissAfterMs)
    }

    private fun advanceStep(stepName: String) {
        transcriptText.text = "Confirmed: $stepName"
        if (currentStepIndex < SOP_STEPS.lastIndex) {
            currentStepIndex += 1
        }
        updateCurrentStepUi()
    }

    private fun updateCurrentStepUi() {
        currentStepText.text = "Step ${currentStepIndex + 1}: ${currentStepName()}"
    }

    private fun currentStepName(): String = SOP_STEPS[currentStepIndex]

    override fun onReadyForSpeech(params: Bundle?) {
        statusText.text = "Speak now..."
    }

    override fun onBeginningOfSpeech() {
        statusText.text = "Recording voice input..."
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        statusText.text = "Processing speech..."
    }

    override fun onError(error: Int) {
        stopVoiceCapture()
        statusText.text = "Speech recognition error: $error"
        showOverlay(
            title = "WARNING",
            subtitle = "Microphone capture failed",
            titleColor = Color.RED,
            subtitleColor = Color.WHITE,
            backgroundColor = Color.parseColor("#dd3c0000"),
            dismissAfterMs = 3_000L
        )
    }

    override fun onResults(results: Bundle?) {
        stopVoiceCapture()
        val matches = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()

        if (matches.isEmpty()) {
            statusText.text = "No speech recognized."
            return
        }

        val spokenText = matches.first()
        transcriptText.text = "Heard: $spokenText"
        if (captureMode == CaptureMode.LOG_OBSERVATION) {
            logObservation(spokenText)
        } else {
            sendVerifyRequest(spokenText)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val TAG = "PathGuardDemo"
        private const val BACKEND_BASE_URL = "http://192.168.1.11:8000"
        private const val DEMO_SPECIMEN_ID = "DEMO-4521"
        private const val DEMO_TECHNICIAN_NAME = "Emulator Demo"
        private val OVERLAY_TOKEN = Any()

        private val SOP_STEPS = listOf(
            "Sample Collection",
            "RNA Extraction",
            "Reverse Transcription",
            "PCR Master Mix Preparation",
            "Thermal Cycling",
            "Detection and Analysis",
        )
    }

    private enum class CaptureMode {
        VERIFY_STEP,
        LOG_OBSERVATION,
    }
}
