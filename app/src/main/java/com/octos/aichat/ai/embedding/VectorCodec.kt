package com.octos.aichat.ai.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 512×float32 little-endian ⇄ FloatArray, for storing vectors in the memory BLOB column. */
object VectorCodec {
    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(v)
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}
