package org.pesaran.sleeve

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class UploadWorker(val appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val host = "https://20.55.36.17"

        val certStream = appContext.getResources().openRawResource(R.raw.sleeve_d50fb63dd19340d2b20aaebeeaf168d4)
        val cf = CertificateFactory.getInstance("X.509")
        val ca = cf.generateCertificate(certStream)
        certStream.close()

        val keyStore: KeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("ca", ca)

        val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
        val tmf = TrustManagerFactory.getInstance(tmfAlgorithm)
        tmf.init(keyStore)

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
            val client = HttpClient(CIO) {
                engine {
                    https {
                        trustManager = tmf.trustManagers?.first { it is X509TrustManager } as X509TrustManager
                    }
                }
            }
            val now = System.currentTimeMillis()

            val allFiles = recordingDir.listFiles()!!
            println("Found ${allFiles.contentToString()}")
            val newFiles = allFiles.filter { now - it.lastModified() > 10e3 }.filter {
                val response = client.get("$host/exists?uuid=$uuid&path=${it.name}")
                val text: String = response.body()
                val cleaned = text.trim().lowercase()
                cleaned != "true"
            }
            println("Uploading $newFiles")
            newFiles.forEach {
                val stream = it.inputStream().asInput()
                val response = client.submitFormWithBinaryData(
                    url = "$host/upload?uuid=$uuid&path=${it.name}",
                    formData = formData {
                        append(it.name, stream, Headers.build {
                            append(HttpHeaders.ContentType, "application/octet-stream")
                            append(HttpHeaders.ContentDisposition, "filename=\"${it.name}\"")
                        })
                    }
                )
                stream.close()
                println("uploaded $it ${it.length()}")
                println("response $response")
            }
        }

        return Result.success()
    }
}