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
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class BandPassNode<T>(val minFrequency: Double, val maxFrequency: Double, val source: T)
    : Node, TimeSeriesNode where T: Node, T: TimeSeriesNode {
    override var ready = Signal1<Node>()
    override var channelsChanged = Signal1<TimeSeriesNode>()
    private var outputData = ByteBuffer.allocate(1)
    var running = false
    private var lastTime = 0.seconds
    private var currentTime = 0.seconds
    private var timestamp = 0.seconds
    private val _numChannels = 16
    private var numSamples = 0
    private var channelConnection: Signal1<TimeSeriesNode>.Connection? = null
    private var connection: Signal1<Node>.Connection? = null
    private var response: DoubleArray? = null
    private val channelHistory: MutableMap<Int, MutableList<DoubleBuffer>> = mutableMapOf()
    val fft = FastFourierTransformer(DftNormalization.STANDARD)
    val outputBuffers = mutableListOf<DoubleBuffer>()
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
            if(response == null) {
                val sampleInterval = source.sampleInterval(0)
                val rawSampleFrequency = 1e9/sampleInterval.inWholeNanoseconds
                val sampleFrequency = pow(2, ceil(log2(rawSampleFrequency)).roundToInt())
                val nyquistFrequency = sampleFrequency/2
                assert(maxFrequency < nyquistFrequency)

                val frequencyDomain = DoubleArray(sampleFrequency.toInt())
                val bottom = (frequencyDomain.size/2*minFrequency/nyquistFrequency).toInt()
                val top = (frequencyDomain.size/2*maxFrequency/nyquistFrequency).toInt()
                for(i in bottom..top) {
                    frequencyDomain[i] = 1.0
                    frequencyDomain[frequencyDomain.size-1-i] = 1.0
                }

                val timeDomain = fft.transform(frequencyDomain, TransformType.INVERSE)
                val mid = timeDomain.size/2
                val end = timeDomain.size
                val newResponse = DoubleArray(timeDomain.size)
                for(i in 0..<mid) {
                    newResponse[i] = timeDomain[mid+i].real
                }
                for(i in 0..<mid) {
                    newResponse[mid+i] = timeDomain[i].real
                }
                //newResponse[512] = 0.451171//875
                response = newResponse
                //response = DoubleArray(newResponse.size)
                //for (i in 0..<response!!.size) {
                //    response!![i] = 1.0
               // }
            }

            val currentResponse = response!!
            outputBuffers.clear()

            for(i in 0..<source.numChannels()) {
                if(!channelHistory.containsKey(i)) {
                    channelHistory[i] = mutableListOf<DoubleBuffer>()
                }

                val currentChannelHistory = channelHistory[i]!!

                val sourceSamples = if(source.dataType() == TimeSeriesNode.DataType.DOUBLE){
                    source.doubles(i)
                } else {
                    val samples = source.shorts(i)
                    val copy = DoubleBuffer.allocate(samples.remaining())
                    while(samples.hasRemaining()) {
                        copy.put(samples.get().toDouble())
                    }
                    copy.clear()
                    copy
                }
                sourceSamples.mark()

                val nextOutputBuffer = DoubleBuffer.allocate(sourceSamples.remaining())

                val initialPosition = sourceSamples.position()
                while(sourceSamples.hasRemaining()) {
                    //Convolute over current buffer
                    //val trace = mutableListOf<Double>()
                    var k = 0
                    val first = sourceSamples.get()
                    //if(i == 0) {
                    //    outputStream.println(first)
                    //}
                    var sum = first*currentResponse[k++]
                    //trace.add(sum)
                    var j = sourceSamples.position()-2
                    while(initialPosition <= j && k < currentResponse.size) {
                        val temp = sourceSamples.get(j--)
                        sum += temp*currentResponse[k++]
                        //trace.add(sum)
                    }
                    //Convolute over past buffers
                    val iter = currentChannelHistory.listIterator(currentChannelHistory.size)
                    while(iter.hasPrevious() && k < currentResponse.size) {
                        val buffer = iter.previous()
                        j = buffer.limit()-1
                        while(buffer.position() <= j && k < currentResponse.size) {
                            val temp = buffer.get(j--)
                            sum += temp*currentResponse[k++]
                            //trace.add(sum)
                        }
                    }
                    //Drop past buffers outside the convolution window
                    while(iter.hasPrevious()) {
                        iter.previous()
                        iter.remove()
                    }
                    //val traceStr = "[" + trace.joinToString(",") { it.toString() } + "]"
                    nextOutputBuffer.put(sum)
                    //trace.add(sum)
                }
                nextOutputBuffer.clear()
                outputBuffers.add(nextOutputBuffer)
                sourceSamples.reset()
                currentChannelHistory.add(sourceSamples)
            }
            ready(this)
        }
    }

    override fun dataType() = TimeSeriesNode.DataType.DOUBLE

    override fun numChannels() = outputBuffers.size

    override fun sampleInterval(channel: Int) =  this.source.sampleInterval(channel)

    override fun time() = this.source.time()

    override fun name(channel: Int) = this.source.name(channel)

    override fun doubles(channel: Int): DoubleBuffer {
        return outputBuffers[channel].asReadOnlyBuffer()
    }

    override fun hasAnalogData() = true
}