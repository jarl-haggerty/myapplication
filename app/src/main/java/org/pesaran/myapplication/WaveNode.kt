package org.pesaran.myapplication

import android.os.Handler
import android.os.Looper
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

interface GraphNode : Node, TimeSeriesNode {}
class WaveNode : GraphNode {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var data = mutableListOf<Short>()
    var running = false
    private var lastTime = 0.seconds
    private var currentTime = 0.seconds
    private var timestamp = 0.seconds

    private fun publish() {
        if(!running) {
            return
        }
        currentTime = System.nanoTime().nanoseconds
        data.clear()
        while(lastTime < currentTime) {
            data.add((sin(lastTime.inWholeMilliseconds/1000.0)*Short.MAX_VALUE).toInt().toShort())
            lastTime += 1.milliseconds
        }
        timestamp = lastTime - 1.milliseconds
        ready(this)
        Handler(Looper.getMainLooper()).postDelayed({publish()}, 16)
    }

    fun start() {
        running = true
        lastTime = System.nanoTime().nanoseconds
        publish()
    }

    fun stop() {
        running = false
    }

    fun toggle() {
        if(running) {
            stop()
        } else {
            start()
        }
    }

    override fun dataType() = TimeSeriesNode.DataType.SHORT

    override fun numChannels() = 1

    override fun sampleInterval(channel: Int) =  1.milliseconds

    override fun time() = timestamp

    override fun name(channel: Int): String {
        return "sin"
    }

    override fun shorts(channel: Int): List<Short> {
        return data
    }

    override fun hasAnalogData() = true
}