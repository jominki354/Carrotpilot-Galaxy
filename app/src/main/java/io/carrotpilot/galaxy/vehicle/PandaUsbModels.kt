package io.carrotpilot.galaxy.vehicle

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

data class PandaUsbSession(
  val device: UsbDevice,
  val connection: UsbDeviceConnection,
  val usbInterface: UsbInterface,
  val bulkIn: UsbEndpoint,
  val bulkOut: UsbEndpoint?,
)

