package org.pesaran.myapplication

import java.nio.ByteBuffer
import java.nio.ByteOrder
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

class Block(val blockType: BlockType, val blockId: CommandBlockId, val data: ByteArray, val firstPointIdx: Short = -1) {
    fun encode(): ByteArray {
        val prefixSize = if (blockType == BlockType.DATA_BLOCK) {
            4
        } else {
            2
        }
        val totalSize = prefixSize + data.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(totalSize.toByte())
        if(blockType == BlockType.DATA_BLOCK) {
            buffer.put(blockId.value)
            buffer.putShort(firstPointIdx)
        } else {
            buffer.put(blockId.value or 0x80.toByte())
        }
        buffer.put(data)
        return buffer.array()
    }
}