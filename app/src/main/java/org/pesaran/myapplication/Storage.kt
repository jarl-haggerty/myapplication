package org.pesaran.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.work.impl.model.Preference
import org.pesaran.thalamus.ThalamusOuterClass
import org.pesaran.thalamus.ThalamusOuterClass.StorageRecord
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import kotlin.time.TimeSource

class Storage(val context: Context) {
    var running = false

    private fun onTimeSeries(name: String, node: TimeSeriesNode) {
        /*if(!node.hasAnalogData() || !running) {
            return
        }

        var count = 0
        val builder = ThalamusOuterClass.StorageRecord.newBuilder()
            .setTime(node.time().inWholeNanoseconds)
            .setNode(name)
        val timeSeriesBuilder = builder.analogBuilder
        timeSeriesBuilder.setTime(node.time().inWholeNanoseconds)

        for(i in 0..<node.numChannels()) {
            val begin = count
            if(node.dataType() == TimeSeriesNode.DataType.SHORT) {
                val data = node.shorts(i)
                count += data.size
                timeSeriesBuilder.addAllIntData(node.shorts(i).map { it.toInt() })
                timeSeriesBuilder.addSampleIntervals(node.sampleInterval(i).inWholeNanoseconds)
            }
            val spanBuilder = timeSeriesBuilder.addSpansBuilder()
                .setName(node.name(i))
                .setBegin(begin)
                .setEnd(count)
                //.build()
        }

        //timeSeriesBuilder.build()
        val record = builder.build()
        //println(record.toString())
        writeRecord(record)*/
    }

    val connections = mutableListOf<Signal1<Node>.Connection>()
    fun add(name: String, node: Node) {
        if(node is TimeSeriesNode) {
            connections.add(node.ready.connect {
                onTimeSeries(name, node)
            })
        }
    }

    fun clear() {
        connections.forEach{ it.disconnect() }
        connections.clear()
    }

    var outputStream: OutputStream? = null
    var outputFile: File? = null

    private fun writeRecord(record: StorageRecord) {
        val size = record.serializedSize.toLong()
        val sizeBuffer = ByteBuffer.allocate(Long.SIZE_BYTES)
        sizeBuffer.order(ByteOrder.BIG_ENDIAN)
        sizeBuffer.putLong(size)
        sizeBuffer.rewind()
        outputStream!!.write(sizeBuffer.array())
        record.writeTo(outputStream)
    }

    private fun readRecord(stream: InputStream): StorageRecord? {
        if(stream.available() < 8) {
            return null
        }
        val sizeBuffer = ByteBuffer.allocate(8)
        var count = 0
        while(count < 8) {
            count += stream.read(sizeBuffer.array(), count, 8-count)
        }
        sizeBuffer.order(ByteOrder.BIG_ENDIAN)
        val size = sizeBuffer.getLong().toInt()

        if(stream.available() < size) {
            return null
        }
        val messageBuffer = ByteBuffer.allocate(size)
        count = 0
        while(count < size) {
            count += stream.read(messageBuffer.array(), count, size-count)
        }
        val record = ThalamusOuterClass.StorageRecord.parseFrom(messageBuffer)
        return record
    }

    private fun checkOutput() {
        try {
            //Are we running
            if(!running) {
                return
            }
            //Was output assigned
            if(outputFile == null) {
                return
            }
            //Does output exist
            val file = outputFile!!
            if(!file.exists()) {
                return
            }

            val stream = file.inputStream()
            val record = readRecord(file.inputStream())
            stream.close()
            //Record first record exist
            if(record == null) {
                return
            }

            val time = System.nanoTime()
            //Is first record 1 hour old
            if(time - record.time < 3600e9) {
              return
            }

            //Start new file
            prepareStorage()
        } finally {
            if(running) {
                Handler(Looper.getMainLooper()).postDelayed({checkOutput()}, 60000)
            }
        }
    }

    //We should update "recording-file" before we start writing
    @SuppressLint("ApplySharedPref")
    fun prepareStorage() {
        val filesDir = this.context.getExternalFilesDir(null)
        val recordingDir = File(filesDir, "recording")
        recordingDir.mkdir()
        val now = LocalDate.now()
        var rec = 1
        val month = now.monthValue.toString().padStart(2, '0')
        val day = now.dayOfMonth.toString().padStart(2, '0')
        var file = File(recordingDir, "sleeve.tha.${now.year}${month}${day}.${rec}")
        while(file.exists()) {
            ++rec
            file = File(recordingDir, "sleeve.tha.${now.year}${month}${day}.${rec}")
        }
        outputStream?.close()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString("recording-file", outputFile.toString()).commit()
        outputFile = file
        outputStream = file.outputStream()
        val startRecord = ThalamusOuterClass.StorageRecord.newBuilder()
            .setTime(System.nanoTime())
            .setNode("start-time")
            .build()
        writeRecord(startRecord)
    }

    fun start() {
        running = true
        prepareStorage()
        checkOutput()
    }

    fun stop() {
        running = false
        outputStream?.close()
    }
}