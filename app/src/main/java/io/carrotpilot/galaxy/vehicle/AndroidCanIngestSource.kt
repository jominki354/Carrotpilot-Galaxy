package io.carrotpilot.galaxy.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidCanIngestSource(
  private val usbHostManager: UsbHostManager,
) : CanIngestSource {
  private val scope = CoroutineScope(Dispatchers.IO)
  private var readJob: Job? = null

  private val _frames = MutableSharedFlow<CanFrame>(extraBufferCapacity = 1024)
  override val frames: Flow<CanFrame> = _frames.asSharedFlow()

  override suspend fun start() {
    if (readJob != null) return
    readJob = scope.launch {
      val buffer = ByteArray(16 * 1024)
      var overflow = byteArrayOf()
      while (isActive) {
        val session = usbHostManager.sessionOrNull()
        if (session == null) {
          delay(100)
          continue
        }

        val n = session.connection.bulkTransfer(session.bulkIn, buffer, buffer.size, 100)
        if (n <= 0) continue

        val chunk = buffer.copyOfRange(0, n)
        val decoded = PandaCanPacketCodec.decode(chunk, overflow)
        overflow = decoded.overflow
        decoded.frames.forEach { _frames.tryEmit(it) }
      }
    }
  }

  override suspend fun stop() {
    readJob?.cancel()
    readJob = null
  }
}

