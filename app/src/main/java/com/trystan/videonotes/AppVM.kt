package com.trystan.videonotes

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class AppVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val store = Store(app)
    val notes = store.notes

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var mode by mutableStateOf(prefs.getString("mode", MODE_FREE) ?: MODE_FREE)
        private set
    var apiKey by mutableStateOf(prefs.getString("key", "") ?: "")
        private set
    var toast by mutableStateOf<String?>(null)

    private val lock = Mutex()

    init {
        // Pick up anything that was mid-transcript when the app closed.
        notes.value.filter { it.status == Note.STATUS_PENDING || it.status == Note.STATUS_WORKING }
            .forEach { process(it.id) }
    }

    fun updateMode(m: String) { mode = m; prefs.edit().putString("mode", m).apply() }
    fun updateKey(k: String) { apiKey = k.trim(); prefs.edit().putString("key", apiKey).apply(); toast = "Key saved" }

    fun addRecording(uri: Uri, name: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        val n = Note(
            id = UUID.randomUUID().toString(), name = name, videoUri = uri.toString(),
            createdAt = now, durationMs = durationMs, title = "Note · ${Files.fmtDate(now)}",
        )
        store.add(n)
        process(n.id)
    }

    fun redo(id: String) {
        store.update(id) { it.copy(status = Note.STATUS_PENDING, error = null) }
        process(id)
    }

    private fun process(id: String) {
        viewModelScope.launch { lock.withLock { runOne(id) } }
    }

    private suspend fun runOne(id: String) {
        val start = store.get(id) ?: return
        store.update(id) { it.copy(status = Note.STATUS_WORKING, error = null) }
        try {
            val video = Uri.parse(start.videoUri)
            val useWhisper = mode == MODE_WHISPER && apiKey.isNotBlank()
            val segs: List<Seg>
            val approx: Boolean
            if (useWhisper) {
                segs = Whisper.transcribe(ctx, video, apiKey); approx = false
            } else {
                if (Build.VERSION.SDK_INT < 33) {
                    throw IllegalStateException("Free transcripts need Android 13 or newer. Add a Whisper key in Settings and tap Redo.")
                }
                segs = OnPhone.transcribe(ctx, video); approx = true
            }
            val text = segs.joinToString(" ") { it.text }
            val current = store.get(id) ?: return
            var n = current.copy(
                segments = segs, approxTimes = approx, status = Note.STATUS_DONE,
                engine = if (useWhisper) "Whisper" else "On phone",
                title = makeTitle(text) ?: current.title,
            )
            val all = store.notes.value
            if (!n.manualFolder) n = n.copy(folder = Grouper.pickFolder(n, all))
            n = n.copy(keywords = Grouper.keywords(text, all))
            val placed = withContext(Dispatchers.IO) { Files.place(ctx, n) }
            store.update(id) { placed }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.update(id) { it.copy(status = Note.STATUS_FAILED, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun rename(id: String, title: String) {
        val t = title.trim().ifBlank { return }
        viewModelScope.launch {
            val n = store.get(id)?.copy(title = t) ?: return@launch
            val placed = withContext(Dispatchers.IO) { Files.place(ctx, n) }
            store.update(id) { placed }
        }
    }

    fun moveTo(id: String, folder: String) {
        val f = Grouper.cleanName(folder).ifBlank { return }
        viewModelScope.launch {
            val n = store.get(id)?.copy(folder = f, manualFolder = true) ?: return@launch
            val placed = withContext(Dispatchers.IO) { Files.place(ctx, n) }
            store.update(id) { placed }
            toast = "Moved to $f"
        }
    }

    fun renameFolder(old: String, new: String) {
        val f = Grouper.cleanName(new).ifBlank { return }
        viewModelScope.launch {
            lock.withLock {
                notes.value.filter { it.folder == old }.forEach { note ->
                    val placed = withContext(Dispatchers.IO) { Files.place(ctx, note.copy(folder = f)) }
                    store.update(note.id) { placed }
                }
            }
            toast = "Renamed to $f"
        }
    }

    /** Re-sorts every note you haven't moved by hand, oldest first. */
    fun regroupAll() {
        viewModelScope.launch {
            lock.withLock {
                val auto = notes.value.filter { it.status == Note.STATUS_DONE && !it.manualFolder }.sortedBy { it.createdAt }
                auto.forEach { n -> store.update(n.id) { it.copy(folder = Note.INBOX) } }
                auto.forEach { n ->
                    val cur = store.get(n.id) ?: return@forEach
                    val folder = Grouper.pickFolder(cur, store.notes.value)
                    val placed = withContext(Dispatchers.IO) { Files.place(ctx, cur.copy(folder = folder)) }
                    store.update(n.id) { placed }
                }
            }
            toast = "Notes regrouped"
        }
    }

    fun delete(id: String) {
        val n = store.get(id) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Files.delete(ctx, n) }
            store.remove(id)
            toast = "Deleted"
        }
    }

    private fun makeTitle(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val t = words.take(7).joinToString(" ").trimEnd('.', ',', '!', '?')
        return (if (t.length > 52) t.take(52).trimEnd() + "…" else if (words.size > 7) "$t…" else t)
            .replaceFirstChar(Char::titlecase)
    }

    companion object {
        const val MODE_FREE = "free"
        const val MODE_WHISPER = "whisper"
    }
}
