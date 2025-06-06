package org.pesaran.myapplication

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.and
import kotlin.experimental.or

enum class BlockType {
    DATA_BLOCK,
    CMD_BLOCK
}
enum class CommandBlockId(val value: Byte) {
    ID_ENABLE(0),
    ID_SET_SAMPLE_RATE(1),
    ID_SET_CHANNEL_MASK(2),
    ID_STIMULATE(3),
    ID_ECHO(4),
    ID_CUSTOM_CONFIG(5)
}

class Block(val blockType: BlockType, val blockId: CommandBlockId, val data: ByteBuffer, val firstPointIdx: Short = -1) {
    companion object {
        fun decode(packet: ByteBuffer): Block {
            packet.order(ByteOrder.BIG_ENDIAN)
            val totalLenByte = packet.get().toInt()
            val totalLen = if (totalLenByte < 0) 256 - totalLenByte else totalLenByte

            val actualLen = Math.min(totalLen-1, packet.remaining())
            val slice = packet.slice(packet.position(), actualLen)
            packet.position(packet.position() + actualLen)

            val idByte = slice.get()
            val isCommandBlock = (idByte and 0b10000000.toByte()).toInt() != 0
            val blockId = when(val blockCode = (idByte and 0b01111111).toInt()) {
                0 -> CommandBlockId.ID_ENABLE
                1 -> CommandBlockId.ID_SET_SAMPLE_RATE
                2 -> CommandBlockId.ID_SET_CHANNEL_MASK
                3 -> CommandBlockId.ID_STIMULATE
                4 -> CommandBlockId.ID_ECHO
                5 -> CommandBlockId.ID_CUSTOM_CONFIG
                else -> throw IllegalArgumentException("Unexpected blockId: $blockCode")
            }

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
            buffer.put(blockId.value)
            buffer.putShort(firstPointIdx)
        } else {
            buffer.put(blockId.value or 0x80.toByte())
        }

        data.mark()
        buffer.put(data)
        data.reset()
        val result = buffer.array()
        return result
    }
}