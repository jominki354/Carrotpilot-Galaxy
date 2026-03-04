package io.carrotpilot.galaxy.model

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
    onFrame: (Long) -> Unit,
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

        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { useCase ->
            useCase.setAnalyzer(cameraExecutor) { image ->
              onFrame(SystemClock.elapsedRealtime())
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
}
