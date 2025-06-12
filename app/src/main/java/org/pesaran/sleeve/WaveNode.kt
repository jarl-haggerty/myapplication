package org.pesaran.sleeve

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

interface GraphNode : Node, TimeSeriesNode {}
class WaveNode : GraphNode {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var data = ByteBuffer.allocate(1)
    private val byteStream = ByteArrayOutputStream()
    private val dataStream = DataOutputStream(byteStream)

    private var cosData = ByteBuffer.allocate(1)
    private val cosByteStream = ByteArrayOutputStream()
    private val cosDataStream = DataOutputStream(cosByteStream)

    var running = false
    private var lastTime = 0.seconds
    private var currentTime = 0.seconds
    private var timestamp = 0.seconds

    private fun publish() {
        if(!running) {
            return
        }
        currentTime = System.nanoTime().nanoseconds
        byteStream.reset()
        cosByteStream.reset()
        while(lastTime < currentTime) {
            dataStream.writeShort((sin(lastTime.inWholeMilliseconds/1000.0)*Short.MAX_VALUE).toInt())
            cosDataStream.writeShort((cos(lastTime.inWholeMilliseconds/1000.0)*Short.MAX_VALUE).toInt())
            lastTime += 1.milliseconds
        }
        timestamp = lastTime - 1.milliseconds
        data = ByteBuffer.wrap(byteStream.toByteArray())
        cosData = ByteBuffer.wrap(cosByteStream.toByteArray())
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

    override fun numChannels() = 2

    override fun sampleInterval(channel: Int) =  1.milliseconds

    override fun time() = timestamp

    override fun name(channel: Int): String {
        return when(channel) {
            0 -> "sin"
            1 -> "cos"
            else -> ""
        }
    }

    override fun shorts(channel: Int): ShortBuffer {
        return when(channel) {
            0 -> data.asReadOnlyBuffer().asShortBuffer()
            1 -> cosData.asReadOnlyBuffer().asShortBuffer()
            else -> throw UnsupportedOperationException()
        }
    }

    override fun hasAnalogData() = true
}