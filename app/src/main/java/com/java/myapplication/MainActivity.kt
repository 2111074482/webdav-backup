package com.java.myapplication

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.java.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    var status by remember { mutableStateOf("请选择文件并填写 WebDAV 地址") }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedUris.clear()
        selectedUris.addAll(uris)
        if (uris.isNotEmpty()) {
            BackupEngine.cacheSelection(context, uris)
            status = "已选择 ${uris.size} 个文件"
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

    fun runManualBackup() {
        if (selectedUris.isEmpty()) {
            status = "请先选择要备份的文件"
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
        status = "已加入手动备份队列"
    }

    LaunchedEffect(autoEnabled, intervalHours) {
        if (autoEnabled) syncAutoBackup()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E1525), Color(0xFF131E33), Color(0xFF0B1120))))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeaderCard(selectedCount = selectedUris.size, status = status)
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
                onPickFiles = { picker.launch(arrayOf("*/*")) },
                onManualBackup = { runManualBackup() },
                onAutoToggle = { autoEnabled = !autoEnabled; syncAutoBackup() },
                compressBackup = compressBackup,
                onCompressChange = { compressBackup = it },
                selectedCount = selectedUris.size,
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
                    Text("支持 HTTP / HTTPS · 多选文件 · 压缩上传", color = Color(0xFFB4C6E7))
                }
            }
            Divider(color = Color(0x332B3E5E))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatChip(label = "已选文件", value = selectedCount.toString(), icon = Icons.Filled.FolderOpen)
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
                Text("自动备份", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = autoEnabled, onCheckedChange = onAutoEnabledChange)
            }
            OutlinedTextField(value = intervalHours, onValueChange = onIntervalHoursChange, label = { Text("自动备份间隔（小时）") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ActionCard(
    onPickFiles: () -> Unit,
    onManualBackup: () -> Unit,
    onAutoToggle: () -> Unit,
    compressBackup: Boolean,
    onCompressChange: (Boolean) -> Unit,
    selectedCount: Int,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("操作", Icons.Filled.Backup)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onPickFiles, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("选择文件")
                }
                Button(onClick = onManualBackup, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("手动备份")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(checked = compressBackup, onCheckedChange = onCompressChange)
                Text("压缩备份（ZIP）", color = Color.White)
                TextButton(onClick = onAutoToggle, enabled = selectedCount > 0) { Text("切换自动备份") }
            }
        }
    }
}

@Composable
private fun TipsCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("说明", Icons.Filled.Settings)
            Text("1. 先填写支持 HTTP 或 HTTPS 的 WebDAV 地址。", color = Color(0xFFD6E4FF))
            Text("2. 使用“选择文件”可多选文件，随后可手动备份或开启自动备份。", color = Color(0xFFD6E4FF))
            Text("3. 自动备份会保存最近一次选择的文件缓存，并按设定间隔执行。", color = Color(0xFFD6E4FF))
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
                Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
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