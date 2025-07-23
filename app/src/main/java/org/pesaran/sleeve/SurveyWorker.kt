package org.pesaran.sleeve

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import okhttp3.internal.notify

class SurveyWorker(val appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        // Create an explicit intent for an Activity in your app.
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        preferences.edit(commit = true) { putBoolean("show-survey", true) }

        val intent = Intent(appContext, MainActivity::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        var builder = NotificationCompat.Builder(appContext, "SLEEVE")
            .setSmallIcon(R.drawable.ss)
            .setContentTitle("Sleeve Survey")
            .setContentText("Please answer our survey")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = appContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(0, builder.build())

        return Result.success()
    }
}