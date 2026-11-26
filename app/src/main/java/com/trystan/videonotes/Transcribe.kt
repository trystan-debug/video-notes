package com.trystan.videonotes

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Whisper (OpenAI) transcripts: sharper text and exact timestamps. Needs an API key. */
object Whisper {
    private const val LIMIT_BYTES = 25L * 1024 * 1024

    suspend fun transcribe(ctx: Context, video: Uri, apiKey: String): List<Seg> = withContext(Dispatchers.IO) {
        val audio = File(ctx.cacheDir, "whisper_${System.nanoTime()}.m4a")
        try {
            Files.extractAudio(ctx, video, audio)
            if (audio.length() > LIMIT_BYTES) {
                throw IllegalStateException("This one is too long for Whisper (25 MB max, roughly 25 minutes). Switch to Free in Settings and tap Redo.")
            }
            val boundary = "----videonotes${System.nanoTime()}"
            val conn = URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.setChunkedStreamingMode(64 * 1024)
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", "whisper-1")
                field("response_format", "verbose_json")
                Locale.getDefault().language.takeIf { it.length == 2 }?.let { field("language", it) }
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
                audio.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                throw IllegalStateException(
                    when (code) {
                        401 -> "Whisper rejected the API key. Check it in Settings."
                        429 -> "OpenAI says you're out of credit or going too fast. Try again later."
                        else -> "Whisper error $code: ${msg ?: "unknown"}"
                    }
                )
            }
            val json = JSONObject(body)
            val segs = json.optJSONArray("segments")
            val list = if (segs != null && segs.length() > 0) {
                (0 until segs.length()).map {
                    val s = segs.getJSONObject(it)
                    Seg((s.optDouble("start") * 1000).toLong(), s.optString("text").trim())
                }.filter { it.text.isNotBlank() }
            } else {
                json.optString("text").trim().takeIf { it.isNotBlank() }?.let { listOf(Seg(0, it)) } ?: emptyList()
            }
            list
        } finally {
            audio.delete()
        }
    }
}

/** Free transcripts using the speech engine built into the phone (Android 13+). Works offline. */
@RequiresApi(33)
object OnPhone {

    suspend fun transcribe(ctx: Context, video: Uri): List<Seg> {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            throw IllegalStateException("This phone has no built-in speech engine. Add a Whisper key in Settings and tap Redo.")
        }
        val pcm = File(ctx.cacheDir, "pcm_${System.nanoTime()}.raw")
        try {
            withContext(Dispatchers.IO) { Files.decodePcm16kMono(ctx, video, pcm) }
            val totalMs = pcm.length() / 32 // 16,000 samples x 2 bytes = 32 bytes per ms
            if (totalMs < 500) return emptyList()
            val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val texts = withTimeout(totalMs * 3 + 90_000) {
                    withContext(Dispatchers.Main) { listen(ctx, pfd) }
                }
                // The phone engine gives no timestamps, so space the lines out across the video.
                return texts.mapIndexed { i, t -> Seg(totalMs * i / texts.size.coerceAtLeast(1), t) }
            } finally {
                runCatching { pfd.close() }
            }
        } finally {
            pcm.delete()
        }
    }

    private fun languageTag(): String {
        val l = Locale.getDefault()
        return if (l.language == "en") "en-US" else l.toLanguageTag()
    }

    private suspend fun listen(ctx: Context, pfd: ParcelFileDescriptor): List<String> =
        suspendCancellableCoroutine { cont ->
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            val texts = mutableListOf<String>()
            var finished = false
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }

            fun done(error: Throwable? = null) {
                if (finished) return
                finished = true
                runCatching { sr.destroy() }
                if (!cont.isActive) return
                if (error != null) cont.resumeWithException(error) else cont.resume(texts.toList())
            }

            fun addFrom(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.trim()?.takeIf { it.isNotBlank() }?.let { texts.add(it.replaceFirstChar(Char::titlecase)) }
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onSegmentResults(segmentResults: Bundle) { addFrom(segmentResults) }
                override fun onEndOfSegmentedSession() { done() }
                override fun onResults(results: Bundle?) { addFrom(results); done() }
                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> done()
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            runCatching { sr.triggerModelDownload(intent) }
                            done(IllegalStateException("Your phone is downloading its speech pack. Tap Redo in a few minutes."))
                        }
                        else -> if (texts.isNotEmpty()) done()
                        else done(IllegalStateException("The phone's speech engine stopped (code $error). Tap Redo, or add a Whisper key."))
                    }
                }
            })
            cont.invokeOnCancellation {
                finished = true
                android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { sr.destroy() } }
            }
            sr.startListening(intent)
        }
}
