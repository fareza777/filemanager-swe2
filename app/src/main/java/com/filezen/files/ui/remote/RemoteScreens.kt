package com.filezen.files.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.Routes
import com.filezen.files.core.model.formatDate
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.remote.*
import com.filezen.files.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- ViewModel ----------

class ConnectionsViewModel : ViewModel() {
    private val c get() = FileZenApp.c
    val connections = c.settings.remoteConnections
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _testing = MutableStateFlow<Long?>(null)   // conn id being tested
    val testing: StateFlow<Long?> = _testing
    private val _testResult = MutableStateFlow<Pair<Long, String>?>(null)
    val testResult: StateFlow<Pair<Long, String>?> = _testResult

    fun save(conn: RemoteConnection) = viewModelScope.launch {
        val list = connections.value.filter { it.id != conn.id } + conn
        c.settings.setRemoteConnections(list.sortedBy { it.label.lowercase() })
    }
    fun remove(id: Long) = viewModelScope.launch {
        c.settings.setRemoteConnections(connections.value.filter { it.id != id })
    }
    fun test(conn: RemoteConnection) {
        _testing.value = conn.id
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val fs = RemoteFs.of(conn)
                    val n = fs.list("").size
                    fs.close()
                    "Connected — $n item(s) at root"
                } catch (e: Exception) { "Failed: ${e.message}" }
            }
            _testing.value = null
            _testResult.value = conn.id to msg
        }
    }
    fun clearTest() { _testResult.value = null }
}

class RemoteBrowserViewModel : ViewModel() {
    private val _path = MutableStateFlow("")
    val path: StateFlow<String> = _path
    private val _entries = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val entries: StateFlow<List<RemoteEntry>> = _entries
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _busy = MutableStateFlow<String?>(null)   // label of running op
    val busy: StateFlow<String?> = _busy

    private var fs: RemoteFs? = null

    fun connect(conn: RemoteConnection) {
        if (fs != null) return
        _loading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                fs = RemoteFs.of(conn)
                load("")
            } catch (e: Exception) {
                _error.value = "Connect failed: ${e.message}"
                _loading.value = false
            }
        }
    }

    fun navigate(p: String) { _path.value = p; load(p) }
    fun up() {
        val p = _path.value.trim('/')
        if (p.isEmpty()) return
        navigate(p.substringBeforeLast('/', ""))
    }

    private fun load(p: String) {
        val f = fs ?: return
        _loading.value = true; _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _entries.value = f.list(p)
            } catch (e: Exception) {
                _error.value = e.message ?: "list failed"
            }
            _loading.value = false
        }
    }

    fun mkdir(name: String) = op("Creating $name") {
        fs?.mkdir(RemoteFs.joinPath(_path.value, name))
        load(_path.value)
    }
    fun delete(e: RemoteEntry) = op("Deleting ${e.name}") {
        fs?.delete(e.path, e.isDir)
        load(_path.value)
    }
    fun rename(e: RemoteEntry, to: String) = op("Renaming ${e.name}") {
        fs?.rename(e.path, RemoteFs.joinPath(_path.value, to))
        load(_path.value)
    }
    /** Download remote file into a local directory. */
    fun download(e: RemoteEntry, destDir: File) = op("Downloading ${e.name}") {
        val out = File(destDir, e.name)
        fs?.openInput(e.path)?.use { ins ->
            out.outputStream().use { ins.copyTo(it, 128 * 1024) }
        }
    }
    /** Upload a local file into the current remote dir. */
    fun upload(local: File) = op("Uploading ${local.name}") {
        local.inputStream().use { ins ->
            fs?.write(RemoteFs.joinPath(_path.value, local.name), ins, local.length())
        }
        load(_path.value)
    }

    private fun op(label: String, block: () -> Unit) {
        if (_busy.value != null) return
        _busy.value = label
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (e: Exception) { _error.value = e.message }
            _busy.value = null
        }
    }

    override fun onCleared() { runCatching { fs?.close() } }
}

