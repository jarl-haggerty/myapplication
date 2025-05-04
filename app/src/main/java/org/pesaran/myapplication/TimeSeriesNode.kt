package org.pesaran.myapplication

import kotlin.time.Duration

interface TimeSeriesNode {
    enum class DataType {
        SHORT
    }
    var channelsChanged: Signal1<TimeSeriesNode>
    fun dataType(): DataType
    fun shorts(channel: Int): List<Short>
    fun numChannels(): Int
    fun sampleInterval(channel: Int): Duration
    fun time(): Duration
    fun name(channel: Int): String
    fun hasAnalogData(): Boolean
}