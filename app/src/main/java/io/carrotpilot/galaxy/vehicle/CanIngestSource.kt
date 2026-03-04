package io.carrotpilot.galaxy.vehicle

import kotlinx.coroutines.flow.Flow

interface CanIngestSource {
  val frames: Flow<CanFrame>
  suspend fun start()
  suspend fun stop()
}

