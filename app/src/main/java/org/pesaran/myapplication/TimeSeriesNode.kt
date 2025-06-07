package org.pesaran.myapplication

import java.nio.DoubleBuffer
import java.nio.ShortBuffer
import kotlin.time.Duration

interface TimeSeriesNode {
    enum class DataType {
        SHORT,
        DOUBLE
    }
    var channelsChanged: Signal1<TimeSeriesNode>
    fun dataType(): DataType
    fun shorts(channel: Int): ShortBuffer {
        throw UnsupportedOperationException()
    }
    fun doubles(channel: Int): DoubleBuffer {
        throw UnsupportedOperationException()
    }
    fun numChannels(): Int
    fun sampleInterval(channel: Int): Duration
    fun time(): Duration
    fun name(channel: Int): String
    fun hasAnalogData(): Boolean
}