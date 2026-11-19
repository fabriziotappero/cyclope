package com.fab.cyclope

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log as AndroidLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Logs to logcat and, in debug builds only, appends each line to Downloads/cyclope_log.txt (Android 10 and later),
// so the log can be read on the phone without adb. The file is cleared on the first write of each app launch.
// Release builds skip the file: each write opens the file through MediaStore, which is slow.
object Log {

    private const val FILE_NAME = "cyclope_log.txt"
    private var clearedThisRun = false

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        AndroidLog.i(tag, message)
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(FILE_NAME),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            } ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }) ?: return
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val mode = if (clearedThisRun) "wa" else "wt"
            resolver.openOutputStream(uri, mode)?.bufferedWriter()?.use { it.write("$time $tag: $message\n") }
            clearedThisRun = true
        } catch (e: Exception) {
            AndroidLog.w(tag, "Could not write log file: ${e.message}")
        }
    }
}
