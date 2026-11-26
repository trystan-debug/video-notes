package com.trystan.videonotes

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything that touches files on the phone. */
object Files {
    const val VIDEO_ROOT = "Movies/VideoNotes"
    const val TEXT_ROOT = "Documents/VideoNotes"

    fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    fun fmtDate(t: Long): String = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(t))

    /** Moves the video into its topic folder and writes/refreshes the paired transcript file. */
    fun place(ctx: Context, n: Note): Note {
        var note = n
        runCatching { moveVideo(ctx, Uri.parse(n.videoUri), n.folder) }
        runCatching { note = note.copy(transcriptUri = writeTranscript(ctx, note).toString()) }
        return note
    }

    private fun moveVideo(ctx: Context, uri: Uri, folder: String) {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$VIDEO_ROOT/$folder/") }
        ctx.contentResolver.update(uri, cv, null, null)
    }

    fun transcriptText(n: Note): String = buildString {
        appendLine(n.title)
        appendLine("Recorded: ${fmtDate(n.createdAt)} · ${fmtTime(n.durationMs)}")
        appendLine("Folder: ${n.folder}")
        appendLine("Video file: ${n.name}.mp4")
        if (n.engine.isNotBlank()) appendLine("Transcribed by: ${n.engine}")
        if (n.keywords.isNotEmpty()) appendLine("Keywords: ${n.keywords.joinToString(", ")}")
        appendLine()
        if (n.segments.isEmpty()) appendLine("(no speech found)")
        n.segments.forEach {
            val stamp = (if (n.approxTimes) "~" else "") + fmtTime(it.startMs)
            appendLine("[$stamp] ${it.text}")
        }
    }

    private fun writeTranscript(ctx: Context, n: Note): Uri {
        val cr = ctx.contentResolver
        val rel = "$TEXT_ROOT/${n.folder}/"
        val existing = n.transcriptUri?.let { Uri.parse(it) }?.takeIf { uri ->
            runCatching {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, rel) }, null, null) > 0
            }.getOrDefault(false)
        }
        val uri = existing ?: cr.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${n.name} transcript.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }
        ) ?: throw IllegalStateException("Could not save the transcript file")
        cr.openOutputStream(uri, "wt")!!.use { it.write(transcriptText(n).toByteArray()) }
        return uri
    }

    fun delete(ctx: Context, n: Note) {
        runCatching { ctx.contentResolver.delete(Uri.parse(n.videoUri), null, null) }
        n.transcriptUri?.let { t -> runCatching { ctx.contentResolver.delete(Uri.parse(t), null, null) } }
    }

    private fun audioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        throw IllegalStateException("This video has no sound to transcribe")
    }

    /** Copies the sound out of the video into a small .m4a without re-encoding (for Whisper). */
    fun extractAudio(ctx: Context, uri: Uri, out: File) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = audioTrack(ex)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
            val buf = ByteBuffer.allocate(size.coerceAtLeast(64 * 1024))
            val info = MediaCodec.BufferInfo()
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val t = mux.addTrack(fmt)
            mux.start()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(t, buf, info)
                ex.advance()
            }
            mux.stop()
            mux.release()
        } finally {
            ex.release()
        }
    }

    /** Decodes the video's sound to raw 16 kHz mono 16-bit audio (for the free on-phone engine). */
    fun decodePcm16kMono(ctx: Context, uri: Uri, out: File) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = audioTrack(ex)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var pos = 0.0
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                while (!outDone) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        srcRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val raw = ShortArray(sb.remaining())
                        sb.get(raw)
                        // mix down to mono
                        val frames = raw.size / channels.coerceAtLeast(1)
                        val mono = FloatArray(frames)
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += raw[f * channels + c]
                            mono[f] = sum / channels
                        }
                        // resample to 16 kHz with simple linear interpolation
                        val step = srcRate / 16000.0
                        while (pos < frames - 1) {
                            val i = pos.toInt()
                            val frac = (pos - i).toFloat()
                            val v = (mono[i] * (1 - frac) + mono[i + 1] * frac).toInt().coerceIn(-32768, 32767)
                            os.write(v and 0xFF)
                            os.write((v shr 8) and 0xFF)
                            pos += step
                        }
                        pos -= frames
                        if (pos < 0) pos = 0.0
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
