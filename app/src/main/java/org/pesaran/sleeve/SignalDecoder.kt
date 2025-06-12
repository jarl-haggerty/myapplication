package org.pesaran.sleeve

import java.nio.ByteBuffer
import java.nio.ByteOrder

class SignalDecoder(val numChannels: Int) {
    var sampleIndex = 0

    fun decode(block: Block) {
        val data = block.data
        data.order(ByteOrder.BIG_ENDIAN)

        var numSamples = data.remaining() / 2
        if(data.remaining() % 2 != 0) {
            ++numSamples
        }
        val remainder = numSamples % numChannels
        if(remainder != 0) {
            numSamples += numChannels - remainder
        }

        var i = 0
        val samples = ByteBuffer.allocate(2*numSamples)
        while(data.remaining() > 0) {
            if(data.remaining() > 1) {
                samples.putShort(data.getShort())
            } else {
                samples.put(data.get())
            }
        }
    }
}