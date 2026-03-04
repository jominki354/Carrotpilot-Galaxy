package io.carrotpilot.galaxy.vehicle

object PandaCanPacketCodec {
  private const val HEADER_SIZE = 6
  private val DLC_TO_LEN = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16, 20, 24, 32, 48, 64)

  data class DecodeResult(
    val frames: List<CanFrame>,
    val overflow: ByteArray,
  )

  fun decode(chunk: ByteArray, previousOverflow: ByteArray = byteArrayOf()): DecodeResult {
    val dat = ByteArray(previousOverflow.size + chunk.size)
    System.arraycopy(previousOverflow, 0, dat, 0, previousOverflow.size)
    System.arraycopy(chunk, 0, dat, previousOverflow.size, chunk.size)

    var idx = 0
    val out = mutableListOf<CanFrame>()
    while (dat.size - idx >= HEADER_SIZE) {
      val h0 = dat[idx].toInt() and 0xFF
      val dlc = (h0 shr 4) and 0x0F
      val len = DLC_TO_LEN[dlc]
      if (dat.size - idx < HEADER_SIZE + len) break

      val h1 = dat[idx + 1].toInt() and 0xFF
      val h2 = dat[idx + 2].toInt() and 0xFF
      val h3 = dat[idx + 3].toInt() and 0xFF
      val h4 = dat[idx + 4].toInt() and 0xFF
      val busRaw = (h0 shr 1) and 0x7
      val addressWord = (h4 shl 24) or (h3 shl 16) or (h2 shl 8) or h1
      val address = addressWord ushr 3

      // checksum XOR of header+payload should be 0
      var checksum = 0
      for (i in 0 until HEADER_SIZE + len) {
        checksum = checksum xor (dat[idx + i].toInt() and 0xFF)
      }
      if (checksum == 0) {
        val payload = dat.copyOfRange(idx + HEADER_SIZE, idx + HEADER_SIZE + len)
        out += CanFrame(
          timestampNs = System.nanoTime(),
          bus = busRaw,
          address = address,
          data = payload,
          isCanFd = (h0 and 0x1) == 1,
        )
      }

      idx += HEADER_SIZE + len
    }

    val overflow = if (idx < dat.size) dat.copyOfRange(idx, dat.size) else byteArrayOf()
    return DecodeResult(out, overflow)
  }
}

