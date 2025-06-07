package org.pesaran.myapplication

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.and
import kotlin.experimental.or

enum class BlockType {
    DATA_BLOCK,
    CMD_BLOCK
}
val ID_ENABLE: Byte = 0
val ID_SET_SAMPLE_RATE: Byte = 1
val ID_SET_CHANNEL_MASK: Byte = 2
val ID_STIMULATE: Byte = 3
val ID_ECHO: Byte = 4
val ID_CUSTOM_CONFIG: Byte = 5

class Block(val blockType: BlockType, val blockId: Byte, val data: ByteBuffer, val firstPointIdx: Short = -1) {
    companion object {
        fun decode(packet: ByteBuffer): Block {
            packet.order(ByteOrder.BIG_ENDIAN)
            val totalLenByte = packet.get().toInt()
            val totalLen = if (totalLenByte < 0) 256 + totalLenByte else totalLenByte

            val actualLen = Math.min(totalLen-1, packet.remaining())
            val slice = packet.slice(packet.position(), actualLen)
            packet.position(packet.position() + actualLen)

            val idByte = slice.get()
            val isCommandBlock = (idByte and 0b10000000.toByte()) != 0.toByte()
            val blockId = idByte and 0b01111111

            if (isCommandBlock) {
                return Block(BlockType.CMD_BLOCK, blockId, slice)
            } else {
                val firstPointIdx = slice.getShort()
                return Block(BlockType.DATA_BLOCK, blockId, slice, firstPointIdx)
            }
        }
        fun decodeBlockPacket(packet: ByteBuffer) = iterator {
            while(packet.remaining() > 0) {
                yield(decode(packet))
            }
        }
    }

    fun encode(): ByteArray {
        val prefixSize = if (blockType == BlockType.DATA_BLOCK) {
            4
        } else {
            2
        }
        val totalSize = prefixSize + data.remaining()
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(totalSize.toByte())
        if(blockType == BlockType.DATA_BLOCK) {
            buffer.put(blockId)
            buffer.putShort(firstPointIdx)
        } else {
            buffer.put(blockId or 0x80.toByte())
        }

        data.mark()
        buffer.put(data)
        data.reset()
        val result = buffer.array()
        return result
    }
}