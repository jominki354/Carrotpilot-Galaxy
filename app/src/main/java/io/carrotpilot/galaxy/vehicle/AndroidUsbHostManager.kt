package io.carrotpilot.galaxy.vehicle

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume

class AndroidUsbHostManager(
  private val context: Context,
  private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager,
) : UsbHostManager {
  companion object {
    private const val ACTION_USB_PERMISSION = "io.carrotpilot.galaxy.USB_PERMISSION"
    private val PANDA_VIDS = setOf(0xBBAA, 0x3801)
    private val PANDA_PIDS = setOf(0xDDEE, 0xDDCC, 0xDDEF, 0xDDCF)
  }

  private val _state = MutableStateFlow(UsbConnectionState.DISCONNECTED)
  override val state: StateFlow<UsbConnectionState> = _state.asStateFlow()

  private var selectedDevice: UsbDevice? = null
  private var session: PandaUsbSession? = null

  override fun sessionOrNull(): PandaUsbSession? = session

  override suspend fun ensurePermission(): Boolean {
    val device = findPandaDevice() ?: run {
      _state.value = UsbConnectionState.DISCONNECTED
      selectedDevice = null
      return false
    }
    selectedDevice = device

    if (usbManager.hasPermission(device)) {
      _state.value = UsbConnectionState.PERMISSION_GRANTED
      return true
    }

    _state.value = UsbConnectionState.PERMISSION_REQUIRED
    val granted = requestPermission(device)
    _state.value = if (granted) UsbConnectionState.PERMISSION_GRANTED else UsbConnectionState.PERMISSION_REQUIRED
    return granted
  }

  override suspend fun connect(): Boolean {
    val device = selectedDevice ?: findPandaDevice() ?: run {
      _state.value = UsbConnectionState.ERROR
      return false
    }
    if (!usbManager.hasPermission(device)) {
      _state.value = UsbConnectionState.PERMISSION_REQUIRED
      return false
    }

    val connection = usbManager.openDevice(device) ?: run {
      _state.value = UsbConnectionState.ERROR
      return false
    }

    val iface = findBestInterface(device) ?: run {
      connection.close()
      _state.value = UsbConnectionState.ERROR
      return false
    }

    if (!connection.claimInterface(iface, true)) {
      connection.close()
      _state.value = UsbConnectionState.ERROR
      return false
    }

    val (bulkIn, bulkOut) = findBulkEndpoints(iface)
    if (bulkIn == null) {
      connection.releaseInterface(iface)
      connection.close()
      _state.value = UsbConnectionState.ERROR
      return false
    }

    session = PandaUsbSession(
      device = device,
      connection = connection,
      usbInterface = iface,
      bulkIn = bulkIn,
      bulkOut = bulkOut,
    )
    _state.value = UsbConnectionState.CONNECTED
    return true
  }

  override suspend fun disconnect() {
    val s = session
    session = null
    if (s != null) {
      runCatching { s.connection.releaseInterface(s.usbInterface) }
      runCatching { s.connection.close() }
    }
    _state.value = UsbConnectionState.DISCONNECTED
  }

  private fun findPandaDevice(): UsbDevice? {
    return usbManager.deviceList.values.firstOrNull { device ->
      device.vendorId in PANDA_VIDS && device.productId in PANDA_PIDS
    }
  }

  private fun findBestInterface(device: UsbDevice) = (0 until device.interfaceCount)
    .map { device.getInterface(it) }
    .firstOrNull { iface ->
      (0 until iface.endpointCount).any { epIdx ->
        val ep = iface.getEndpoint(epIdx)
        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
      }
    }

  private fun findBulkEndpoints(iface: android.hardware.usb.UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
    var inEp: UsbEndpoint? = null
    var outEp: UsbEndpoint? = null

    for (i in 0 until iface.endpointCount) {
      val ep = iface.getEndpoint(i)
      if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
      if (ep.direction == UsbConstants.USB_DIR_IN) {
        if (inEp == null || ep.address == 1 || ep.address == 0x81) inEp = ep
      } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
        if (outEp == null || ep.address == 3 || ep.address == 0x03) outEp = ep
      }
    }
    return inEp to outEp
  }

  private suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
    val intent = PendingIntent.getBroadcast(
      context,
      0,
      Intent(ACTION_USB_PERMISSION),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val filter = IntentFilter(ACTION_USB_PERMISSION)

    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        if (intent?.action != ACTION_USB_PERMISSION) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        runCatching { context.unregisterReceiver(this) }
        if (cont.isActive) cont.resume(granted)
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("DEPRECATION")
      context.registerReceiver(receiver, filter)
    }

    usbManager.requestPermission(device, intent)

    cont.invokeOnCancellation {
      runCatching { context.unregisterReceiver(receiver) }
    }
  }
}

