package org.pesaran.myapplication

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.or

enum class BlockType {
    DATA_BLOCK,
    CMD_BLOCK
}

class Block(val blockType: BlockType, val blockId: Byte, val data: ByteArray, val firstPointIdx: Short = -1) {
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
            buffer.put(blockId)
            buffer.putShort(firstPointIdx)
        } else {
            buffer.put(blockId or 0x80.toByte())
        }
        buffer.put(data)
        return buffer.array()
    }
}