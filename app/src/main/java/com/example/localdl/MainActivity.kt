package com.example.localdl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var sharedUrlState = mutableStateOf("")
    private val channelId = "local_dl_progress"
    private val notificationId = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DownloaderScreen(
                        url = sharedUrlState.value,
                        onUrlChange = { sharedUrlState.value = it }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val urlRegex = "(https?://\\S+)".toRegex()
                sharedUrlState.value = urlRegex.find(text)?.value ?: text
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun DownloaderScreen(url: String, onUrlChange: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        val formats = listOf("1080p", "720p", "480p", "Audio (MP3)")
        var selectedFormat by remember { mutableStateOf("1080p") }

        var isDownloading by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var statusText by remember { mutableStateOf("Initializing engine...") }
        var isEngineReady by remember { mutableStateOf(false) }
        var savedFileUri by remember { mutableStateOf<Uri?>(null) }
        var savedMimeType by remember { mutableStateOf("video/mp4") }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                    isEngineReady = true
                    statusText = "Ready to download"
                } catch (e: Exception) {
                    statusText = "Init error: ${e.localizedMessage}"
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Local Media Downloader", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = url,
                onValueChange = {
                    onUrlChange(it)
                    savedFileUri = null
                },
                label = { Text("Paste or Share Video URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Select Quality:", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEach { format ->
                    FilterChip(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format },
                        label = { Text(format) }
                    )
                }
            }

            Button(
                onClick = {
                    if (url.isBlank() || !isEngineReady) return@Button
                    isDownloading = true
                    savedFileUri = null
                    progress = 0f
                    statusText = "Starting download..."
                    updateProgressNotification(0, "Starting download...", null, "video/mp4")

                    scope.launch(Dispatchers.IO) {
                        try {
                            val tempDir = File(cacheDir, "ytdl_temp").apply {
                                deleteRecursively()
                                mkdirs()
                            }

                            val isAudio = selectedFormat == "Audio (MP3)"
                            val request = YoutubeDLRequest(url.trim()).apply {
                                addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                                when (selectedFormat) {
                                    "Audio (MP3)" -> {
                                        addOption("-x")
                                        addOption("--audio-format", "mp3")
                                    }
                                    "1080p" -> addOption("-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best")
                                    "720p" -> addOption("-f", "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best")
                                    "480p" -> addOption("-f", "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best")
                                }
                            }

                            var lastNotifiedPct = -5
                            YoutubeDL.getInstance().execute(request) { p, _, _ ->
                                if (p >= 0) {
                                    val pct = p.toInt()
                                    progress = p / 100f
                                    statusText = "Downloading ($selectedFormat): $pct%"
                                    if (pct - lastNotifiedPct >= 5) {
                                        lastNotifiedPct = pct
                                        updateProgressNotification(pct, "Downloading ($selectedFormat)...", null, "video/mp4")
                                    }
                                }
                            }

                            val downloadedFile = tempDir.listFiles()?.firstOrNull()
                            if (downloadedFile != null) {
                                val mime = if (isAudio) "audio/mpeg" else "video/mp4"
                                val uri = saveToPublicDownloads(downloadedFile, mime)
                                downloadedFile.delete()

                                savedMimeType = mime
                                savedFileUri = uri
                                statusText = "Saved to Downloads: ${downloadedFile.name}"
                                updateProgressNotification(100, "Saved: ${downloadedFile.name}", uri, mime)
                            } else {
                                statusText = "Download finished, but file not found."
                            }
                        } catch (e: Exception) {
                            statusText = "Failed: ${e.localizedMessage}"
                            updateProgressNotification(-1, "Download failed", null, "video/mp4")
                        } finally {
                            isDownloading = false
                        }
                    }
                },
                enabled = isEngineReady && !isDownloading && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isDownloading) "Downloading..." else "Download")
            }

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            savedFileUri?.let { uri ->
                FilledTonalButton(
                    onClick = { openMediaFile(uri, savedMimeType) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Downloaded File")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        statusText = "Updating yt-dlp binary..."
                        val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                        statusText = "Update status: $status"
                    }
                },
                enabled = isEngineReady && !isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Update yt-dlp Engine")
            }
        }
    }

    private fun openMediaFile(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Open with"))
    }

    private fun saveToPublicDownloads(file: File, mimeType: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { outputUri ->
                contentResolver.openOutputStream(outputUri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
            }
            uri
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, file.name)
            file.copyTo(destFile, overwrite = true)
            Uri.fromFile(destFile)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateProgressNotification(progress: Int, text: String, fileUri: Uri?, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Local DL")
            .setContentText(text)
            .setOnlyAlertOnce(true)
        if (progress in 0..99) {
            builder.setProgress(100, progress, false).setOngoing(true)
        } else {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
            if (fileUri != null) {
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pendingIntent)
            }
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }
}
