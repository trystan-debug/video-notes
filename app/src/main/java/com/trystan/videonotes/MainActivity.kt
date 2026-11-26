@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.trystan.videonotes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Navy = Color(0xFF0B1630)
private val CardBg = Color(0xFF15234A)
private val Red = Color(0xFFFF3B4E)
private val Muted = Color(0xFF9AA6C4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VNTheme { AppRoot() } }
    }
}

@Composable
private fun VNTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Red, onPrimary = Color.White,
            background = Navy, onBackground = Color.White,
            surface = Navy, onSurface = Color.White,
            surfaceVariant = CardBg, onSurfaceVariant = Muted,
            secondaryContainer = Color(0xFF2A3D73), onSecondaryContainer = Color.White,
            surfaceContainerHigh = CardBg,
        ),
        content = content,
    )
}

private sealed interface Screen {
    data object Home : Screen
    data object Record : Screen
    data object Settings : Screen
    data class Detail(val id: String) : Screen
}

@Composable
private fun AppRoot(vm: AppVM = viewModel()) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val ctx = LocalContext.current
    val t = vm.toast
    LaunchedEffect(t) {
        if (t != null) { Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show(); vm.toast = null }
    }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    Surface(Modifier.fillMaxSize(), color = Navy) {
        when (val s = screen) {
            Screen.Home -> Box(Modifier.systemBarsPadding()) {
                HomeScreen(vm, onRecord = { screen = Screen.Record }, onOpen = { screen = Screen.Detail(it) },
                    onSettings = { screen = Screen.Settings })
            }
            Screen.Record -> RecordScreen(vm, onClose = { screen = Screen.Home })
            Screen.Settings -> Box(Modifier.systemBarsPadding().imePadding()) {
                SettingsScreen(vm, onBack = { screen = Screen.Home })
            }
            is Screen.Detail -> Box(Modifier.systemBarsPadding().imePadding()) {
                DetailScreen(vm, s.id, onBack = { screen = Screen.Home })
            }
        }
    }
}

// ---------- Home ----------

@Composable
private fun HomeScreen(vm: AppVM, onRecord: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    var folder by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val folders = notes.groupingBy { it.folder }.eachCount().toList().sortedByDescending { it.second }
    val shown = notes.filter {
        (folder == null || it.folder == folder) &&
            (query.isBlank() || it.title.contains(query, true) || it.fullText.contains(query, true) ||
                it.keywords.any { k -> k.contains(query, true) })
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Video Notes", fontSize = 32.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search what you said") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
            )
            if (notes.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = folder == null, onClick = { folder = null },
                            label = { Text("All  ${notes.size}", fontWeight = FontWeight.Bold) })
                    }
                    items(folders) { (f, c) ->
                        FilterChip(selected = folder == f, onClick = { folder = if (folder == f) null else f },
                            label = { Text("$f  $c", fontWeight = FontWeight.Bold) })
                    }
                }
            }
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (notes.isEmpty()) "No notes yet" else "Nothing matches",
                        fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text(if (notes.isEmpty()) "Tap Record and talk. Every video saves to your phone first, gets a transcript, and lands in a topic folder."
                    else "Try another word or folder.", color = Muted)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { n -> NoteCard(n) { onOpen(n.id) } }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onRecord, containerColor = Red, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            icon = { Box(Modifier.size(14.dp).background(Color.White, CircleShape)) },
            text = { Text("Record", fontWeight = FontWeight.Black, fontSize = 18.sp) },
        )
    }
}

