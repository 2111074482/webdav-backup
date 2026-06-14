package com.java.myapplication

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
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
        onProgress: (String) -> Unit = {},
    ): String {
        validateConfig(config)
        require(uris.isNotEmpty()) { "请至少选择一个文件" }

        onProgress("正在准备备份文件…")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val payloadName = if (compress || uris.size > 1) "webdav_backup_$timestamp.zip" else safeName(context, uris.first())
        val payload = if (compress || uris.size > 1) createZip(context, uris) else readUri(context, uris.first())

        onProgress("正在上传到 WebDAV…")
        upload(config, payloadName, payload)
        return "备份完成：$payloadName"
    }

    fun backupCachedSelection(
        context: Context,
        config: BackupConfig,
        compress: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): String {
        validateConfig(config)
        val files = cachedFiles(context)
        require(files.isNotEmpty()) { "没有可自动备份的缓存文件，请先手动选择文件或文件夹" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val payloadName = if (compress || files.size > 1) "webdav_backup_$timestamp.zip" else files.first().name
        onProgress(if (compress || files.size > 1) "正在压缩备份文件…" else "正在准备备份文件…")
        val payload = if (compress || files.size > 1) {
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    files.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativePath))
                        File(file.absolutePath).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                output.toByteArray()
            }
        } else {
            File(files.first().absolutePath).readBytes()
        }

        onProgress("正在上传到 WebDAV…")
        upload(config, payloadName, payload)
        return "备份完成：$payloadName"
    }

    fun cacheSelection(context: Context, uris: List<Uri>): List<CachedBackupItem> {
        val directory = cacheDirectory(context).also { it.mkdirs() }
        uris.forEach { uri ->
            val originalName = safeName(context, uri)
            val output = uniqueFile(directory, originalName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                output.outputStream().use { stream -> input.copyTo(stream) }
            }
        }
        return cachedFiles(context)
    }

    fun cacheFolder(context: Context, treeUri: Uri): List<CachedBackupItem> {
        cacheDirectory(context).mkdirs()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return cachedFiles(context)
        copyDocumentTree(context, root, root.name ?: "folder")
        return cachedFiles(context)
    }

    fun cachedFiles(context: Context): List<CachedBackupItem> {
        val root = cacheDirectory(context)
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
                CachedBackupItem(
                    name = file.name,
                    displayName = displayNameFor(relativePath),
                    relativePath = relativePath,
                    absolutePath = file.absolutePath,
                    sizeBytes = file.length(),
                )
            }
            .sortedBy { it.relativePath }
            .toList()
    }

    fun removeCachedItem(context: Context, relativePath: String): List<CachedBackupItem> {
        val root = cacheDirectory(context)
        File(root, relativePath).takeIf { it.exists() && it.isFile }?.delete()
        removeEmptyDirectories(root)
        return cachedFiles(context)
    }

    fun clearCache(context: Context): List<CachedBackupItem> {
        resetCache(context)
        return emptyList()
    }

    private fun validateConfig(config: BackupConfig) {
        require(config.endpoint.startsWith("http://") || config.endpoint.startsWith("https://")) {
            "WebDAV 地址必须以 http:// 或 https:// 开头"
        }
    }

    private fun resetCache(context: Context) {
        val directory = cacheDirectory(context)
        if (directory.exists()) directory.deleteRecursively()
        directory.mkdirs()
    }

    private fun cacheDirectory(context: Context): File = File(context.filesDir, "backup-selection")

    private fun copyDocumentTree(context: Context, document: DocumentFile, relativePath: String) {
        val safeRelativePath = relativePath.trim('/').ifBlank { document.name ?: "folder" }
        if (document.isDirectory) {
            document.listFiles().forEach { child ->
                val childName = child.name?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "item"
                copyDocumentTree(context, child, "$safeRelativePath/$childName")
            }
        } else if (document.isFile) {
            val output = File(cacheDirectory(context), safeRelativePath)
            output.parentFile?.mkdirs()
            context.contentResolver.openInputStream(document.uri)?.use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun removeEmptyDirectories(root: File) {
        root.walkBottomUp().filter { it.isDirectory && it != root && it.listFiles().isNullOrEmpty() }.forEach { it.delete() }
    }

    private fun uniqueFile(directory: File, originalName: String): File {
        var candidate = File(directory, originalName)
        if (!candidate.exists()) return candidate
        val dotIndex = originalName.lastIndexOf('.').takeIf { it > 0 }
        val base = dotIndex?.let { originalName.substring(0, it) } ?: originalName
        val ext = dotIndex?.let { originalName.substring(it) }.orEmpty()
        var counter = 2
        while (candidate.exists()) {
            candidate = File(directory, "${base} (${counter})${ext}")
            counter++
        }
        return candidate
    }

    private fun displayNameFor(relativePath: String): String = relativePath
        .substringAfterLast('/')
        .replace(Regex("^\\d+_"), "")
        .ifBlank { relativePath }

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
                    android.util.Base64.NO_WRAP,
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

data class CachedBackupItem(
    val name: String,
    val displayName: String,
    val relativePath: String,
    val absolutePath: String,
    val sizeBytes: Long,
)

data class BackupConfig(
    val endpoint: String,
    val username: String,
    val password: String,
)