package com.java.myapplication

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.java.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme(dynamicColor = false) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebDavBackupScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun WebDavBackupScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var endpoint by remember { mutableStateOf(prefs.getString(KEY_ENDPOINT, "https://example.com/webdav/").orEmpty()) }
    var username by remember { mutableStateOf(prefs.getString(KEY_USERNAME, "").orEmpty()) }
    var password by remember { mutableStateOf(prefs.getString(KEY_PASSWORD, "").orEmpty()) }
    var autoEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_ENABLED, false)) }
    var intervalHours by remember { mutableStateOf(prefs.getInt(KEY_INTERVAL_HOURS, 24).toString()) }
    var compressBackup by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("请选择文件或文件夹并填写 WebDAV 地址") }
    val selectedItems = remember { mutableStateListOf<CachedBackupItem>() }

    fun refreshItems(items: List<CachedBackupItem>) {
        selectedItems.clear()
        selectedItems.addAll(items)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            refreshItems(BackupEngine.cacheSelection(context, uris))
            status = "已添加 ${uris.size} 个文件，当前 ${selectedItems.size} 项"
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            refreshItems(BackupEngine.cacheFolder(context, it))
            status = "已添加文件夹，当前 ${selectedItems.size} 项"
        }
    }

    fun persistConfig() {
        prefs.edit {
            putString(KEY_ENDPOINT, endpoint)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putBoolean(KEY_AUTO_ENABLED, autoEnabled)
            putInt(KEY_INTERVAL_HOURS, intervalHours.toIntOrNull()?.coerceAtLeast(1) ?: 24)
        }
    }

    fun enqueueBackup(reason: String) {
        if (selectedItems.isEmpty()) {
            status = "请先选择要备份的文件或文件夹"
            return
        }
        persistConfig()
        val request = OneTimeWorkRequestBuilder<ManualBackupWorker>().setInputData(
            workDataOf(
                ManualBackupWorker.KEY_ENDPOINT to endpoint,
                ManualBackupWorker.KEY_USERNAME to username,
                ManualBackupWorker.KEY_PASSWORD to password,
                ManualBackupWorker.KEY_COMPRESS to compressBackup,
            )
        ).build()
        WorkManager.getInstance(context).enqueue(request)
        status = reason
    }

    fun syncAutoBackup() {
        persistConfig()
        if (autoEnabled) {
            AutoBackupWorker.schedule(context, intervalHours.toIntOrNull()?.coerceAtLeast(1) ?: 24)
            status = "自动备份已开启"
        } else {
            AutoBackupWorker.cancel(context)
            status = "自动备份已关闭"
        }
    }

    LaunchedEffect(Unit) {
        refreshItems(BackupEngine.cachedFiles(context))
        if (autoEnabled && selectedItems.isNotEmpty()) {
            enqueueBackup("应用启动，已自动加入一次备份队列")
        }
    }

    LaunchedEffect(autoEnabled, intervalHours) {
        if (autoEnabled) syncAutoBackup()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E1525), Color(0xFF131E33), Color(0xFF0B1120))))
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeaderCard(selectedCount = selectedItems.size, status = status)
            ConfigCard(
                endpoint = endpoint,
                username = username,
                password = password,
                autoEnabled = autoEnabled,
                intervalHours = intervalHours,
                onEndpointChange = { endpoint = it },
                onUsernameChange = { username = it },
                onPasswordChange = { password = it },
                onAutoEnabledChange = { autoEnabled = it; syncAutoBackup() },
                onIntervalHoursChange = { intervalHours = it.filter(Char::isDigit).take(3) },
            )
            ActionCard(
                onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                onPickFolder = { folderPicker.launch(null) },
                onManualBackup = { enqueueBackup("已加入手动备份队列") },
                onAutoToggle = { autoEnabled = !autoEnabled; syncAutoBackup() },
                compressBackup = compressBackup,
                onCompressChange = { compressBackup = it },
                selectedCount = selectedItems.size,
                onClearSelection = {
                    refreshItems(BackupEngine.clearCache(context))
                    status = "已清空选择"
                },
            )
            SelectedItemsCard(
                items = selectedItems,
                onRemove = { item ->
                    refreshItems(BackupEngine.removeCachedItem(context, item.relativePath))
                    status = "已取消：${item.displayName}"
                },
            )
            TipsCard()
        }
    }
}

