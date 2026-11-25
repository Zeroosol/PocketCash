package com.patternsystems.pocketcash.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.Csv
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** CSV export (Pro): writes every transaction to a temporary file and opens the share sheet. */
object Export {
    fun shareCsv(context: Context, data: AppData) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "pocketcash-${LocalDate.now()}.csv")
        file.writeText(Csv.export(data.sortedWallets, data.transactions, ZoneId.systemDefault()))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.csv_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.csv_export)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
