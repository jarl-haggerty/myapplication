package org.pesaran.sleeve

import android.content.Context
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import org.apache.commons.math3.util.ArithmeticUtils.pow
import java.io.BufferedOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import kotlin.experimental.and
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class RectifierNode<T>(val source: T)
    : Node, TimeSeriesNode where T: Node, T: TimeSeriesNode {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var channelConnection: Signal1<TimeSeriesNode>.Connection? = null
    private var connection: Signal1<Node>.Connection? = null
    private var response: DoubleArray? = null
    private val channelHistory: MutableMap<Int, MutableList<DoubleBuffer>> = mutableMapOf()
    var buffer = DoubleBuffer.allocate(1)
    var sampleCounts = listOf<Int>()
    init {
        //val filesDir = context!!.getExternalFilesDir(null)
        //val dataFile = File(filesDir, "data.txt")
        //val outputStream = PrintWriter(dataFile.outputStream())
        //this.source = source
        connection?.disconnect()
        response = null
        channelHistory.clear()
        channelsChanged(this)
        channelConnection = source.channelsChanged.connect {
            response = null
            channelHistory.clear()
            channelsChanged(this)
        }
        connection = source.ready.connect {
            if(!source.hasAnalogData()) {
                return@connect
            }

            sampleCounts = (0..<source.numChannels()).map {
                if(source.dataType() == TimeSeriesNode.DataType.DOUBLE){
                    source.doubles(it).remaining()
                } else {
                    source.shorts(it).remaining()
                }
            }
            val totalSamples = sampleCounts.sum()

            buffer.clear()
            if(buffer.remaining() < totalSamples) {
                buffer = DoubleBuffer.allocate(totalSamples)
            }

            for(i in 0..<source.numChannels()) {
                if(source.dataType() == TimeSeriesNode.DataType.DOUBLE) {
                    val inputBuffer = source.doubles(i)
                    while(inputBuffer.hasRemaining()) {
                        buffer.put(abs(inputBuffer.get()))
                    }
                } else {
                    val inputBuffer = source.shorts(i)
                    while(inputBuffer.hasRemaining()) {
                        buffer.put(abs(inputBuffer.get().toDouble()))
                    }
                }
            }
            buffer.flip()
            ready(this)
        }
    }

    override fun dataType() = TimeSeriesNode.DataType.DOUBLE

    override fun numChannels() = sampleCounts.size

    override fun sampleInterval(channel: Int) =  this.source.sampleInterval(channel)

    override fun time() = this.source.time()

    override fun name(channel: Int) = this.source.name(channel)

    override fun doubles(channel: Int): DoubleBuffer {
        val position = sampleCounts.subList(0, channel).sum()
        val limit = position + sampleCounts[channel]

        val result = buffer.asReadOnlyBuffer()
        result.position(position)
        result.limit(limit)
        return result
    }

    override fun hasAnalogData() = true
}