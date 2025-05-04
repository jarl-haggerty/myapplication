package org.pesaran.myapplication

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UploadWorker(appContext: Context, workerParams: WorkerParameters):
    Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val currentFile = File(preferences.getString("recording-file", "/sleeve-fake-file")!!)
        val id = File(preferences.getString("id", "")!!)

        val filesDir = applicationContext.getExternalFilesDir(null)
        val recordingDir = File(filesDir, "recording")
        recordingDir.mkdir()

        val url = URL("http://192.168.0.46/sleeve/$id")
        for(file in recordingDir.listFiles()!!) {
            if(file.equals(currentFile)) {
                continue
            }
            val connection = url.openConnection()
            connection.doOutput = true
            file.inputStream().copyTo(connection.getOutputStream())
            val responseCode = (connection as HttpURLConnection).responseCode
            if(responseCode / 100 == 2) {
                file.delete()
            }
        }
        return Result.success()
    }
}