package org.pesaran.myapplication

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import androidx.core.content.edit

class UploadWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val host = "http://128.91.19.194"

        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        var uuid = preferences.getString("uuid", "")
        if (uuid == null || uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString()
            preferences.edit(commit = true) { putString("uuid", uuid) }
        }

        val currentFile = File(preferences.getString("recording-file", "/sleeve-fake-file")!!)
        val id = File(preferences.getString("id", "")!!)

        val filesDir = applicationContext.getExternalFilesDir(null)
        val recordingDir = File(filesDir, "recording")
        recordingDir.mkdir()


        val now = System.currentTimeMillis()
        recordingDir.listFiles().filter { now - it.lastModified() > 10e3 }.filter {
            val url = URL("$host/exists/?uuid=$uuid&path=${it.name}")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            text.trim().lowercase() != "true"
        }.forEach {
            val url = URL("$host/exists/?upload=$uuid&path=${it.name}")
            val connection = url.openConnection() as HttpURLConnection
            connection.doOutput = true
            connection.requestMethod = "POST"
            val fileStream = it.inputStream()
            fileStream.copyTo(connection.outputStream)
            fileStream.close()
            connection.outputStream.close()
        }

        return Result.success()
    }
}