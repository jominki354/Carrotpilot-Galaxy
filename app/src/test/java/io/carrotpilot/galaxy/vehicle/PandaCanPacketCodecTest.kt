package io.carrotpilot.galaxy.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PandaCanPacketCodecTest {
  @Test
  fun decodesSingleClassicCanFrame() {
    val address = 0x123
    val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    val encoded = encodePacket(address = address, bus = 0, fd = false, data = payload)

    val decoded = PandaCanPacketCodec.decode(encoded)
    assertEquals(1, decoded.frames.size)
    assertTrue(decoded.overflow.isEmpty())
    assertEquals(address, decoded.frames[0].address)
    assertEquals(8, decoded.frames[0].data.size)
  }

  private fun encodePacket(address: Int, bus: Int, fd: Boolean, data: ByteArray): ByteArray {
    val lenToDlc = mapOf(
      0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 6, 7 to 7, 8 to 8,
      12 to 9, 16 to 10, 20 to 11, 24 to 12, 32 to 13, 48 to 14, 64 to 15,
    )
    val dlc = lenToDlc.getValue(data.size)
    val header = ByteArray(6)
    val word = address shl 3
    header[0] = (((dlc shl 4) or (bus shl 1) or if (fd) 1 else 0) and 0xFF).toByte()
    header[1] = (word and 0xFF).toByte()
    header[2] = ((word shr 8) and 0xFF).toByte()
    header[3] = ((word shr 16) and 0xFF).toByte()
    header[4] = ((word shr 24) and 0xFF).toByte()

    var checksum = 0
    for (i in 0 until 5) checksum = checksum xor (header[i].toInt() and 0xFF)
    for (b in data) checksum = checksum xor (b.toInt() and 0xFF)
    header[5] = checksum.toByte()

    return header + data
  }
}

