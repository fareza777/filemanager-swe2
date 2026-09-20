package com.filezen.files.ui.transfer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.Routes
import com.filezen.files.core.transfer.TransferService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Transfer — serves the shared folder over HTTP so any browser on the same
 * Wi-Fi can push/pull files. Server + UI inspired by matan-h/Transfer (MIT).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(nav: NavController) {
    val server = FileZenApp.c.transfer
    val state by server.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val ctx = LocalContext.current

    // Keep the screen (and CPU/network) awake while the server is up.
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    // Live file count in the shared folder, refreshed while visible.
    var fileCount by remember { mutableStateOf(0) }
    var shareBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(state.running) {
        while (true) {
            val files = server.shareDir.listFiles()?.filter { it.isFile } ?: emptyList()
            fileCount = files.size
            shareBytes = files.sumOf { it.length() }
            delay(1500)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Transfer to PC", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Hero — radar broadcast while running
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(
                        listOf(Color(0xFF1F5C8B), Color(0xFF123A56))))
                    .padding(26.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BroadcastOrb(active = state.running)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(if (state.running) "Server is live"
                                 else "Server stopped",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text(if (state.running)
                                    "Open on your PC — keeps running in background"
                                 else "Start to share over Wi-Fi",
                                color = Color(0xFFB8D4E8),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.url != null) {
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.28f))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(state.url!!,
                                Modifier.weight(1f),
                                color = Color(0xFF9BE7DC),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(state.url!!))
                            }) {
                                Icon(Icons.Rounded.ContentCopy, "Copy",
                                    tint = Color.White)
                            }
                        }
                        // Extra interfaces (hotspot, VPN overlay) also serve the
                        // same URL path — show them so the right one can be picked.
                        val alts = server.lanAddrs().drop(1)
                        if (alts.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            alts.forEach { a ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.16f))
                                        .clickable {
                                            clipboard.setText(AnnotatedString(
                                                "http://${a.ip}:8765" +
                                                    state.url!!.substringAfter(":8765")))
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(a.kind, color = Color(0xFF8FD8CE),
                                        style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(10.dp))
                                    Text("http://${a.ip}:8765/…",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Couldn't start: $it", color = Color(0xFFF6B7B7),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Button(
                onClick = {
                    if (state.running) TransferService.stop(ctx)
                    else TransferService.start(ctx)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.running)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primary),
            ) {
                Icon(if (state.running) Icons.Rounded.Stop
                     else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.running) "Stop server" else "Start server",
                    fontWeight = FontWeight.Bold)
            }

            // Shared folder card
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                ListItem(
                    headlineContent = { Text("Shared folder") },
                    supportingContent = {
                        Text("${server.shareDir.absolutePath} · " +
                            "$fileCount files · ${formatShareSize(shareBytes)}",
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Rounded.FolderShared, null,
                        tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .clickable {
                            server.shareDir.mkdirs()
                            nav.navigate(Routes.folder(server.shareDir.absolutePath))
                        },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            // How it works
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HowRow(Icons.Rounded.Wifi,
                        "Same Wi-Fi only — nothing leaves your network")
                    HowRow(Icons.Rounded.UploadFile,
                        "Drop files on the page to send them to the phone")
                    HowRow(Icons.Rounded.Download,
                        "Tap Download in the browser to pull files to the PC")
                    HowRow(Icons.Rounded.Terminal,
                        "CLI friendly: curl -T file.zip <url>put/file.zip")
                    HowRow(Icons.Rounded.WifiTethering,
                        "No Wi-Fi around? Turn on the phone hotspot and connect the PC to it")
                    HowRow(Icons.Rounded.VpnKey,
                        "Different network? Put Tailscale on both devices, then use the VPN address")
                    HowRow(Icons.Rounded.Lock,
                        "The address contains a random key that changes every launch")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Pulsing radar — concentric rings expanding while the server runs. */
@Composable
private fun BroadcastOrb(active: Boolean) {
    val inf = rememberInfiniteTransition(label = "orb")
    val pulse by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "p")
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                for (k in 0..2) {
                    val t = (pulse + k / 3f) % 1f
                    drawCircle(
                        color = Color(0xFF4FD1C5),
                        alpha = (1f - t) * 0.55f,
                        radius = r * (0.45f + 0.55f * t),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (active) Color(0xFF4FD1C5) else Color(0xFF54708A)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Phonelink, null,
                tint = if (active) Color(0xFF06332C) else Color.White,
                modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HowRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatShareSize(b: Long): String =
    com.filezen.files.core.model.formatSize(b)
