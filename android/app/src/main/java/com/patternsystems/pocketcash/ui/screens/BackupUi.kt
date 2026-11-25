package com.patternsystems.pocketcash.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.Backup
import com.patternsystems.pocketcash.core.BackupContents
import com.patternsystems.pocketcash.core.BackupError
import com.patternsystems.pocketcash.core.Problem
import com.patternsystems.pocketcash.data.AppLock
import com.patternsystems.pocketcash.data.BackupFiles
import com.patternsystems.pocketcash.ui.fullDate
import com.patternsystems.pocketcash.ui.theme.Cash
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate

/** "Back up to a file": the system's file picker saves `PocketCash-backup-YYYY-MM-DD.json` wherever the person likes. */
@Composable
fun rememberBackupExport(app: AppScope): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.scope.launch {
            val ok = BackupFiles.write(context, uri, app.store.data.value)
            app.toast(app.str(if (ok) R.string.backup_saved else R.string.backup_save_failed))
        }
    }
    return {
        AppLock.expectReturn()
        try {
            launcher.launch(Backup.fileName(LocalDate.now()))
        } catch (e: ActivityNotFoundException) {
            app.toast(app.str(R.string.backup_no_picker))
        }
    }
}

/**
 * "Restore from a file": pick a file, see what it holds, confirm "Replace all data on this phone". A file that fails
 * validation changes nothing and says why (docs/spec.md section 11).
 */
@Composable
fun rememberRestore(app: AppScope): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<BackupContents?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.scope.launch {
            when (val result = BackupFiles.read(context, uri)) {
                is BackupFiles.ReadResult.Ok -> pending = result.contents
                is BackupFiles.ReadResult.Rejected -> problem = rejection(app, result.error)
                BackupFiles.ReadResult.Unreadable -> problem = app.str(R.string.restore_unreadable)
            }
        }
    }

    pending?.let { contents ->
        val numbers = NumberFormat.getIntegerInstance()
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.restore_summary,
                            numbers.format(contents.wallets.size),
                            numbers.format(contents.transactions.size),
                            fullDate(contents.exportedAt),
                        ),
                    )
                    Text(stringResource(R.string.restore_warning), color = Cash.colors.subtle, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    app.scope.launch {
                        app.store.replaceAll(contents)
                        app.navigate(Destination.HOME)
                        app.toast(app.str(R.string.restore_done))
                    }
                }) { Text(stringResource(R.string.restore_replace), color = Cash.colors.over) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text(stringResource(R.string.restore_failed_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }

    return {
        AppLock.expectReturn()
        try {
            // Some file apps call a .json file text or a plain download, so offer those too; the file is checked anyway.
            launcher.launch(arrayOf(Backup.MIME_TYPE, "text/*", "application/octet-stream"))
        } catch (e: ActivityNotFoundException) {
            app.toast(app.str(R.string.backup_no_picker))
        }
    }
}

private fun rejection(app: AppScope, error: BackupError): String = when (error) {
    BackupError.NotABackup -> app.str(R.string.restore_not_backup)
    is BackupError.TooNew -> app.str(R.string.restore_too_new)
    is BackupError.Invalid -> {
        val value = error.value?.take(40) ?: ""
        val detail = when (error.problem) {
            Problem.MALFORMED -> app.str(R.string.restore_problem_malformed)
            Problem.NO_WALLETS -> app.str(R.string.restore_problem_no_wallets)
            Problem.BAD_AMOUNT -> app.str(R.string.restore_problem_amount, value)
            Problem.BAD_DATE -> app.str(R.string.restore_problem_date, value)
            Problem.BAD_ID -> app.str(R.string.restore_problem_id, value)
            Problem.BAD_KIND -> app.str(R.string.restore_problem_kind, value)
            Problem.BAD_CURRENCY -> app.str(R.string.restore_problem_currency, value)
            Problem.DUPLICATE_ID -> app.str(R.string.restore_problem_duplicate, value)
        }
        app.str(R.string.restore_damaged, detail)
    }
}
