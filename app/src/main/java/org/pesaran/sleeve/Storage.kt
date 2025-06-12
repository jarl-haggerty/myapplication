package org.pesaran.sleeve

import android.annotation.SuppressLint
import android.content.Context
import androidx.preference.PreferenceManager
import org.pesaran.thalamus.ThalamusOuterClass
import org.pesaran.thalamus.ThalamusOuterClass.StorageRecord
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class Storage(val context: Context) {
    var running = false

    private fun onTimeSeries(name: String, node: TimeSeriesNode) {
        if(!node.hasAnalogData() || !running) {
            return
        }

        var count = 0
        val builder = ThalamusOuterClass.StorageRecord.newBuilder()
            .setTime(node.time().inWholeNanoseconds)
            .setNode(name)
        val timeSeriesBuilder = builder.analogBuilder
        timeSeriesBuilder.setTime(node.time().inWholeNanoseconds)
        if(node.dataType() == TimeSeriesNode.DataType.SHORT) {
            timeSeriesBuilder.setIsIntData(true)
        }

        for(i in 0..<node.numChannels()) {
            val begin = count
            if(node.dataType() == TimeSeriesNode.DataType.SHORT) {
                val data = node.shorts(i)
                count += data.remaining()
                while(data.remaining() > 0) {
                    timeSeriesBuilder.addIntData(data.get().toInt())
                }
                timeSeriesBuilder.addSampleIntervals(node.sampleInterval(i).inWholeNanoseconds)
            } else if(node.dataType() == TimeSeriesNode.DataType.DOUBLE) {
                val data = node.doubles(i)
                count += data.remaining()
                while(data.remaining() > 0) {
                    timeSeriesBuilder.addData(data.get())
                }
                timeSeriesBuilder.addSampleIntervals(node.sampleInterval(i).inWholeNanoseconds)
            }
            timeSeriesBuilder.addSpansBuilder()
                .setName(node.name(i))
                .setBegin(begin)
                .setEnd(count)
                //.build()
        }

        //timeSeriesBuilder.build()
        val record = builder.build()
        //println(record.toString())
        writeRecord(record)
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
    var outputStartTime: Duration = 0.seconds

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
        //Does first record exist
        if(record == null) {
            return
        }

        val time = System.nanoTime().nanoseconds
        //Is first record 1 hour old
        if(time - outputStartTime < 1.hours) {
            return
        }

        //Start new file
        prepareStorage()
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
            file = File(recordingDir, "sleeve.tha.${now.year}${month}${day}.${++rec}")
        }
        outputStream?.close()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit(commit = true) { putString("recording-file", outputFile.toString()) }
        outputFile = file
        outputStream = file.outputStream()
        outputStartTime = System.nanoTime().nanoseconds
    }

    val scope = CoroutineScope(Dispatchers.Main)

    var looping = false
    suspend fun loop() {
        while(true) {
            delay(1.seconds)
            checkOutput()
        }
    }

    fun start() {
        running = true
        prepareStorage()
        if(!looping) {
            looping = true
            scope.launch { loop() }
        }
    }

    fun stop() {
        running = false
        outputStream?.close()
    }
}