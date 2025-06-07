package org.pesaran.myapplication

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private interface IADCNode : Node, TimeSeriesNode {}
class ADCNode : IADCNode {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var outputData = ByteBuffer.allocate(1)
    var running = false
    private var lastTime = 0.seconds
    private var currentTime = 0.seconds
    private var timestamp = 0.seconds
    private val _numChannels = 5
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

        val vref = 0.6
        val gain = 1
        val resolution = 14

        var currentChannel = 0
        var currentSample = 0
        outputData = ByteBuffer.allocate(8 * numPoints)
        while(data.remaining() > 1) {
            val value = if(data.remaining() > 1) {
                data.getShort()
            } else {
                (data.get().toInt() shl 8).toShort()
            }
            val scaled = value * vref / gain / ((1 shl resolution) - 1) * 1000
            outputData.putDouble((currentChannel*numSamples+currentSample)*8, scaled)
            currentChannel = (currentChannel + 1) % _numChannels
            if(currentChannel == 0) {
                ++currentSample
            }
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