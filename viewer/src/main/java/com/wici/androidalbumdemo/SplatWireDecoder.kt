package com.wici.androidalbumdemo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure decoder for the canonical 32-byte .splat wire row. */
internal object SplatWireDecoder {
    const val ROW_BYTES = 32

    data class Row(
        val x: Float, val y: Float, val z: Float,
        val sx: Float, val sy: Float, val sz: Float,
        val r: Int, val g: Int, val b: Int, val a: Int,
        val qw: Float, val qx: Float, val qy: Float, val qz: Float,
    ) {
        val numericValid: Boolean
            get() = x.isFinite() && y.isFinite() && z.isFinite() &&
                sx.isFinite() && sy.isFinite() && sz.isFinite()
    }

    fun decodeRow(bytes: ByteArray, offset: Int = 0): Row {
        require(offset >= 0 && offset + ROW_BYTES <= bytes.size) { "incomplete splat row" }
        val buffer = ByteBuffer.wrap(bytes, offset, ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        return Row(
            buffer.float, buffer.float, buffer.float,
            buffer.float, buffer.float, buffer.float,
            buffer.u8(), buffer.u8(), buffer.u8(), buffer.u8(),
            buffer.quaternionComponent(), buffer.quaternionComponent(),
            buffer.quaternionComponent(), buffer.quaternionComponent(),
        )
    }

    fun decodeRows(bytes: ByteArray): List<Row> {
        require(bytes.size % ROW_BYTES == 0) { "partial trailing splat bytes: ${bytes.size % ROW_BYTES}" }
        return (bytes.indices step ROW_BYTES).map { decodeRow(bytes, it) }
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xff
    private fun ByteBuffer.quaternionComponent(): Float = (u8() - 128) / 128f
}