// ---------- Connections screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(nav: NavController, vm: ConnectionsViewModel = viewModel()) {
    val conns by vm.connections.collectAsState()
    val testing by vm.testing.collectAsState()
    val testResult by vm.testResult.collectAsState()
    var editing by remember { mutableStateOf<RemoteConnection?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RemoteConnection?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote storage", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add connection") })
        },
    ) { padding ->
        if (conns.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(Icons.Rounded.CloudOff, "No connections yet",
                    "Add an SFTP, SMB, WebDAV or S3 server — it will show up here and in Browse.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(conns, key = { it.id }) { conn ->
                    ListItem(
                        headlineContent = { Text(conn.label.ifBlank { conn.host },
                            fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text("${conn.type.label} · ${conn.host}:${conn.port}" +
                                if (conn.root.isNotBlank()) " · ${conn.root}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(when (conn.type) {
                                RemoteType.SFTP -> Icons.Rounded.Terminal
                                RemoteType.SMB -> Icons.Rounded.Dns
                                RemoteType.WEBDAV -> Icons.Rounded.Http
                                RemoteType.S3 -> Icons.Rounded.CloudQueue
                            }, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Row {
                                if (testing == conn.id) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = { vm.test(conn) }) {
                                        Icon(Icons.Rounded.NetworkCheck, "Test",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { editing = conn }) {
                                    Icon(Icons.Rounded.Edit, "Edit", modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { deleting = conn }) {
                                    Icon(Icons.Rounded.Delete, "Delete", modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        modifier = Modifier.clickable { nav.navigate(Routes.remote(conn.id)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (adding || editing != null) {
        ConnectionDialog(initial = editing, onDismiss = { adding = false; editing = null }) { c ->
            scope.launch { vm.save(c) }
            adding = false; editing = null
        }
    }
    deleting?.let { c ->
        ConfirmDialog("Remove connection?", "Remove “${c.label.ifBlank { c.host }}”? The server itself is untouched.",
            "Remove", danger = true,
            onConfirm = { vm.remove(c.id); deleting = null },
            onDismiss = { deleting = null })
    }
    testResult?.let { (_, msg) ->
        AlertDialog(onDismissRequest = { vm.clearTest() },
            title = { Text("Connection test") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.clearTest() }) { Text("OK") } })
    }
}

@Composable
private fun ConnectionDialog(initial: RemoteConnection?,
                             onDismiss: () -> Unit,
                             onSave: (RemoteConnection) -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: RemoteType.SFTP) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var pass by remember { mutableStateOf(initial?.pass ?: "") }
    var root by remember { mutableStateOf(initial?.root ?: "") }
    var extra by remember { mutableStateOf(initial?.extra ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New connection" else "Edit connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteType.values().forEach { t ->
                        FilterChip(selected = type == t, onClick = {
                            type = t
                            if (port.isBlank() || port.toIntOrNull() == initial?.port)
                                port = t.defaultPort.toString()
                        }, label = { Text(t.label.split(" ")[0]) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it },
                    label = { Text(if (type == RemoteType.S3) "Endpoint (blank = AWS)" else "Host") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type != RemoteType.S3)
                    OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it },
                        label = { Text(if (type == RemoteType.S3) "Access key" else "Username") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(pass, { pass = it },
                        label = { Text(if (type == RemoteType.S3) "Secret key" else "Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f))
                }
                OutlinedTextField(root, { root = it },
                    label = { Text(when (type) {
                        RemoteType.SMB -> "Share/path (e.g. Documents/work)"
                        RemoteType.S3 -> "Bucket/prefix"
                        else -> "Start path (e.g. /home/me)"
                    }) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == RemoteType.S3)
                    OutlinedTextField(extra, { extra = it }, label = { Text("Region (default us-east-1)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == RemoteType.WEBDAV)
                    OutlinedTextField(extra, { extra = it }, label = { Text("https (blank) or http") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (host.isNotBlank() || type == RemoteType.S3) {
                    onSave(RemoteConnection(
                        id = initial?.id ?: System.currentTimeMillis(),
                        type = type,
                        label = label.ifBlank { host },
                        host = host.trim(),
                        port = port.toIntOrNull() ?: type.defaultPort,
                        user = user, pass = pass,
                        root = root.trim().trim('/'),
                        extra = extra.trim(),
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Remote browser ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(nav: NavController, connId: Long, vm: RemoteBrowserViewModel = viewModel()) {
    val conns by FileZenApp.c.settings.remoteConnections
        .collectAsState(initial = emptyList())
    val conn = conns.firstOrNull { it.id == connId }
    val path by vm.path.collectAsState()
    val entries by vm.entries.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var mkdir by remember { mutableStateOf(false) }
    var downloadTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var pickUpload by remember { mutableStateOf(false) }
    val favorites by FileZenApp.c.db.favorites().all().collectAsState(initial = emptyList())
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val uploadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Copy the picked content into cache first (remote write wants a File).
        scope.launch(Dispatchers.IO) {
            runCatching {
                val name = runCatching {
                    ctx.contentResolver.query(uri, arrayOf(
                        android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cur ->
                            if (cur.moveToFirst()) cur.getString(0) else null
                        }
                }.getOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "upload.bin"
                val tmp = File(ctx.cacheDir, name)
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                }
                vm.upload(tmp)
                tmp.delete()
            }
        }
    }

    LaunchedEffect(conn) { conn?.let { vm.connect(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conn?.label?.ifBlank { conn.host } ?: "Remote",
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${conn?.type?.label ?: ""} · /$path",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (path.isNotBlank()) vm.up() else nav.popBackStack()
                    }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.navigate(path) }) {
                        Icon(Icons.Rounded.Refresh, "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = { pickUpload = true },
                    modifier = Modifier.padding(bottom = 10.dp)) {
                    Icon(Icons.Rounded.UploadFile, "Upload file here")
                }
                ExtendedFloatingActionButton(onClick = { mkdir = true },
                    icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    text = { Text("New folder") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            busy?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (path.isNotBlank()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.up() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, "Up", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("/$path", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                loading -> Column(Modifier.padding(horizontal = 10.dp)) { repeat(8) { SkeletonRow() } }
                entries.isEmpty() && conn != null && error == null ->
                    EmptyState(Icons.Rounded.CloudOff, "Empty",
                        "No files here — or the connection failed above.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name, maxLines = 1,
                                overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    (if (e.isDir) "Folder" else formatSize(e.size)) +
                                        if (e.mtime > 0) " · ${formatDate(e.mtime)}" else "",
                                    style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                Icon(if (e.isDir) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                                    null,
                                    tint = if (e.isDir) Color(0xFFF5B94E)
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingContent = {
                                Row {
                                    if (!e.isDir) IconButton(onClick = { downloadTarget = e }) {
                                        Icon(Icons.Rounded.Download, "Download",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { renameTarget = e }) {
                                        Icon(Icons.Rounded.Edit, "Rename",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { deleteTarget = e }) {
                                        Icon(Icons.Rounded.Delete, "Delete",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            modifier = Modifier.clickable {
                                if (e.isDir) vm.navigate(e.path)
                                else downloadTarget = e
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    mkdir.takeIf { it }?.let {
        TextInputDialog("New remote folder", hint = "Folder name",
            onConfirm = { vm.mkdir(it); mkdir = false }, onDismiss = { mkdir = false })
    }
    renameTarget?.let { e ->
        TextInputDialog("Rename", initial = e.name, hint = "New name",
            onConfirm = { vm.rename(e, it); renameTarget = null },
            onDismiss = { renameTarget = null })
    }
    deleteTarget?.let { e ->
        ConfirmDialog("Delete remote ${if (e.isDir) "folder" else "file"}?",
            "“${e.name}” will be deleted on the server. This can't be undone.",
            "Delete", danger = true,
            onConfirm = { vm.delete(e); deleteTarget = null },
            onDismiss = { deleteTarget = null })
    }
    downloadTarget?.let { e ->
        DestinationSheet(favorites = favorites,
            currentPath = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                .absolutePath,
            shareDir = FileZenApp.c.transfer.shareDir,
            onPick = { dest -> vm.download(e, File(dest)); downloadTarget = null },
            onDismiss = { downloadTarget = null })
    }
    if (pickUpload) {
        LaunchedEffect(Unit) {
            pickUpload = false
            uploadLauncher.launch("*/*")
        }
    }
}
