package io.carrotpilot.galaxy.model

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

data class ModelInferenceResult(
  val success: Boolean,
  val latencyMs: Double = 0.0,
  val outputsProduced: Int = 0,
  val failureReason: String? = null,
)

interface ModelInferenceEngine {
  val backendName: String
  fun initialize(): Boolean
  fun run(frameTimestampMs: Long): ModelInferenceResult
  fun release() {}
}

class MockModelInferenceEngine : ModelInferenceEngine {
  override val backendName: String = "MOCK"
  override fun initialize(): Boolean = true
  override fun run(frameTimestampMs: Long): ModelInferenceResult {
    return ModelInferenceResult(
      success = true,
      latencyMs = 0.2,
      outputsProduced = 1,
    )
  }
}

class OnnxPlaceholderInferenceEngine : ModelInferenceEngine {
  override val backendName: String = "ONNX_PLACEHOLDER"
  override fun initialize(): Boolean = true
  override fun run(frameTimestampMs: Long): ModelInferenceResult {
    return ModelInferenceResult(
      success = true,
      latencyMs = 0.4,
      outputsProduced = 1,
    )
  }
}

class FallbackInferenceEngine(
  private val primary: ModelInferenceEngine,
  private val fallback: ModelInferenceEngine,
) : ModelInferenceEngine {
  private var activeEngine: ModelInferenceEngine = primary
  private var fallbackActivated = false

  override val backendName: String
    get() = activeEngine.backendName

  override fun initialize(): Boolean {
    fallbackActivated = false
    activeEngine = if (primary.initialize()) {
      primary
    } else {
      val fallbackReady = fallback.initialize()
      if (!fallbackReady) {
        activeEngine = fallback
        return false
      }
      fallbackActivated = true
      fallback
    }
    return true
  }

  override fun run(frameTimestampMs: Long): ModelInferenceResult {
    val result = activeEngine.run(frameTimestampMs)
    if (result.success || activeEngine === fallback) {
      return result
    }
    if (!fallbackActivated && fallback.initialize()) {
      fallbackActivated = true
      activeEngine = fallback
      return fallback.run(frameTimestampMs)
    }
    return result
  }

  override fun release() {
    primary.release()
    fallback.release()
  }
}

class OnnxRuntimeAssetInferenceEngine(
  context: Context,
  private val modelAssetPath: String = "models/mul_1.onnx",
) : ModelInferenceEngine {
  private val appContext = context.applicationContext
  private var session: OrtSession? = null
  private var environment: OrtEnvironment? = null
  private var inputName: String? = null
  private var inputShape: LongArray = longArrayOf(1L)
  private var initialized = false

  override val backendName: String = "ONNX_RUNTIME_ANDROID[$modelAssetPath]"

  override fun initialize(): Boolean {
    if (initialized) return true
    return try {
      val modelBytes = appContext.assets.open(modelAssetPath).use { it.readBytes() }
      val env = OrtEnvironment.getEnvironment()
      val options = OrtSession.SessionOptions()
      val createdSession = env.createSession(modelBytes, options)
      val firstInput = createdSession.inputInfo.entries.firstOrNull()
      val tensorInfo = firstInput?.value?.info as? TensorInfo
      if (firstInput == null || tensorInfo == null) {
        createdSession.close()
        false
      } else if (tensorInfo.type != OnnxJavaType.FLOAT) {
        createdSession.close()
        false
      } else {
        val resolvedShape = tensorInfo.shape.map { dim ->
          if (dim <= 0L) 1L else dim
        }.toLongArray()
        val elementCount = resolvedShape.fold(1L) { acc, dim -> acc * dim }
        if (elementCount <= 0L || elementCount > 1_000_000L) {
          createdSession.close()
          false
        } else {
          environment = env
          session = createdSession
          inputName = firstInput.key
          inputShape = resolvedShape
          initialized = true
          true
        }
      }
    } catch (t: Throwable) {
      Log.w("ModelInferenceEngine", "ONNX init failed: ${t.message}")
      false
    }
  }

  override fun run(frameTimestampMs: Long): ModelInferenceResult {
    val currentSession = session
    val env = environment
    val currentInputName = inputName
    if (!initialized || currentSession == null || env == null || currentInputName == null) {
      return ModelInferenceResult(
        success = false,
        failureReason = "onnx_not_initialized",
      )
    }

    val inputData = buildInputTensor(frameTimestampMs)
    val startedAtNs = System.nanoTime()
    var tensor: OnnxTensor? = null
    var result: OrtSession.Result? = null
    return try {
      tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), inputShape)
      result = currentSession.run(mapOf(currentInputName to tensor))
      val latencyMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
      ModelInferenceResult(
        success = true,
        latencyMs = latencyMs,
        outputsProduced = result.size(),
      )
    } catch (e: OrtException) {
      val latencyMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
      ModelInferenceResult(
        success = false,
        latencyMs = latencyMs,
        failureReason = e.message ?: "onnx_run_failed",
      )
    } finally {
      runCatching { result?.close() }
      runCatching { tensor?.close() }
    }
  }

  override fun release() {
    runCatching { session?.close() }
    session = null
    environment = null
    inputName = null
    inputShape = longArrayOf(1L)
    initialized = false
  }

  private fun buildInputTensor(frameTimestampMs: Long): FloatArray {
    val total = inputShape.fold(1L) { acc, dim -> acc * dim }.toInt().coerceAtLeast(1)
    val value = ((frameTimestampMs % 10_000L).toFloat() / 10_000.0f)
    return FloatArray(total) { value }
  }
}

