package io.carrotpilot.galaxy.vehicle

data class CanFrame(
  val timestampNs: Long,
  val bus: Int,
  val address: Int,
  val data: ByteArray,
  val isCanFd: Boolean = false,
)

data class CanSignature(
  val address: Int,
  val length: Int,
)

