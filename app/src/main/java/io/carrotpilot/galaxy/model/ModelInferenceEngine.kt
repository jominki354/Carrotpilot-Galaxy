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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

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

private data class OnnxInputSpec(
  val name: String,
  val type: OnnxJavaType,
  val shape: LongArray,
  val elementCount: Int,
)

internal abstract class BaseOnnxRuntimeInferenceEngine(
  context: Context,
  override val backendName: String,
) : ModelInferenceEngine {
  private companion object {
    const val TAG = "ModelInferenceEngine"
    const val MAX_INPUT_ELEMENTS = 8_000_000L
  }

  protected val appContext = context.applicationContext

  private var session: OrtSession? = null
  private var environment: OrtEnvironment? = null
  private var inputSpecs: List<OnnxInputSpec> = emptyList()
  private var initialized = false

  protected abstract fun loadModelBytes(): ByteArray?

  override fun initialize(): Boolean {
    if (initialized) return true
    return try {
      val modelBytes = loadModelBytes() ?: return false
      val env = OrtEnvironment.getEnvironment()
      val options = OrtSession.SessionOptions()
      val createdSession = env.createSession(modelBytes, options)

      val specs = buildInputSpecs(createdSession) ?: run {
        createdSession.close()
        return false
      }
      if (specs.isEmpty()) {
        createdSession.close()
        return false
      }

      environment = env
      session = createdSession
      inputSpecs = specs
      initialized = true
      true
    } catch (t: Throwable) {
      Log.w(TAG, "ONNX init failed($backendName): ${t.message}")
      false
    }
  }

  override fun run(frameTimestampMs: Long): ModelInferenceResult {
    val currentSession = session
    val env = environment
    val specs = inputSpecs
    if (!initialized || currentSession == null || env == null || specs.isEmpty()) {
      return ModelInferenceResult(
        success = false,
        failureReason = "onnx_not_initialized",
      )
    }

    val startedAtNs = System.nanoTime()
    var result: OrtSession.Result? = null
    val tensors = linkedMapOf<String, OnnxTensor>()
    return try {
      specs.forEachIndexed { index, spec ->
        tensors[spec.name] = createInputTensor(env, spec, frameTimestampMs, index)
      }
      result = currentSession.run(tensors)
      val latencyMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
      ModelInferenceResult(
        success = true,
        latencyMs = latencyMs,
        outputsProduced = result.size(),
      )
    } catch (e: Throwable) {
      val latencyMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
      val reason = when (e) {
        is OrtException -> e.message ?: "onnx_run_failed"
        else -> e.message ?: "onnx_run_failed"
      }
      ModelInferenceResult(
        success = false,
        latencyMs = latencyMs,
        failureReason = reason,
      )
    } finally {
      runCatching { result?.close() }
      tensors.values.forEach { tensor -> runCatching { tensor.close() } }
    }
  }

  override fun release() {
    runCatching { session?.close() }
    session = null
    environment = null
    inputSpecs = emptyList()
    initialized = false
  }

  private fun buildInputSpecs(session: OrtSession): List<OnnxInputSpec>? {
    val specs = mutableListOf<OnnxInputSpec>()
    for ((name, nodeInfo) in session.inputInfo) {
      val tensorInfo = nodeInfo.info as? TensorInfo ?: return null
      val resolvedShape = tensorInfo.shape.map { dim ->
        if (dim <= 0L) 1L else dim
      }.toLongArray()
      val elementCountLong = resolvedShape.fold(1L) { acc, dim -> acc * dim }
      if (elementCountLong <= 0L || elementCountLong > MAX_INPUT_ELEMENTS) return null

      val type = tensorInfo.type
      if (!isSupportedInputType(type)) return null

      specs += OnnxInputSpec(
        name = name,
        type = type,
        shape = resolvedShape,
        elementCount = elementCountLong.toInt(),
      )
    }
    return specs.sortedBy { it.name }
  }

  private fun isSupportedInputType(type: OnnxJavaType): Boolean {
    return when (type) {
      OnnxJavaType.FLOAT,
      OnnxJavaType.UINT8,
      OnnxJavaType.INT8,
      OnnxJavaType.INT16,
      OnnxJavaType.INT32,
      OnnxJavaType.INT64,
      OnnxJavaType.BOOL,
      -> true

      else -> false
    }
  }

  private fun createInputTensor(
    env: OrtEnvironment,
    spec: OnnxInputSpec,
    frameTimestampMs: Long,
    inputIndex: Int,
  ): OnnxTensor {
    val seed = (((frameTimestampMs + inputIndex * 97L) % 255L) + 1L).toInt()
    return when (spec.type) {
      OnnxJavaType.FLOAT -> {
        val value = seed.toFloat() / 255.0f
        val data = FloatArray(spec.elementCount) { value }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), spec.shape)
      }

      OnnxJavaType.UINT8 -> {
        val data = ByteArray(spec.elementCount) { seed.toByte() }
        val buffer = directByteBuffer(data)
        OnnxTensor.createTensor(env, buffer, spec.shape, OnnxJavaType.UINT8)
      }

      OnnxJavaType.INT8 -> {
        val value = (seed % 127).toByte()
        val data = ByteArray(spec.elementCount) { value }
        val buffer = directByteBuffer(data)
        OnnxTensor.createTensor(env, buffer, spec.shape, OnnxJavaType.INT8)
      }

      OnnxJavaType.BOOL -> {
        val data = ByteArray(spec.elementCount) { index ->
          if ((index + seed) % 2 == 0) 1 else 0
        }
        val buffer = directByteBuffer(data)
        OnnxTensor.createTensor(env, buffer, spec.shape, OnnxJavaType.BOOL)
      }

      OnnxJavaType.INT16 -> {
        val value = (seed % 32767).toShort()
        val data = ShortArray(spec.elementCount) { value }
        OnnxTensor.createTensor(env, ShortBuffer.wrap(data), spec.shape, OnnxJavaType.INT16)
      }

      OnnxJavaType.INT32 -> {
        val value = seed
        val data = IntArray(spec.elementCount) { value }
        OnnxTensor.createTensor(env, IntBuffer.wrap(data), spec.shape)
      }

      OnnxJavaType.INT64 -> {
        val value = seed.toLong()
        val data = LongArray(spec.elementCount) { value }
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), spec.shape)
      }

      else -> {
        throw IllegalStateException("unsupported_input_type=${spec.type}")
      }
    }
  }

  private fun directByteBuffer(bytes: ByteArray): ByteBuffer {
    return ByteBuffer.allocateDirect(bytes.size)
      .order(ByteOrder.nativeOrder())
      .put(bytes)
      .rewind() as ByteBuffer
  }
}

internal class OnnxRuntimeAssetInferenceEngine(
  context: Context,
  private val modelAssetPath: String = "models/mul_1.onnx",
) : BaseOnnxRuntimeInferenceEngine(
  context = context,
  backendName = "ONNX_RUNTIME_ANDROID[$modelAssetPath]",
) {
  override fun loadModelBytes(): ByteArray? {
    return runCatching {
      appContext.assets.open(modelAssetPath).use { it.readBytes() }
    }.getOrNull()
  }
}

internal class OnnxRuntimeExternalFileInferenceEngine(
  context: Context,
  private val relativeExternalFilePath: String = "models/comma_model.onnx",
) : BaseOnnxRuntimeInferenceEngine(
  context = context,
  backendName = "ONNX_RUNTIME_ANDROID[file:$relativeExternalFilePath]",
) {
  override fun loadModelBytes(): ByteArray? {
    return runCatching {
      val root = appContext.getExternalFilesDir(null) ?: return null
      val file = File(root, relativeExternalFilePath)
      if (!file.exists() || !file.isFile || file.length() <= 0L) {
        return null
      }
      file.readBytes()
    }.getOrNull()
  }
}
