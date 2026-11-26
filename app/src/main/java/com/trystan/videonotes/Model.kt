package com.trystan.videonotes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Seg(val startMs: Long, val text: String)

data class Note(
    val id: String,
    val name: String,
    val videoUri: String,
    val createdAt: Long,
    val durationMs: Long,
    val title: String,
    val folder: String = INBOX,
    val status: String = STATUS_PENDING,
    val error: String? = null,
    val segments: List<Seg> = emptyList(),
    val approxTimes: Boolean = false,
    val keywords: List<String> = emptyList(),
    val transcriptUri: String? = null,
    val manualFolder: Boolean = false,
    val engine: String = "",
) {
    val fullText: String get() = segments.joinToString(" ") { it.text }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("videoUri", videoUri)
        put("createdAt", createdAt); put("durationMs", durationMs)
        put("title", title); put("folder", folder); put("status", status)
        put("error", error ?: "")
        put("segments", JSONArray().apply {
            segments.forEach { put(JSONObject().put("t", it.startMs).put("x", it.text)) }
        })
        put("approxTimes", approxTimes)
        put("keywords", JSONArray(keywords))
        put("transcriptUri", transcriptUri ?: "")
        put("manualFolder", manualFolder)
        put("engine", engine)
    }

    companion object {
        const val INBOX = "Inbox"
        const val UNSORTED = "Unsorted"
        const val STATUS_PENDING = "pending"
        const val STATUS_WORKING = "working"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"

        fun fromJson(o: JSONObject): Note {
            val segs = o.optJSONArray("segments") ?: JSONArray()
            val kws = o.optJSONArray("keywords") ?: JSONArray()
            return Note(
                id = o.getString("id"),
                name = o.optString("name"),
                videoUri = o.getString("videoUri"),
                createdAt = o.optLong("createdAt"),
                durationMs = o.optLong("durationMs"),
                title = o.optString("title"),
                folder = o.optString("folder", INBOX),
                status = o.optString("status", STATUS_PENDING),
                error = o.optString("error").ifBlank { null },
                segments = (0 until segs.length()).map {
                    val s = segs.getJSONObject(it); Seg(s.optLong("t"), s.optString("x"))
                },
                approxTimes = o.optBoolean("approxTimes"),
                keywords = (0 until kws.length()).map { kws.getString(it) },
                transcriptUri = o.optString("transcriptUri").ifBlank { null },
                manualFolder = o.optBoolean("manualFolder"),
                engine = o.optString("engine"),
            )
        }
    }
}

/** Keeps the list of notes in a JSON file inside the app. Videos live in the phone's Movies folder. */
class Store(context: Context) {
    private val file = File(context.filesDir, "notes.json")
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun get(id: String): Note? = _notes.value.find { it.id == id }

    @Synchronized
    fun add(n: Note) { _notes.value = listOf(n) + _notes.value; save() }

    @Synchronized
    fun update(id: String, f: (Note) -> Note) {
        _notes.value = _notes.value.map { if (it.id == id) f(it) else it }; save()
    }

    @Synchronized
    fun remove(id: String) { _notes.value = _notes.value.filterNot { it.id == id }; save() }

    private fun load(): List<Note> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { runCatching { Note.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }
    } catch (e: Exception) { emptyList() }

    private fun save() {
        val arr = JSONArray().apply { _notes.value.forEach { put(it.toJson()) } }
        val tmp = File(file.parentFile, "notes.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
    }
}