@Composable
private fun NoteCard(n: Note, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(n.title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("${Files.fmtDate(n.createdAt)} · ${Files.fmtTime(n.durationMs)} · ${n.folder}", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            when (n.status) {
                Note.STATUS_PENDING -> Text("Waiting to transcribe…", color = Muted, fontSize = 14.sp)
                Note.STATUS_WORKING -> Text("Transcribing…", color = Muted, fontSize = 14.sp)
                Note.STATUS_FAILED -> Text("Transcript failed. Open to retry.", color = Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                else -> Text(n.fullText.ifBlank { "(no speech found)" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            }
            if (n.keywords.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    n.keywords.forEach { Tag(it) }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color(0xFF2A3D73), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

// ---------- Record ----------

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private suspend fun cameraProvider(ctx: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val f = ProcessCameraProvider.getInstance(ctx)
    f.addListener({
        try { cont.resume(f.get()) } catch (e: Exception) { cont.resumeWithException(e) }
    }, ContextCompat.getMainExecutor(ctx))
}

@SuppressLint("MissingPermission")
@Composable
private fun RecordScreen(vm: AppVM, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hasCam by remember { mutableStateOf(granted(ctx, Manifest.permission.CAMERA)) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCam = granted(ctx, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!granted(ctx, Manifest.permission.CAMERA) || !granted(ctx, Manifest.permission.RECORD_AUDIO)) {
            perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }
    if (!hasCam) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("Camera is off", fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text("Video Notes needs the camera and mic to record.", color = Muted)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                Text("Allow camera and mic", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onClose) { Text("Back") }
        }
        return
    }

    var front by remember { mutableStateOf(true) }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
        )
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var paused by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(front) {
        try {
            val p = cameraProvider(ctx)
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            p.unbindAll()
            p.bindToLifecycle(owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, videoCapture)
        } catch (e: Exception) {
            vm.toast = "Camera problem: ${e.message}"
        }
    }
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            recording?.stop()
            provider?.unbindAll()
        }
    }

    fun start() {
        val name = "VideoNote_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Files.VIDEO_ROOT}/${Note.INBOX}/")
        }
        val opts = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pending = videoCapture.output.prepareRecording(ctx, opts)
        if (granted(ctx, Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        elapsed = 0L
        paused = false
        recording = pending.start(ContextCompat.getMainExecutor(ctx)) { ev ->
            when (ev) {
                is VideoRecordEvent.Status -> elapsed = ev.recordingStats.recordedDurationNanos / 1_000_000
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    val uri = ev.outputResults.outputUri
                    val okErrors = setOf(
                        VideoRecordEvent.Finalize.ERROR_NONE,
                        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
                        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                    )
                    if (uri != Uri.EMPTY && ev.error in okErrors) {
                        vm.addRecording(uri, name, ev.recordingStats.recordedDurationNanos / 1_000_000)
                        vm.toast = "Saved to your phone"
                        onClose()
                    } else {
                        vm.toast = "Recording didn't save (code ${ev.error})"
                    }
                }
                else -> {}
            }
        }
    }

    BackHandler {
        if (recording != null) recording?.stop() else onClose()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().systemBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (recording != null) recording?.stop() else onClose() },
                modifier = Modifier.background(Color(0x66000000), CircleShape)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (recording != null) {
                Row(Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(if (paused) Muted else Red, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(Files.fmtTime(elapsed), color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (recording == null) front = !front }, enabled = recording == null,
                modifier = Modifier.background(Color(0x66000000), CircleShape)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Flip camera", tint = Color.White)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
                if (recording != null) {
                    TextButton(onClick = {
                        if (paused) recording?.resume() else recording?.pause()
                        paused = !paused
                    }) { Text(if (paused) "Resume" else "Pause", color = Color.White, fontWeight = FontWeight.Black) }
                }
            }
            Box(
                Modifier.size(84.dp).border(5.dp, Color.White, CircleShape).clip(CircleShape)
                    .clickable { if (recording == null) start() else recording?.stop() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (recording == null) 64.dp else 34.dp)
                    .background(Red, if (recording == null) CircleShape else RoundedCornerShape(8.dp)))
            }
            Box(Modifier.width(96.dp))
        }
    }
}

// ---------- Detail ----------

@Composable
private fun DetailScreen(vm: AppVM, id: String, onBack: () -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val n = notes.find { it.id == id }
    if (n == null) { LaunchedEffect(Unit) { onBack() }; return }
    val clipboard = LocalClipboardManager.current
    var player by remember { mutableStateOf<VideoView?>(null) }
    var showMove by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player?.stopPlayback() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(n.folder, color = Muted, fontWeight = FontWeight.Bold)
        }
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setVideoURI(Uri.parse(n.videoUri))
                    val mc = MediaController(c)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                    setOnPreparedListener { seekTo(1) }
                    player = this
                }
            },
            modifier = Modifier.fillMaxWidth().height(440.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(n.title, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            IconButton(onClick = { showRename = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
        }
        Text("${Files.fmtDate(n.createdAt)} · ${Files.fmtTime(n.durationMs)}" +
            (if (n.engine.isNotBlank()) " · ${n.engine}" else ""), color = Muted, fontSize = 13.sp)
        if (n.keywords.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { n.keywords.forEach { Tag(it) } }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showMove = true }) { Text("Move folder", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { vm.redo(n.id) },
                enabled = n.status != Note.STATUS_WORKING && n.status != Note.STATUS_PENDING) {
                Text("Redo transcript", fontWeight = FontWeight.Bold)
            }
            if (n.segments.isNotEmpty()) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(Files.transcriptText(n))); vm.toast = "Transcript copied"
                }) { Text("Copy text", fontWeight = FontWeight.Bold) }
            }
            TextButton(onClick = { showDelete = true }) { Text("Delete", color = Red, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Transcript", fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        when (n.status) {
            Note.STATUS_PENDING, Note.STATUS_WORKING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
                Spacer(Modifier.height(8.dp))
                Text("Transcribing. You can leave this screen.", color = Muted)
            }
            Note.STATUS_FAILED -> {
                Text(n.error ?: "Something went wrong.", color = Red, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.redo(n.id) }) { Text("Try again", fontWeight = FontWeight.Bold) }
            }
            else -> {
                if (n.segments.isEmpty()) Text("No speech found in this one.", color = Muted)
                if (n.approxTimes && n.segments.isNotEmpty()) {
                    Text("Times are rough with the free engine. Tap a line to jump near it.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                }
                n.segments.forEach { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { player?.seekTo(s.startMs.toInt()); player?.start() }
                        .padding(vertical = 6.dp, horizontal = 4.dp)) {
                        Text((if (n.approxTimes) "~" else "") + Files.fmtTime(s.startMs), color = Red,
                            fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.width(60.dp))
                        Text(s.text, fontSize = 16.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (showRename) {
        TextDialog("Rename note", n.title, onDismiss = { showRename = false }) { vm.rename(n.id, it); showRename = false }
    }
    if (showMove) {
        val folders = notes.map { it.folder }.distinct().filter { it != Note.INBOX && it != n.folder }.sorted()
        MoveDialog(folders, onDismiss = { showMove = false }) { vm.moveTo(n.id, it); showMove = false }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this note?", fontWeight = FontWeight.Black) },
            text = { Text("This removes the video and its transcript from your phone. It can't be undone.") },
            confirmButton = { TextButton(onClick = { showDelete = false; vm.delete(n.id) }) { Text("Delete", color = Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveDialog(folders: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEach { f ->
                    Text(f, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                    placeholder = { Text("New folder name") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(newName) }, enabled = newName.isNotBlank()) { Text("Make folder", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Settings ----------

@Composable
private fun SettingsScreen(vm: AppVM, onBack: () -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(vm.apiKey) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val folders = notes.map { it.folder }.distinct().sorted()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(16.dp))
        Text("Transcripts", fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        ModeRow(vm.mode == AppVM.MODE_FREE, "Free, on your phone",
            "Uses the speech engine built into your Samsung. Works offline. Times are rough.") { vm.updateMode(AppVM.MODE_FREE) }
        ModeRow(vm.mode == AppVM.MODE_WHISPER, "Whisper (OpenAI)",
            "Sharper text and exact times. About 1 cent per minute of video. Needs an OpenAI API key.") { vm.updateMode(AppVM.MODE_WHISPER) }
        if (vm.mode == AppVM.MODE_WHISPER) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text("OpenAI API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.updateKey(key) }) { Text("Save key", fontWeight = FontWeight.Bold) }
            if (vm.apiKey.isBlank()) Text("No key saved yet, so the free engine is used.", color = Muted, fontSize = 13.sp)
        }

        Spacer(Modifier.height(28.dp))
        Text("Folders", fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text("Notes get filed by the words you use. Tap a folder to rename it.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        folders.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { renaming = f }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("${notes.count { it.folder == f }}", color = Muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.regroupAll() }) { Text("Regroup all notes", fontWeight = FontWeight.Bold) }
        Text("Re-sorts every note you haven't moved by hand.", color = Muted, fontSize = 13.sp)

        Spacer(Modifier.height(28.dp))
        Text("Where your files live", fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text("Videos: ${Files.VIDEO_ROOT}/<folder>\nTranscripts: ${Files.TEXT_ROOT}/<folder>\nThey stay on your phone even if you delete this app.",
            color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(40.dp))
    }

    renaming?.let { f ->
        TextDialog("Rename folder", f, onDismiss = { renaming = null }) { vm.renameFolder(f, it); renaming = null }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(sub, color = Muted, fontSize = 13.sp)
        }
    }
}
