package com.hadencain.vox

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hadencain.vox.core.VoxSettings
import com.hadencain.vox.inject.VoxAccessibilityService
import com.hadencain.vox.setup.ModelDownloader
import com.hadencain.vox.ui.BehaviorCard
import com.hadencain.vox.ui.CleanupCard
import com.hadencain.vox.ui.DictionaryCard
import com.hadencain.vox.ui.HistoryCard
import com.hadencain.vox.ui.SetupCard
import com.hadencain.vox.ui.StatusCard
import com.hadencain.vox.ui.SuggestionsCard
import kotlinx.coroutines.delay
import java.io.File

// ---- palette -------------------------------------------------------------
private val VoxIndigo = Color(0xFF3D5AFE)
private val VoxBackground = Color(0xFF0E0F13)
private val VoxSurface = Color(0xFF1A1C22)
private val VoxSurfaceVariant = Color(0xFFA1A5B0)
private val VoxOnSurface = Color(0xFFE7E8EC)
internal val VoxGreen = Color(0xFF22C55E)
internal val VoxAmber = Color(0xFFF59E0B)
internal val VoxStopped = Color(0xFF6B7280)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoxTheme { VoxApp() } }
        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutostart(intent)
    }

    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(EXTRA_AUTOSTART)
        if (VoxService.isRunning) return
        val setupComplete = Settings.canDrawOverlays(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            ModelDownloader.allPresent(this)
        if (setupComplete) {
            startForegroundService(Intent(this, VoxService::class.java))
        }
    }
}

@Composable
private fun VoxTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = VoxIndigo,
        onPrimary = Color.White,
        secondary = VoxIndigo,
        background = VoxBackground,
        onBackground = VoxOnSurface,
        surface = VoxSurface,
        onSurface = VoxOnSurface,
        surfaceVariant = VoxSurface,
        onSurfaceVariant = VoxSurfaceVariant,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

@Composable
private fun VoxApp() {
    val ctx = LocalContext.current
    val totalMem = remember {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        mi.totalMem
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (totalMem < 5_300_000_000L) RamBlockedScreen(totalMem) else HomeScreen()
    }
}

