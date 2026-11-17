package com.aurora.companion.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.ChatMessage
import com.aurora.companion.android.ModelOption
import com.aurora.companion.android.Screen
import com.aurora.companion.android.UiState
import com.aurora.companion.android.toNiceSize
import com.aurora.companion.android.VoiceCatalog
import com.aurora.companion.android.MIC_AUTO
import com.aurora.companion.android.MIC_HOLD

/** Phone vs tablet sizing, decided from the actual window width (works in split-screen too). */
data class Sizing(val wide: Boolean, val twoPane: Boolean, val body: Float, val pad: Dp)

@Composable
fun AuroraApp(vm: AuroraViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy = ui.screen is Screen.Busy || ui.generating || ui.listening || ui.speaking || ui.inCall
    val actx = LocalContext.current
    LaunchedEffect(ui.inCall) {
        (actx as? android.app.Activity)?.volumeControlStream =
            if (ui.inCall) android.media.AudioManager.STREAM_VOICE_CALL else android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
    }
    LocalView.current.keepScreenOn = busy

    // Default text colour for every screen: full-screen pages aren't inside a Surface, so without this plain Text would be black on black.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val sizing = Sizing(
            wide = maxWidth >= 600.dp,
            twoPane = maxWidth >= 840.dp,
            body = if (maxWidth >= 600.dp) 17f else 15f,
            pad = if (maxWidth >= 600.dp) 24.dp else 12.dp,
        )
        val firstPick = !ui.companionChosen && ui.screen !is Screen.Busy
        // The chat screen draws the companion edge to edge and handles the system bars / keyboard itself;
        // every other screen is kept inside the safe area.
        val padded = Modifier.fillMaxSize().safeDrawingPadding()
        if (firstPick) Box(padded) { CompanionPicker(ui, vm, sizing, firstRun = true, onClose = {}) }
        else when (val s = ui.screen) {
            is Screen.Setup -> Box(padded) { SetupScreen(ui, vm, sizing) }
            is Screen.Busy -> Box(padded) { BusyScreen(s, sizing, onCancel = vm::cancelWork) }
            is Screen.Chat -> ChatScreen(ui, vm, sizing)   // calls run inline on the chat screen (0.8.2)
        }
        ui.notice?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(3500); vm.dismissNotice() }
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
                Text(msg, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(PanelHi).clickable { vm.dismissNotice() }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
        ui.error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                title = { Text("Heads up") },
                text = { Text(msg) },
            )
        }
    }
    }
}

@Composable
fun Brand(sizing: Sizing, subtitle: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Aurora Companion",
            style = TextStyle(brush = AuroraBrush, fontSize = if (sizing.wide) 34.sp else 26.sp, fontWeight = FontWeight.Bold),
        )
        if (subtitle != null) {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
fun SetupScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing) {
    var agreed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGguf(uri)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(sizing.pad),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Brand(sizing, "Private AI that runs on this device - Android preview")
            InfoCard {
                Text("This device", fontWeight = FontWeight.SemiBold)
                Text("Memory (RAM): %.1f GB   -   Free storage: %s".format(ui.ramGb, ui.freeStorageBytes.toNiceSize()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                if (ui.options.size < 2) Text("Larger 3B model is hidden: it needs an 8 GB device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Text("Choose a brain for Aurora", fontWeight = FontWeight.SemiBold, fontSize = (sizing.body + 1).sp)
            ui.options.forEach { opt ->
                ModelCard(opt, selected = opt == ui.selected, downloaded = opt.id in ui.downloaded,
                    onClick = { vm.select(opt) }, onDelete = { vm.deleteModel(opt) })
            }
            InfoCard {
                Text("Before you download", fontWeight = FontWeight.SemiBold)
                Text(
                    "The model is a third-party file (${ui.selected.license}) downloaded from Hugging Face. " +
                    "Aurora Companion's author does not own the rights to third-party models or apps. " +
                    "Downloading is optional and at your own discretion. Download size: about ${ui.selected.sizeBytes.toNiceSize()} " +
                    "(the same amount of storage is used once installed). Wi-Fi is recommended. " +
                    "After download, chatting works fully offline - nothing you type leaves this device.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { agreed = !agreed }) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text("I understand and want to continue", fontSize = 14.sp)
                }
            }
            val isDownloaded = ui.selected.id in ui.downloaded
            Button(
                onClick = vm::downloadSelected,
                enabled = agreed || isDownloaded,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (isDownloaded) "Start chatting" else "Download ${ui.selected.sizeBytes.toNiceSize()} and start")
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("I already have a .gguf file - import it")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ModelCard(opt: ModelOption, selected: Boolean, downloaded: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, AuroraBrush) else BorderStroke(1.dp, PanelHi)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(border, RoundedCornerShape(16.dp))
            .background(Panel).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(opt.title, fontWeight = FontWeight.SemiBold)
                Text("${opt.sizeBytes.toNiceSize()} download" + if (downloaded) "  -  on device" else "",
                    fontSize = 13.sp, color = if (downloaded) AuroraTeal else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (downloaded) TextButton(onClick = onDelete) { Text("Delete") }
        }
        Text(opt.note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

@Composable
fun BusyScreen(s: Screen.Busy, sizing: Sizing, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(sizing.pad), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Brand(sizing)
            Text(s.title, fontSize = sizing.body.sp)
            if (s.progress != null) {
                LinearProgressIndicator(progress = { s.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                Text("${(s.progress * 100).toInt()}%", color = AuroraTeal)
            } else {
                CircularProgressIndicator()
            }
            if (s.detail.isNotEmpty()) Text(s.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.progress != null) OutlinedButton(onClick = onCancel) { Text("Pause / cancel") }
        }
    }
}

private fun Brush_ring(c: Color) = androidx.compose.ui.graphics.SolidColor(c)

@Composable
private fun BackHandlerCompat(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
