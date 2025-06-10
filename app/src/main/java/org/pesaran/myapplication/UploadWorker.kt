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
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.call.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.client.request.forms.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class UploadWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val host = "http://128.91.19.194:8081"

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

        runBlocking {
            val client = HttpClient(CIO)
            val now = System.currentTimeMillis()
            recordingDir.listFiles().filter { now - it.lastModified() > 10e3 }.filter {
                val response = client.get("$host/exists?uuid=$uuid&path=${it.name}")
                val text: String = response.body()
                val cleaned = text.trim().lowercase()
                cleaned != "true"
            }.forEach {
                val response = client.submitFormWithBinaryData(
                    url = "$host/upload?uuid=$uuid&path=${it.name}",
                    formData = formData {
                        append(it.name, it.inputStream().asInput(), Headers.build {
                            append(HttpHeaders.ContentType, "application/octet-stream")
                            append(HttpHeaders.ContentDisposition, "filename=\"${it.name}\"")
                        })
                    }
                )
                println("uplaaded $it ${it.length()}")
                println("response $response")
            }
        }

        return Result.success()
    }
}