@Composable
private fun RamBlockedScreen(totalMem: Long) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Vox needs ~6GB RAM; this device has ${"%.1f".format(totalMem / 1e9)}GB.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// ---- home screen -----------------------------------------------------------

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val resumeTick = rememberResumeTick()
    val settingsFile = remember { File(ctx.filesDir, "settings.json") }
    val historyFile = remember { File(ctx.filesDir, "history.jsonl") }

    var settings by remember { mutableStateOf(VoxSettings.load(settingsFile)) }
    var settingsDirty by remember { mutableStateOf(false) }
    fun updateSettings(new: VoxSettings) {
        settings = new
        new.save(settingsFile)
        settingsDirty = true
    }

    var isRunning by remember { mutableStateOf(VoxService.isRunning) }
    LaunchedEffect(Unit) {
        while (true) {
            isRunning = VoxService.isRunning
            delay(1000)
        }
    }

    var micGranted by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var a11yGranted by remember { mutableStateOf(VoxAccessibilityService.instance != null) }
    
    LaunchedEffect(resumeTick) {
        micGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        overlayGranted = Settings.canDrawOverlays(ctx)
        a11yGranted = VoxAccessibilityService.instance != null
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    var downloadIds by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var dlFailed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modelsPresent by remember { mutableStateOf(ModelDownloader.allPresent(ctx)) }
    var dlProgress by remember { mutableStateOf<Float?>(null) }
    var dlStatusText by remember { mutableStateOf<String?>(null) }

    fun startMissingDownloads() {
        val newIds = downloadIds.toMutableMap()
        val newFailed = dlFailed.toMutableSet()
        for (spec in ModelDownloader.MODELS) {
            if (File(ModelDownloader.modelsDir(ctx), spec.fileName).exists()) continue
            val existingId = ModelDownloader.knownId(ctx, spec)
            if (existingId == -1L) {
                newIds[spec.fileName] = ModelDownloader.enqueue(ctx, spec)
                continue
            }
            when (ModelDownloader.status(ctx, existingId).first) {
                ModelDownloader.DlState.NONE -> newIds[spec.fileName] = ModelDownloader.enqueue(ctx, spec)
                ModelDownloader.DlState.RUNNING -> newIds[spec.fileName] = existingId
                ModelDownloader.DlState.SUCCESS -> {
                    if (ModelDownloader.finalize(ctx, spec)) ModelDownloader.forget(ctx, spec)
                    else { ModelDownloader.forget(ctx, spec); newFailed.add(spec.fileName) }
                }
                ModelDownloader.DlState.FAILED -> {
                    ModelDownloader.forget(ctx, spec); newFailed.add(spec.fileName)
                }
            }
        }
        downloadIds = newIds
        dlFailed = newFailed
        modelsPresent = ModelDownloader.allPresent(ctx)
    }

    fun pollDownloadsOnce() {
        if (downloadIds.isEmpty()) {
            dlProgress = null
            dlStatusText = null
            return
        }
        val newIds = downloadIds.toMutableMap()
        val newFailed = dlFailed.toMutableSet()
        
        var totalBytes = 0L
        var downloadedBytes = 0L
        var isDownloading = false
        var currentStatusText: String? = null

        val dm = ctx.getSystemService(android.app.DownloadManager::class.java)

        for ((name, id) in downloadIds) {
            val spec = ModelDownloader.MODELS.first { it.fileName == name }
            when (ModelDownloader.status(ctx, id).first) {
                ModelDownloader.DlState.SUCCESS -> {
                    if (!ModelDownloader.finalize(ctx, spec)) newFailed.add(name)
                    ModelDownloader.forget(ctx, spec)
                    newIds.remove(name)
                }
                ModelDownloader.DlState.FAILED -> {
                    ModelDownloader.forget(ctx, spec)
                    newFailed.add(name)
                    newIds.remove(name)
                }
                ModelDownloader.DlState.RUNNING -> {
                    if (dm != null) {
                        val query = android.app.DownloadManager.Query().setFilterById(id)
                        dm.query(query)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val statusCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                                val reasonCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_REASON)
                                
                                if (statusCol != -1) {
                                    val status = cursor.getInt(statusCol)
                                    if (status == android.app.DownloadManager.STATUS_PENDING) currentStatusText = "Pending... (Connecting)"
                                    if (status == android.app.DownloadManager.STATUS_PAUSED) {
                                        currentStatusText = "Paused by system (Network issue?)"
                                    }
                                }

                                val bytesDlCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val bytesTotalCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                if (bytesDlCol != -1 && bytesTotalCol != -1) {
                                    val bDl = cursor.getLong(bytesDlCol)
                                    val bTotal = cursor.getLong(bytesTotalCol)
                                    if (bTotal > 0) {
                                        downloadedBytes += bDl
                                        totalBytes += bTotal
                                        isDownloading = true
                                    } else if (bDl > 0) {
                                        downloadedBytes += bDl
                                        isDownloading = true
                                    }
                                }
                            }
                        }
                    }
                }
                ModelDownloader.DlState.NONE -> {
                    ModelDownloader.forget(ctx, spec)
                    newFailed.add(name)
                    newIds.remove(name)
                }
            }
        }
        
        if (isDownloading && totalBytes > 0) {
            dlProgress = downloadedBytes.toFloat() / totalBytes.toFloat()
            dlStatusText = "Downloading... ${(dlProgress!! * 100).toInt()}%"
        } else if (currentStatusText != null) {
            dlProgress = 0f
            dlStatusText = currentStatusText
        } else {
            dlProgress = null
        }

        downloadIds = newIds
        dlFailed = newFailed
        modelsPresent = ModelDownloader.allPresent(ctx)
    }

    LaunchedEffect(Unit) {
        startMissingDownloads()
        while (true) {
            delay(1000)
            pollDownloadsOnce()
        }
    }

    val setupComplete = overlayGranted && micGranted && modelsPresent

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeaderSection()

        StatusCard(
            isRunning = isRunning, canStart = setupComplete, settingsDirty = settingsDirty,
            onStart = { ctx.startForegroundService(Intent(ctx, VoxService::class.java)) },
            onStop = { ctx.startService(Intent(ctx, VoxService::class.java).setAction(VoxService.ACTION_STOP)) },
            onRestart = {
                ctx.startService(Intent(ctx, VoxService::class.java).setAction(VoxService.ACTION_STOP))
                Handler(Looper.getMainLooper()).postDelayed({ ctx.startForegroundService(Intent(ctx, VoxService::class.java)) }, 700)
                settingsDirty = false
            },
        )

        if (!setupComplete) {
            SetupCard(
                micGranted = micGranted,
                onRequestMic = { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
                modelsPresent = modelsPresent,
                dlFailed = dlFailed.isNotEmpty(),
                dlProgress = dlProgress,
                dlStatusText = dlStatusText,
                onRetryModels = { dlFailed = emptySet(); startMissingDownloads() },
                overlayGranted = overlayGranted,
                onRequestOverlay = { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) },
                a11yGranted = a11yGranted,
                onRequestA11y = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
        }

        CleanupCard(settings) { updateSettings(it) }
        BehaviorCard(settings) { updateSettings(it) }
        DictionaryCard(settings) { updateSettings(it) }
        if (settings.saveHistory || historyFile.exists()) SuggestionsCard(ctx, resumeTick, settings) { updateSettings(it) }
        if (settings.saveHistory || historyFile.exists()) HistoryCard(ctx, resumeTick, settings.historyMax)

        FooterSection()
    }
}

@Composable
private fun HeaderSection() {
    Column {
        Text("Vox", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text("On-device voice typing. Nothing leaves your phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FooterSection() {
    Text("v0.1 · 100% on-device · your speech never leaves this phone.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
}