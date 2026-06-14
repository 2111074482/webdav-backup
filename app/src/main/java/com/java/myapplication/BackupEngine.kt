package com.java.myapplication

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupEngine {
    fun backup(
        context: Context,
        uris: List<Uri>,
        config: BackupConfig,
        compress: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): String {
        require(config.endpoint.startsWith("http://") || config.endpoint.startsWith("https://")) {
            "WebDAV 地址必须以 http:// 或 https:// 开头"
        }
        require(uris.isNotEmpty()) { "请至少选择一个文件" }

        onProgress("正在准备备份文件…")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val payloadName = if (compress) "webdav_backup_$timestamp.zip" else safeName(context, uris.first())
        val payload = if (compress) createZip(context, uris) else readUri(context, uris.first())

        onProgress("正在上传到 WebDAV…")
        upload(config, payloadName, payload)
        return "备份完成：$payloadName"
    }

    fun backupCachedSelection(
        context: Context,
        config: BackupConfig,
        compress: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): String {
        val directory = File(context.filesDir, "backup-selection")
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        require(files.isNotEmpty()) { "没有可自动备份的缓存文件，请先手动选择文件" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val payloadName = if (compress || files.size > 1) "webdav_backup_$timestamp.zip" else files.first().name
        onProgress(if (compress || files.size > 1) "正在压缩备份文件…" else "正在准备备份文件…")
        val payload = if (compress || files.size > 1) {
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    files.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                output.toByteArray()
            }
        } else {
            files.first().readBytes()
        }

        onProgress("正在上传到 WebDAV…")
        upload(config, payloadName, payload)
        return "备份完成：$payloadName"
    }

    fun cacheSelection(context: Context, uris: List<Uri>) {
        val directory = File(context.filesDir, "backup-selection")
        if (directory.exists()) directory.deleteRecursively()
        directory.mkdirs()
        uris.forEachIndexed { index, uri ->
            val name = "${index + 1}_${safeName(context, uri)}"
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(directory, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun createZip(context: Context, uris: List<Uri>): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                uris.forEachIndexed { index, uri ->
                    val name = "${index + 1}_${safeName(context, uri)}"
                    zip.putNextEntry(ZipEntry(name))
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun readUri(context: Context, uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选文件")
    }

    private fun upload(config: BackupConfig, fileName: String, bytes: ByteArray) {
        val target = config.endpoint.trimEnd('/') + "/" + fileName.encodePathSegment()
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Content-Length", bytes.size.toString())
            if (config.username.isNotBlank() || config.password.isNotBlank()) {
                val token = android.util.Base64.encodeToString(
                    "${config.username}:${config.password}".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                setRequestProperty("Authorization", "Basic $token")
            }
        }

        connection.outputStream.use { ByteArrayInputStream(bytes).copyTo(it) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val message = connection.errorStream?.bufferedReader()?.readText().orEmpty()
            error("WebDAV 上传失败：HTTP $code $message")
        }
        connection.disconnect()
    }

    private fun safeName(context: Context, uri: Uri): String {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
        return (displayName ?: uri.lastPathSegment ?: "file.bin")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "file.bin" }
    }

    private fun String.encodePathSegment(): String =
        split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

data class BackupConfig(
    val endpoint: String,
    val username: String,
    val password: String,
)
