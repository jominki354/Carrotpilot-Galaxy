package io.carrotpilot.galaxy.model

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AndroidCameraFrameSource(
  context: Context,
) {
  private val appContext = context.applicationContext
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private var cameraProvider: ProcessCameraProvider? = null
  private var imageAnalysis: ImageAnalysis? = null

  fun start(
    owner: LifecycleOwner,
    onFrame: (Long, ModelInputFrame) -> Unit,
    targetResolution: Pair<Int, Int>? = null,
    onError: (String) -> Unit,
  ) {
    val providerFuture = ProcessCameraProvider.getInstance(appContext)
    providerFuture.addListener(
      {
        val provider = runCatching { providerFuture.get() }.getOrNull()
        if (provider == null) {
          onError("camera provider unavailable")
          return@addListener
        }
        cameraProvider = provider

        runCatching { provider.unbindAll() }

        val analysisBuilder = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        if (targetResolution != null) {
          val (targetWidth, targetHeight) = targetResolution
          if (targetWidth > 0 && targetHeight > 0) {
            analysisBuilder.setTargetResolution(Size(targetWidth, targetHeight))
          }
        }

        val analysis = analysisBuilder
          .build()
          .also { useCase ->
            useCase.setAnalyzer(cameraExecutor) { image ->
              val frame = image.toModelInputFrame()
              if (frame != null) {
                onFrame(SystemClock.elapsedRealtime(), frame)
              }
              image.close()
            }
          }
        imageAnalysis = analysis

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val bindResult = runCatching {
          provider.bindToLifecycle(owner, selector, analysis)
        }
        if (bindResult.isFailure) {
          onError("bindToLifecycle failed: ${bindResult.exceptionOrNull()?.message ?: "unknown"}")
        }
      },
      ContextCompat.getMainExecutor(appContext),
    )
  }

  fun stop() {
    imageAnalysis?.clearAnalyzer()
    imageAnalysis = null
    cameraProvider?.unbindAll()
  }

  private fun ImageProxy.toModelInputFrame(): ModelInputFrame? {
    if (width <= 0 || height <= 0) return null
    val plane = planes.firstOrNull() ?: return null
    val source = plane.buffer ?: return null
    val sourceLimit = source.limit()
    if (sourceLimit <= 0) return null
    val rowStride = plane.rowStride.coerceAtLeast(width)
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    val pixels = ByteArray(width * height)
    val baseOffset = source.position()

    val duplicate = source.duplicate()
    if (pixelStride == 1 && rowStride == width && baseOffset + pixels.size <= sourceLimit) {
      duplicate.position(baseOffset)
      duplicate.get(pixels, 0, pixels.size)
    } else if (pixelStride == 1) {
      for (y in 0 until height) {
        val sourceIndex = baseOffset + y * rowStride
        if (sourceIndex in 0 until sourceLimit) {
          val readable = minOf(width, sourceLimit - sourceIndex)
          duplicate.position(sourceIndex)
          duplicate.get(pixels, y * width, readable)
        }
      }
    } else {
      for (y in 0 until height) {
        for (x in 0 until width) {
          val sourceIndex = baseOffset + y * rowStride + x * pixelStride
          val value = if (sourceIndex in 0 until sourceLimit) {
            source.get(sourceIndex)
          } else {
            0
          }
          pixels[y * width + x] = value
        }
      }
    }

    return ModelInputFrame(
      lumaBytes = pixels,
      width = width,
      height = height,
    )
  }
}