class OnnxRuntimeExternalFileInferenceEngine(
  context: Context,
  private val relativeExternalFilePath: String = "models/comma_model.onnx",
) : ModelInferenceEngine {
  private val appContext = context.applicationContext
  private var session: OrtSession? = null
  private var environment: OrtEnvironment? = null
  private var inputName: String? = null
  private var inputShape: LongArray = longArrayOf(1L)
  private var initialized = false

  override val backendName: String = "ONNX_RUNTIME_ANDROID[file:$relativeExternalFilePath]"

  override fun initialize(): Boolean {
    if (initialized) return true
    return try {
      val modelRoot = appContext.getExternalFilesDir(null) ?: return false
      val modelFile = File(modelRoot, relativeExternalFilePath)
      if (!modelFile.exists() || !modelFile.isFile || modelFile.length() <= 0L) {
        return false
      }

      val modelBytes = modelFile.readBytes()
      val env = OrtEnvironment.getEnvironment()
      val options = OrtSession.SessionOptions()
      val createdSession = env.createSession(modelBytes, options)
      val firstInput = createdSession.inputInfo.entries.firstOrNull()
      val tensorInfo = firstInput?.value?.info as? TensorInfo
      if (firstInput == null || tensorInfo == null) {
        createdSession.close()
        false
      } else if (tensorInfo.type != OnnxJavaType.FLOAT) {
        createdSession.close()
        false
      } else {
        val resolvedShape = tensorInfo.shape.map { dim ->
          if (dim <= 0L) 1L else dim
        }.toLongArray()
        val elementCount = resolvedShape.fold(1L) { acc, dim -> acc * dim }
        if (elementCount <= 0L || elementCount > 1_000_000L) {
          createdSession.close()
          false
        } else {
          environment = env
          session = createdSession
          inputName = firstInput.key
          inputShape = resolvedShape
          initialized = true
          true
        }
      }
    } catch (t: Throwable) {
      Log.w("ModelInferenceEngine", "ONNX external init failed: ${t.message}")
      false
    }
  }

  override fun run(frameTimestampMs: Long): ModelInferenceResult {
    val currentSession = session
    val env = environment
    val currentInputName = inputName
    if (!initialized || currentSession == null || env == null || currentInputName == null) {
      return ModelInferenceResult(
        success = false,
        failureReason = "onnx_external_not_initialized",
      )
    }

    val inputData = buildInputTensor(frameTimestampMs)
    val startedAtNs = System.nanoTime()
    var tensor: OnnxTensor? = null
    var result: OrtSession.Result? = null
    return try {
      tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), inputShape)
      result = currentSession.run(mapOf(currentInputName to tensor))
      val latencyMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
      ModelInferenceResult(
        success = true,
        latencyMs = latencyMs,
        outputsProduced = result.size(),
      )
    } catch (e: OrtException) {
      val latencyMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
      ModelInferenceResult(
        success = false,
        latencyMs = latencyMs,
        failureReason = e.message ?: "onnx_external_run_failed",
      )
    } finally {
      runCatching { result?.close() }
      runCatching { tensor?.close() }
    }
  }

  override fun release() {
    runCatching { session?.close() }
    session = null
    environment = null
    inputName = null
    inputShape = longArrayOf(1L)
    initialized = false
  }

  private fun buildInputTensor(frameTimestampMs: Long): FloatArray {
    val total = inputShape.fold(1L) { acc, dim -> acc * dim }.toInt().coerceAtLeast(1)
    val value = ((frameTimestampMs % 10_000L).toFloat() / 10_000.0f)
    return FloatArray(total) { value }
  }
}
