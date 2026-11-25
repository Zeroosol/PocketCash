package com.patternsystems.pocketcash.data

import android.content.Context
import android.net.Uri
import com.patternsystems.pocketcash.BuildConfig
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.Backup
import com.patternsystems.pocketcash.core.BackupContents
import com.patternsystems.pocketcash.core.BackupError
import com.patternsystems.pocketcash.core.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant

/**
 * Reads and writes backup files (docs/spec.md section 11) through the system's file picker (Storage Access
 * Framework), so the file only goes where the person saves it.
 */
object BackupFiles {
    /** A backup bigger than this can't be ours (a year of heavy use is well under 1 MB). */
    private const val MAX_BYTES = 32 * 1024 * 1024

    sealed interface ReadResult {
        data class Ok(val contents: BackupContents) : ReadResult
        data class Rejected(val error: BackupError) : ReadResult
        data object Unreadable : ReadResult
    }

    suspend fun write(context: Context, uri: Uri, data: AppData): Boolean = withContext(Dispatchers.IO) {
        val bytes = Backup.encode(data, Instant.now(), platform = "android", appVersion = BuildConfig.VERSION_NAME).toByteArray(Charsets.UTF_8)
        try {
            // "wt" truncates an existing file; some providers only know "w".
            val out = try {
                context.contentResolver.openOutputStream(uri, "wt")
            } catch (e: IllegalArgumentException) {
                context.contentResolver.openOutputStream(uri, "w")
            } catch (e: FileNotFoundException) {
                context.contentResolver.openOutputStream(uri, "w")
            }
            out?.use { it.write(bytes) } != null
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    suspend fun read(context: Context, uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = input.readNBytesCompat(MAX_BYTES + 1)
                if (buffer.size > MAX_BYTES) return@withContext ReadResult.Rejected(BackupError.NotABackup)
                buffer
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return@withContext ReadResult.Unreadable
        try {
            ReadResult.Ok(Backup.decode(String(bytes, Charsets.UTF_8)))
        } catch (e: BackupException) {
            ReadResult.Rejected(e.error)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}
