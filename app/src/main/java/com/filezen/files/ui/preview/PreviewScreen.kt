@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.filezen.files.ui.preview
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filezen.files.core.model.*
import com.filezen.files.ops.Intents
import com.filezen.files.ui.common.ConfirmDialog
import com.filezen.files.ui.common.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(nav: NavController, path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val entry = remember(path) { FileEntry.from(file) }
    var showOpenWith by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Rounded.Info, "Details") }
                    IconButton(onClick = { Intents.share(ctx, listOf(entry)) }) {
                        Icon(Icons.Rounded.Share, "Share")
                    }
                    IconButton(onClick = { Intents.openWith(ctx, entry) }) {
                        Icon(Icons.Rounded.OpenInNew, "Open with")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (entry.type) {
                FileType.IMAGE -> ImagePreview(file)
                FileType.PDF -> PdfPreview(file)
                FileType.TEXT, FileType.DOCUMENT -> TextPreview(file)
                FileType.VIDEO, FileType.AUDIO -> MediaPlayer(entry)
                else -> GenericPreviewCard(entry)
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(entry.name) },
            text = {
                Column {
                    InfoRow("Path", entry.path)
                    InfoRow("Type", entry.type.name)
                    InfoRow("Size", formatSize(entry.size))
                    InfoRow("Modified", formatDate(entry.lastModified))
                    InfoRow("MIME", FileEntry.mimeOf(entry))
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
        )
    }
}
@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, Modifier.width(80.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun ImagePreview(file: File) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        var m: Modifier = Modifier.fillMaxSize()
        val sharedScope = com.filezen.files.ui.common.LocalSharedScope.current
        val animScope = com.filezen.files.ui.common.LocalAnimScope.current
        if (sharedScope != null && animScope != null) {
            m = with(sharedScope) {
                m.sharedElement(
                    sharedScope.rememberSharedContentState("img:" + file.absolutePath),
                    animScope)
            }
        }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(file).crossfade(true).build(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = m,
        )
    }
}
@Composable
private fun TextPreview(file: File) {
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) {
            try {
                file.inputStream().use { ins ->
                    val bytes = ins.readBytes().take(300_000).toByteArray()
                    String(bytes)
                }
            } catch (e: Exception) { error = e.message; null }
        }
    }
    when {
        error != null -> GenericPreviewCard(FileEntry.from(file))
        text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(text!!, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun PdfPreview(file: File) {
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val bitmaps = mutableListOf<Bitmap>()
                for (i in 0 until minOf(renderer.pageCount, 30)) {
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmaps += bmp
                }
                renderer.close(); pfd.close()
                pages = bitmaps
            } catch (e: Exception) { error = e.message }
        }
    }
    when {
        error != null -> GenericPreviewCard(FileEntry.from(file))
        pages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(pages.size) { i ->
                Image(
                    bitmap = pages[i].asImageBitmap(), contentDescription = "Page ${i + 1}",
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}
@Composable
private fun MediaPlayer(e: FileEntry) {
    val ctx = LocalContext.current
    val player = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(
                android.net.Uri.fromFile(java.io.File(e.path))))
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                androidx.media3.ui.PlayerView(c).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier.fillMaxWidth()
                .height(if (e.type == FileType.VIDEO) 320.dp else 140.dp)
                .padding(16.dp)
                .clip(RoundedCornerShape(18.dp)),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${formatSize(e.size)} · ${formatDate(e.lastModified)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun GenericPreviewCard(e: FileEntry) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ThumbBoxBig(e)
        Spacer(Modifier.height(16.dp))
        Text(e.name, fontWeight = FontWeight.SemiBold)
        Text("${formatSize(e.size)} · ${formatDate(e.lastModified)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { Intents.openWith(ctx, e) }) {
            Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open with…")
        }
    }
}
@Composable
private fun ThumbBoxBig(e: FileEntry) {
    com.filezen.files.ui.common.ThumbBox(e, 96.dp)
}