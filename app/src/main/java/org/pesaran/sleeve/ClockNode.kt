package org.pesaran.sleeve

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.DoubleBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class ClockNode : GraphNode {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var data = ByteBuffer.allocate(1)
    private val byteStream = ByteArrayOutputStream()
    private val dataStream = DataOutputStream(byteStream)

    private var cosData = ByteBuffer.allocate(1)
    private val cosByteStream = ByteArrayOutputStream()
    private val cosDataStream = DataOutputStream(cosByteStream)

    private var squareData = ByteBuffer.allocate(1)
    private val squareByteStream = ByteArrayOutputStream()
    private val squareDataStream = DataOutputStream(squareByteStream)

    var running = false
    private var lastTime = 0.seconds
    private var currentTime = 0.seconds
    private var timestamp = 0.seconds

    private val epoch = doubleArrayOf(0.0)

    private fun publish() {
        if(!running) {
            return
        }
        currentTime = System.nanoTime().nanoseconds
        epoch[0] = System.currentTimeMillis()*1e6
        ready(this)
        Handler(Looper.getMainLooper()).postDelayed({publish()}, 1000)
    }

    fun start() {
        running = true
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

    override fun dataType() = TimeSeriesNode.DataType.DOUBLE

    override fun numChannels() = 1

    override fun sampleInterval(channel: Int) =  1.seconds

    override fun time() = currentTime

    override fun name(channel: Int): String {
        return when(channel) {
            0 -> "Epoch"
            else -> ""
        }
    }

    override fun doubles(channel: Int): DoubleBuffer {
        return when(channel) {
            0 -> DoubleBuffer.wrap(epoch)
            else -> throw UnsupportedOperationException()
        }
    }

    override fun hasAnalogData() = true
}