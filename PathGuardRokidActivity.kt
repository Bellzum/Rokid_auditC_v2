package com.example.pathguard

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * Drop this into your Android app and replace the adapter implementations at the bottom
 * with your concrete Rokid Speech SDK and Rokid AR display API classes.
 *
 * Required Android permissions:
 * - android.permission.RECORD_AUDIO
 * - android.permission.INTERNET
 */
class PathGuardRokidActivity : AppCompatActivity(), RokidSpeechRecognizer.Listener {

    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView

    private lateinit var rokidSpeechRecognizer: RokidSpeechRecognizer
    private lateinit var rokidArDisplay: RokidArDisplay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            text = "Waiting for Rokid voice input..."
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(24, 48, 24, 24)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(statusText)
        }
        setContentView(root)

        rokidSpeechRecognizer = createRokidSpeechRecognizer().also { it.setListener(this) }
        rokidArDisplay = createRokidArDisplay()
    }

    override fun onResume() {
        super.onResume()
        statusText.text = "Listening for SOP step verification..."
        rokidSpeechRecognizer.startListening()
    }

    override fun onPause() {
        rokidSpeechRecognizer.stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        rokidSpeechRecognizer.release()
        rokidArDisplay.release()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRecognizedText(text: String) {
        runOnUiThread {
            statusText.text = "Recognized: $text"
        }
        sendVerifyRequest(text)
    }

    override fun onSpeechError(errorMessage: String) {
        Log.e(TAG, "Speech recognition error: $errorMessage")
        runOnUiThread {
            statusText.text = "Speech error: $errorMessage"
        }
        showWarningOverlay("WARNING")
    }

    private fun sendVerifyRequest(spokenText: String) {
        networkExecutor.execute {
            try {
                val url = URL(endpointUrl("/verify-step"))
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                }

                val requestBody = JSONObject().apply {
                    put("spoken_text", spokenText)
                }

                BufferedWriter(OutputStreamWriter(connection.outputStream)).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseText = BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.readText()
                }
                connection.disconnect()

                if (statusCode !in 200..299) {
                    throw IllegalStateException("Backend error $statusCode: $responseText")
                }

                val json = JSONObject(responseText)
                val result = json.optString("result")
                val stepName = json.optString("step_name", "Unknown Step")

                runOnUiThread {
                    statusText.text = "Backend result: $result"
                    if (result.equals("pass", ignoreCase = true)) {
                        showPassOverlay(stepName)
                    } else {
                        showWarningOverlay("WARNING")
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "verify-step request failed", error)
                runOnUiThread {
                    statusText.text = "Backend request failed: ${error.message}"
                    showWarningOverlay("WARNING")
                }
            }
        }
    }

    private fun logObservation(spokenText: String, stepNumber: Int) {
        networkExecutor.execute {
            try {
                val url = URL(endpointUrl("/log-observation"))
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                }

                val requestBody = JSONObject().apply {
                    put("spoken_text", spokenText)
                    put("step_number", stepNumber)
                }

                BufferedWriter(OutputStreamWriter(connection.outputStream)).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val statusCode = connection.responseCode
                connection.disconnect()
                if (statusCode !in 200..299) {
                    Log.e(TAG, "log-observation failed with status $statusCode")
                }
            } catch (error: Exception) {
                Log.e(TAG, "log-observation request failed", error)
            }
        }
    }

    private fun generateReport(specimenId: String, technicianName: String) {
        networkExecutor.execute {
            try {
                val url = URL(endpointUrl("/generate-report"))
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                }

                val requestBody = JSONObject().apply {
                    put("specimen_id", specimenId)
                    put("technician_name", technicianName)
                }

                BufferedWriter(OutputStreamWriter(connection.outputStream)).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val statusCode = connection.responseCode
                connection.disconnect()
                if (statusCode !in 200..299) {
                    Log.e(TAG, "generate-report failed with status $statusCode")
                }
            } catch (error: Exception) {
                Log.e(TAG, "generate-report request failed", error)
            }
        }
    }

    private fun endpointUrl(path: String): String = "$BACKEND_BASE_URL$path"

    private fun showPassOverlay(stepName: String) {
        rokidArDisplay.showOverlayText(
            text = "\u2713 $stepName",
            color = Color.GREEN,
            durationMs = OVERLAY_DURATION_MS
        )
    }

    private fun showWarningOverlay(text: String) {
        rokidArDisplay.showOverlayText(
            text = text,
            color = Color.RED,
            durationMs = OVERLAY_DURATION_MS
        )
    }

    private fun createRokidSpeechRecognizer(): RokidSpeechRecognizer {
        return PlaceholderRokidSpeechRecognizer()
    }

    private fun createRokidArDisplay(): RokidArDisplay {
        return PlaceholderRokidArDisplay()
    }

    companion object {
        private const val TAG = "PathGuardRokid"
        private const val BACKEND_BASE_URL = "http://192.168.1.11:8000"
        private const val OVERLAY_DURATION_MS = 2_500L
    }
}

/*
 * Replace this interface with the actual listener/callback entry point from the Rokid Speech SDK.
 * Wire the SDK's final ASR result callback to Listener.onRecognizedText(...).
 */
interface RokidSpeechRecognizer {
    fun setListener(listener: Listener)
    fun startListening()
    fun stopListening()
    fun release()

    interface Listener {
        fun onRecognizedText(text: String)
        fun onSpeechError(errorMessage: String)
    }
}

/*
 * Replace this interface with the concrete Rokid AR display or HUD overlay API in your project.
 * The implementation should render projected text in the user's view.
 */
interface RokidArDisplay {
    fun showOverlayText(text: String, color: Int, durationMs: Long)
    fun release()
}

/*
 * Placeholder adapter for workspace-only sample code.
 * Swap this with your actual Rokid Speech SDK wrapper.
 */
class PlaceholderRokidSpeechRecognizer : RokidSpeechRecognizer {
    private var listener: RokidSpeechRecognizer.Listener? = null

    override fun setListener(listener: RokidSpeechRecognizer.Listener) {
        this.listener = listener
    }

    override fun startListening() {
        Log.d("PathGuardRokid", "Attach Rokid Speech SDK startListening() here")
        /*
         * Example integration point:
         * rokidSpeechSdk.startListening(callback = object : RokidSpeechCallback {
         *     override fun onAsrResult(text: String) {
         *         listener?.onRecognizedText(text)
         *     }
         *
         *     override fun onError(code: Int, message: String) {
         *         listener?.onSpeechError("$code: $message")
         *     }
         * })
         */
    }

    override fun stopListening() {
        Log.d("PathGuardRokid", "Attach Rokid Speech SDK stopListening() here")
    }

    override fun release() {
        Log.d("PathGuardRokid", "Attach Rokid Speech SDK release() here")
    }
}

/*
 * Placeholder adapter for workspace-only sample code.
 * Swap this with your actual Rokid AR display API wrapper.
 */
class PlaceholderRokidArDisplay : RokidArDisplay {
    override fun showOverlayText(text: String, color: Int, durationMs: Long) {
        Log.d(
            "PathGuardRokid",
            "Attach Rokid AR overlay API here. text=$text color=$color durationMs=$durationMs"
        )
        /*
         * Example integration point:
         * rokidHud.showText(
         *     content = text,
         *     textColor = color,
         *     timeoutMs = durationMs
         * )
         */
    }

    override fun release() {
        Log.d("PathGuardRokid", "Attach Rokid AR display release() here")
    }
}
