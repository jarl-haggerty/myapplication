package org.pesaran.myapplication

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private interface IICM20948Node : Node, TimeSeriesNode {}
class ICM20948Node : IICM20948Node {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var outputData = ByteBuffer.allocate(1)
    var running = false
    private var lastTime = 0.seconds
    private var currentTime = 0.seconds
    private var timestamp = 0.seconds
    private val _numChannels = 6
    private var numSamples = 0

    fun process(block: Block) {
        val data = block.data
        data.order(ByteOrder.BIG_ENDIAN)

        var numPoints = data.remaining() / 2
        if(data.remaining() % 2 != 0) {
            ++numPoints
        }

        val remainder = numPoints % _numChannels
        if(remainder != 0) {
            numPoints += _numChannels - remainder
        }

        numSamples = numPoints / _numChannels

        var currentChannel = 0
        var currentSample = 0
        outputData = ByteBuffer.allocate(2*numPoints)
        while(data.remaining() > 1) {
            outputData.putShort((currentChannel*numSamples+currentSample)*2, data.getShort())
            currentChannel = (currentChannel + 1) % _numChannels
            if(currentChannel == 0) {
                ++currentSample
            }
        }
        if (data.remaining() > 0) {
            outputData.put((currentChannel*numSamples+currentSample)*2, data.get())
        }
        timestamp = System.nanoTime().nanoseconds
        ready(this)
    }

    override fun dataType() = TimeSeriesNode.DataType.SHORT

    override fun numChannels() = _numChannels

    override fun sampleInterval(channel: Int) =  10.milliseconds

    override fun time() = timestamp

    override fun name(channel: Int): String {
        return when(channel) {
            0 -> "acc_x"
            1 -> "acc_y"
            2 -> "acc_z"
            3 -> "gyro_x"
            4 -> "gyro_y"
            5 -> "gyro_z"
            else -> ""
        }
    }

    override fun shorts(channel: Int): ShortBuffer {
        val result = outputData
            .asReadOnlyBuffer()
            .asShortBuffer()
        result.position(channel*numSamples)
        result.limit((channel+1)*numSamples)
        return result
    }

    override fun hasAnalogData() = true
}