@Composable
private fun HeaderCard(selectedCount: Int, status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18253B))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.CloudDone, contentDescription = null, tint = Color(0xFF7DD3FC), modifier = Modifier.size(34.dp))
                Column {
                    Text("WebDAV 备份中心", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("文件/文件夹同选 · 可见列表 · 启动自动备份", color = Color(0xFFB4C6E7))
                }
            }
            Divider(color = Color(0x332B3E5E))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatChip(label = "已选项目", value = selectedCount.toString(), icon = Icons.Filled.FolderOpen)
                StatChip(label = "状态", value = status, icon = Icons.Filled.Schedule)
            }
        }
    }
}

@Composable
private fun ConfigCard(
    endpoint: String,
    username: String,
    password: String,
    autoEnabled: Boolean,
    intervalHours: String,
    onEndpointChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAutoEnabledChange: (Boolean) -> Unit,
    onIntervalHoursChange: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("连接设置", Icons.Filled.Cloud)
            OutlinedTextField(value = endpoint, onValueChange = onEndpointChange, label = { Text("WebDAV 地址") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = username, onValueChange = onUsernameChange, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = onPasswordChange, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = Color(0xFF86EFAC))
                Text("自动备份（打开应用时也会立即执行一次）", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = autoEnabled, onCheckedChange = onAutoEnabledChange)
            }
            OutlinedTextField(value = intervalHours, onValueChange = onIntervalHoursChange, label = { Text("自动备份间隔（小时）") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ActionCard(
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onManualBackup: () -> Unit,
    onAutoToggle: () -> Unit,
    compressBackup: Boolean,
    onCompressChange: (Boolean) -> Unit,
    selectedCount: Int,
    onClearSelection: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("操作", Icons.Filled.Backup)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onPickFiles, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("选文件")
                }
                Button(onClick = onPickFolder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("选文件夹")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onManualBackup, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("手动备份")
                }
                OutlinedButton(onClick = onClearSelection, enabled = selectedCount > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Clear, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("清空选择")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(checked = compressBackup, onCheckedChange = onCompressChange)
                Text("压缩备份（ZIP）", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = onAutoToggle, enabled = selectedCount > 0) { Text("切换自动备份") }
            }
        }
    }
}

@Composable
private fun SelectedItemsCard(items: List<CachedBackupItem>, onRemove: (CachedBackupItem) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("已选择内容", Icons.Filled.FolderOpen)
            if (items.isEmpty()) {
                Text("暂无选择。可添加多个文件，也可继续添加文件夹。", color = Color(0xFFB4C6E7))
            } else {
                items.take(80).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = Color(0xFF7DD3FC), modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatSize(item.sizeBytes), color = Color(0xFF9FB3D9), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onRemove(item) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "取消选择", tint = Color(0xFFFCA5A5))
                        }
                    }
                }
                if (items.size > 80) Text("还有 ${items.size - 80} 项未显示，将一起备份。", color = Color(0xFFB4C6E7))
            }
        }
    }
}

@Composable
private fun TipsCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("说明", Icons.Filled.Settings)
            Text("1. 可重复添加文件和文件夹，列表会显示当前缓存的备份项目。", color = Color(0xFFD6E4FF))
            Text("2. 点击垃圾桶可取消单个项目，点击“清空选择”可取消全部。", color = Color(0xFFD6E4FF))
            Text("3. 自动备份开启后，打开软件会立即尝试执行一次备份。", color = Color(0xFFD6E4FF))
        }
    }
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = Color(0xFF93C5FD))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatChip(label: String, value: String, icon: ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF22314D))) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = Color(0xFF7DD3FC))
            Column {
                Text(label, color = Color(0xFF9FB3D9), style = MaterialTheme.typography.labelSmall)
                Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

class ManualBackupWorker(
    appContext: Context,
    workerParameters: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = runCatching {
        val config = BackupConfig(
            endpoint = inputData.getString(KEY_ENDPOINT).orEmpty(),
            username = inputData.getString(KEY_USERNAME).orEmpty(),
            password = inputData.getString(KEY_PASSWORD).orEmpty(),
        )
        BackupEngine.backupCachedSelection(
            applicationContext,
            config,
            inputData.getBoolean(KEY_COMPRESS, true),
        )
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.failure() },
    )

    companion object {
        const val KEY_ENDPOINT = "manual_endpoint"
        const val KEY_USERNAME = "manual_username"
        const val KEY_PASSWORD = "manual_password"
        const val KEY_COMPRESS = "manual_compress"
    }
}