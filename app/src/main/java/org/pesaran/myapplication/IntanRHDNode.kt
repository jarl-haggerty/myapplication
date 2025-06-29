package org.pesaran.myapplication

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.ShortBuffer
import kotlin.experimental.and
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private interface IIntanRHDNode : Node, TimeSeriesNode {}
class IntanRHDNode : IIntanRHDNode {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var outputData = ByteBuffer.allocate(1)
    var running = false
    private var lastTime = 0.seconds
    private var currentTime = 0.seconds
    private var timestamp = 0.seconds
    private val _numChannels = 16
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
        outputData = ByteBuffer.allocate(8 * numPoints)
        while(data.remaining() > 1) {
            val value = if(data.remaining() > 1) {
                data.getShort()
            } else {
                (data.get().toInt() shl 8).toShort()
            }
            if(value > 0) {
                val offset = value - 0x8000
                val result = offset * .195/1000
                outputData.putDouble((currentChannel*numSamples+currentSample)*8, result)
            } else {
                val offset = value and 0x7FFF
                val result = offset * .195/1000
                outputData.putDouble((currentChannel*numSamples+currentSample)*8, result)
            }
            currentChannel = (currentChannel + 1) % _numChannels
            if(currentChannel == 0) {
                ++currentSample
            }
        }
        timestamp = System.nanoTime().nanoseconds
        ready(this)
    }

    override fun dataType() = TimeSeriesNode.DataType.DOUBLE

    override fun numChannels() = _numChannels

    override fun sampleInterval(channel: Int) =  1.milliseconds

    override fun time() = timestamp

    override fun name(channel: Int): String {
        return when(channel) {
            0 -> "ch0"
            1 -> "ch1"
            2 -> "ch2"
            3 -> "ch3"
            4 -> "ch4"
            5 -> "ch5"
            6 -> "ch6"
            7 -> "ch7"
            8 -> "ch8"
            9 -> "ch9"
            10 -> "ch10"
            11 -> "ch11"
            12 -> "ch12"
            13 -> "ch13"
            14 -> "ch14"
            15 -> "ch15"
            else -> ""
        }
    }

    override fun doubles(channel: Int): DoubleBuffer {
        val result = outputData
            .asReadOnlyBuffer()
            .asDoubleBuffer()
        result.position(channel*numSamples)
        result.limit((channel+1)*numSamples)
        return result
    }

    override fun hasAnalogData() = true
}