package com.example.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.example.downloader.ui.theme.DownloaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.log10

data class VideoInfo(
    val title: String,
    val duration: String,
    val uploader: String,
    val videoHeights: List<String>,
    val audioFormats: List<String>
)

data class DownloadProgressState(
    val isDownloading: Boolean = false,
    val percent: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speed: Double = 0.0,
    val eta: Long = 0L,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

interface DownloadProgressListener {
    fun onProgress(percent: Float, downloadedBytes: Long, totalBytes: Long, speed: Double, eta: Long)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DownloaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    YoutubeInfoScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

fun getFfmpegPath(context: Context): String? {
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val nativeFfmpeg = File(nativeLibDir, "libffmpeg.so")

    if (nativeFfmpeg.exists()) {
        if (!nativeFfmpeg.canExecute()) {
            nativeFfmpeg.setExecutable(true, false)
            runCatching { Runtime.getRuntime().exec("chmod 755 " + nativeFfmpeg.absolutePath).waitFor() }
        }
        return nativeFfmpeg.absolutePath
    }

    val targetFile = File(context.noBackupFilesDir, "ffmpeg")
    try {
        if (!targetFile.exists() || targetFile.length() == 0L) {
            val apkPath = context.applicationInfo.sourceDir
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                var ffmpegEntry: ZipEntry? = null
                val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.contains(primaryAbi) && entry.name.endsWith("libffmpeg.so")) {
                        ffmpegEntry = entry
                        break
                    }
                }
                if (ffmpegEntry == null) {
                    val entries2 = zip.entries()
                    while (entries2.hasMoreElements()) {
                        val entry = entries2.nextElement()
                        if (entry.name.endsWith("libffmpeg.so")) {
                            ffmpegEntry = entry
                            break
                        }
                    }
                }

                if (ffmpegEntry != null) {
                    zip.getInputStream(ffmpegEntry).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }

        if (targetFile.exists()) {
            targetFile.setExecutable(true, false)
            runCatching { Runtime.getRuntime().exec("chmod 755 " + targetFile.absolutePath).waitFor() }
            return targetFile.absolutePath
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return null
}

suspend fun verifyFfmpegRuntime(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val path = getFfmpegPath(context)
        if (path == null) {
            return@withContext Pair(false, "FFmpeg binary file not found in nativeLibDir or APK")
        }
        val py = Python.getInstance()
        val module = py.getModule("youtube_downloader")
        val jsonStr = module.callAttr("verify_ffmpeg", path).toString()
        val json = JSONObject(jsonStr)
        val success = json.getBoolean("success")
        val info = json.getString("info")
        val returnedPath = json.optString("path", path)
        Pair(success, "Path: $returnedPath\n$info")
    } catch (e: PyException) {
        Pair(false, "Python exception verifying FFmpeg: ${e.message}")
    } catch (e: Exception) {
        Pair(false, "Error verifying FFmpeg: ${e.message}")
    }
}

suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
    try {
        val py = Python.getInstance()
        val module = py.getModule("youtube_info")
        val jsonStr = module.callAttr("get_video_info", url).toString()

        val json = JSONObject(jsonStr)
        val heightsArray = json.getJSONArray("video_heights")
        val heights = (0 until heightsArray.length()).map { heightsArray.getString(it) }

        val audioArray = json.getJSONArray("audio_formats")
        val audio = (0 until audioArray.length()).map { audioArray.getString(it) }

        val info = VideoInfo(
            title = json.getString("title"),
            duration = json.getString("duration"),
            uploader = json.getString("uploader"),
            videoHeights = heights,
            audioFormats = audio
        )
        Result.success(info)
    } catch (e: PyException) {
        Result.failure(Exception(e.message ?: "Python error occurred"))
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun executeDownload(
    context: Context,
    url: String,
    onProgressUpdate: (percent: Float, downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit
): Result<String> = withContext(Dispatchers.IO) {
    try {
        val tempDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        val ffmpegPath = getFfmpegPath(context)

        val listener = object : DownloadProgressListener {
            override fun onProgress(
                percent: Float,
                downloadedBytes: Long,
                totalBytes: Long,
                speed: Double,
                eta: Long
            ) {
                onProgressUpdate(percent, downloadedBytes, totalBytes, speed, eta)
            }
        }

        val py = Python.getInstance()
        val module = py.getModule("youtube_downloader")
        val jsonStr = module.callAttr("download_video", url, tempDir.absolutePath, ffmpegPath, listener).toString()

        val json = JSONObject(jsonStr)
        val success = json.getBoolean("success")

        if (success) {
            val filepath = json.getString("filepath")
            val localFile = File(filepath)

            if (localFile.exists()) {
                val saveResult = saveToPublicDownloads(context, localFile)
                runCatching { localFile.delete() }
                Result.success(saveResult)
            } else {
                Result.failure(Exception("Downloaded file not found at $filepath"))
            }
        } else {
            val error = json.optString("error", "Download failed")
            Result.failure(Exception(error))
        }
    } catch (e: PyException) {
        Result.failure(Exception(e.message ?: "Python error during download"))
    } catch (e: Exception) {
        Result.failure(e)
    }
}

fun saveToPublicDownloads(context: Context, file: File): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                val mimeType = when (file.extension.lowercase(Locale.US)) {
                    "mp4" -> "video/mp4"
                    "webm" -> "video/webm"
                    "mkv" -> "video/x-matroska"
                    "3gp" -> "video/3gpp"
                    else -> "video/*"
                }
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                "Successfully saved ${file.name} to Downloads"
            } else {
                "Failed to create MediaStore entry"
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val targetFile = File(downloadsDir, file.name)
            file.copyTo(targetFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
            "Successfully saved ${file.name} to ${targetFile.absolutePath}"
        }
    } catch (e: Exception) {
        "Saved locally at ${file.absolutePath} (Public copy failed: ${e.message})"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    val index = digitGroups.coerceIn(0, units.size - 1)
    val divider = Math.pow(1024.0, index.toDouble())
    return String.format(Locale.US, "%.2f %s", bytes / divider, units[index])
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "--:--"
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

@Composable
fun YoutubeInfoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ") }
    var isExtracting by remember { mutableStateOf(false) }
    var resultInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var extractError by remember { mutableStateOf<String?>(null) }

    var ffmpegVerificationState by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isVerifyingFfmpeg by remember { mutableStateOf(false) }

    var downloadState by remember { mutableStateOf(DownloadProgressState()) }
    val scope = rememberCoroutineScope()

    // Run verification automatically on screen launch
    LaunchedEffect(Unit) {
        isVerifyingFfmpeg = true
        ffmpegVerificationState = verifyFfmpegRuntime(context)
        isVerifyingFfmpeg = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ffmpegVerificationState?.let { (success, info) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (success) "FFmpeg Verified OK" else "FFmpeg Verification Failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = info,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("YouTube URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (urlText.isNotBlank()) {
                        isExtracting = true
                        extractError = null
                        resultInfo = null
                        scope.launch {
                            val result = fetchVideoInfo(urlText)
                            isExtracting = false
                            result.fold(
                                onSuccess = { resultInfo = it },
                                onFailure = { extractError = it.message ?: "Extraction failed" }
                            )
                        }
                    }
                },
                enabled = !isExtracting && !downloadState.isDownloading && urlText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isExtracting) "Extracting..." else "Get Video Info")
            }

            Button(
                onClick = {
                    if (urlText.isNotBlank()) {
                        downloadState = DownloadProgressState(isDownloading = true)
                        scope.launch {
                            val result = executeDownload(context, urlText) { percent, downloaded, total, speed, eta ->
                                downloadState = downloadState.copy(
                                    percent = percent,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                    speed = speed,
                                    eta = eta
                                )
                            }
                            result.fold(
                                onSuccess = { msg ->
                                    downloadState = downloadState.copy(
                                        isDownloading = false,
                                        successMessage = msg,
                                        errorMessage = null
                                    )
                                },
                                onFailure = { err ->
                                    downloadState = downloadState.copy(
                                        isDownloading = false,
                                        errorMessage = err.message ?: "Download failed"
                                    )
                                }
                            )
                        }
                    }
                },
                enabled = !isExtracting && !downloadState.isDownloading && urlText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (downloadState.isDownloading) "Downloading..." else "Download Video")
            }
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    isVerifyingFfmpeg = true
                    ffmpegVerificationState = verifyFfmpegRuntime(context)
                    isVerifyingFfmpeg = false
                }
            },
            enabled = !isVerifyingFfmpeg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isVerifyingFfmpeg) "Testing FFmpeg..." else "Re-verify FFmpeg")
        }

        if (isExtracting) {
            CircularProgressIndicator()
        }

        extractError?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Extraction Error:",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        resultInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = info.title, style = MaterialTheme.typography.titleLarge)
                    Text(text = "Uploader: ${info.uploader}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Duration: ${info.duration}", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text(text = "Available Video Heights:", style = MaterialTheme.typography.titleMedium)
                    Text(text = info.videoHeights.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Available Audio Formats:", style = MaterialTheme.typography.titleMedium)
                    Text(text = info.audioFormats.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (downloadState.isDownloading || downloadState.successMessage != null || downloadState.errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (downloadState.isDownloading) {
                        Text(
                            text = String.format(Locale.US, "Downloading... %.1f%%", downloadState.percent),
                            style = MaterialTheme.typography.titleMedium
                        )
                        LinearProgressIndicator(
                            progress = { downloadState.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${formatBytes(downloadState.downloadedBytes)} / ${formatBytes(downloadState.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${formatBytes(downloadState.speed.toLong())}/s",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "ETA: ${formatEta(downloadState.eta)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    downloadState.successMessage?.let { msg ->
                        Text(
                            text = "Download Complete!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    downloadState.errorMessage?.let { err ->
                        Text(
                            text = "Download Error:",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
