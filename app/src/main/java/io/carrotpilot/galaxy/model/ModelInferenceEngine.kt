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
import kotlin.math.sqrt

data class ModelInferenceResult(
  val success: Boolean,
  val latencyMs: Double = 0.0,
  val outputsProduced: Int = 0,
  val failureReason: String? = null,
)

interface ModelInferenceEngine {
  val backendName: String
  fun initialize(): Boolean
  fun run(frameTimestampMs: Long, inputFrame: ModelInputFrame? = null): ModelInferenceResult
  fun preferredInputResolution(): Pair<Int, Int>? = null
  fun release() {}
}

class MockModelInferenceEngine : ModelInferenceEngine {
  override val backendName: String = "MOCK"
  override fun initialize(): Boolean = true
  override fun run(frameTimestampMs: Long, inputFrame: ModelInputFrame?): ModelInferenceResult {
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
  override fun run(frameTimestampMs: Long, inputFrame: ModelInputFrame?): ModelInferenceResult {
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

  override fun run(frameTimestampMs: Long, inputFrame: ModelInputFrame?): ModelInferenceResult {
    val result = activeEngine.run(frameTimestampMs, inputFrame)
    if (result.success || activeEngine === fallback) {
      return result
    }
    if (!fallbackActivated && fallback.initialize()) {
      fallbackActivated = true
      activeEngine = fallback
      return fallback.run(frameTimestampMs, inputFrame)
    }
    return result
  }

  override fun preferredInputResolution(): Pair<Int, Int>? {
    return activeEngine.preferredInputResolution()
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
  private var preferredResolution: Pair<Int, Int>? = null
  private var initialized = false

  protected abstract fun loadModelBytes(): ByteArray?

  override fun initialize(): Boolean {
    if (initialized) return true
    return try {
      val modelBytes = loadModelBytes() ?: return false
      val env = OrtEnvironment.getEnvironment()
      val options = OrtSession.SessionOptions().apply {
        runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        runCatching { setInterOpNumThreads(1) }
        runCatching { setIntraOpNumThreads(recommendedIntraOpThreads()) }
        runCatching { setMemoryPatternOptimization(true) }
        runCatching { setCPUArenaAllocator(true) }
      }
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
      preferredResolution = selectPreferredResolution(specs)
      initialized = true
      true
    } catch (t: Throwable) {
      Log.w(TAG, "ONNX init failed($backendName): ${t.message}")
      false
    }
  }

  override fun run(frameTimestampMs: Long, inputFrame: ModelInputFrame?): ModelInferenceResult {
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
    val imagePatternCache = HashMap<String, ByteArray>()
    return try {
      specs.forEachIndexed { index, spec ->
        tensors[spec.name] = createInputTensor(
          env = env,
          spec = spec,
          frameTimestampMs = frameTimestampMs,
          inputIndex = index,
          inputFrame = inputFrame,
          imagePatternCache = imagePatternCache,
        )
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
    preferredResolution = null
    initialized = false
  }

  override fun preferredInputResolution(): Pair<Int, Int>? {
    return preferredResolution
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

  private fun selectPreferredResolution(specs: List<OnnxInputSpec>): Pair<Int, Int>? {
    val imageLike = specs
      .filter { spec ->
        spec.shape.size >= 2 &&
          (spec.name.contains("img", ignoreCase = true) || spec.name.contains("image", ignoreCase = true))
      }
      .mapNotNull { spec ->
        val h = spec.shape[spec.shape.size - 2].toInt()
        val w = spec.shape[spec.shape.size - 1].toInt()
        if (h > 0 && w > 0) w to h else null
      }
      .sortedByDescending { (w, h) -> w * h }

    if (imageLike.isNotEmpty()) {
      return imageLike.first()
    }

    return specs.firstNotNullOfOrNull { spec ->
      if (spec.shape.size < 2) return@firstNotNullOfOrNull null
      val h = spec.shape[spec.shape.size - 2].toInt()
      val w = spec.shape[spec.shape.size - 1].toInt()
      if (h > 0 && w > 0) w to h else null
    }
  }

  private fun isSupportedInputType(type: OnnxJavaType): Boolean {
    return when (type) {
      OnnxJavaType.FLOAT,
      OnnxJavaType.FLOAT16,
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
    inputFrame: ModelInputFrame?,
    imagePatternCache: MutableMap<String, ByteArray>,
  ): OnnxTensor {
    val unsignedPattern = buildUnsignedBytePattern(
      spec = spec,
      frameTimestampMs = frameTimestampMs,
      inputIndex = inputIndex,
      inputFrame = inputFrame,
      imagePatternCache = imagePatternCache,
    )
    return when (spec.type) {
      OnnxJavaType.FLOAT -> {
        val data = FloatArray(spec.elementCount) { index ->
          (unsignedPattern[index].toInt() and 0xFF) / 255.0f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), spec.shape)
      }

      OnnxJavaType.FLOAT16 -> {
        val buffer = directShortByteBuffer(spec.elementCount)
        val shortView = buffer.asShortBuffer()
        repeat(spec.elementCount) { index ->
          val normalized = (unsignedPattern[index].toInt() and 0xFF) / 255.0f
          shortView.put(floatToHalfBits(normalized))
        }
        buffer.rewind()
        OnnxTensor.createTensor(env, buffer, spec.shape, OnnxJavaType.FLOAT16)
      }

      OnnxJavaType.UINT8 -> {
        val buffer = directByteBuffer(unsignedPattern)
        OnnxTensor.createTensor(env, buffer, spec.shape, OnnxJavaType.UINT8)
      }

      OnnxJavaType.INT8 -> {
        val data = ByteArray(spec.elementCount) { index ->
          ((unsignedPattern[index].toInt() and 0xFF) - 128).toByte()
        }
        val buffer = directByteBuffer(data)
        OnnxTensor.createTensor(env, buffer, spec.shape, OnnxJavaType.INT8)
      }

      OnnxJavaType.BOOL -> {
        val data = ByteArray(spec.elementCount) { index ->
          if ((unsignedPattern[index].toInt() and 0xFF) >= 128) 1 else 0
        }
        val buffer = directByteBuffer(data)
        OnnxTensor.createTensor(env, buffer, spec.shape, OnnxJavaType.BOOL)
      }

      OnnxJavaType.INT16 -> {
        val data = ShortArray(spec.elementCount) { index ->
          (unsignedPattern[index].toInt() and 0xFF).toShort()
        }
        OnnxTensor.createTensor(env, ShortBuffer.wrap(data), spec.shape, OnnxJavaType.INT16)
      }

      OnnxJavaType.INT32 -> {
        val data = IntArray(spec.elementCount) { index ->
          unsignedPattern[index].toInt() and 0xFF
        }
        OnnxTensor.createTensor(env, IntBuffer.wrap(data), spec.shape)
      }

      OnnxJavaType.INT64 -> {
        val data = LongArray(spec.elementCount) { index ->
          (unsignedPattern[index].toInt() and 0xFF).toLong()
        }
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), spec.shape)
      }

      else -> {
        throw IllegalStateException("unsupported_input_type=${spec.type}")
      }
    }
  }

  private fun buildUnsignedBytePattern(
    spec: OnnxInputSpec,
    frameTimestampMs: Long,
    inputIndex: Int,
    inputFrame: ModelInputFrame?,
    imagePatternCache: MutableMap<String, ByteArray>,
  ): ByteArray {
    val cachedPattern = imagePatternCache[specPatternCacheKey(spec)]
    if (cachedPattern != null) {
      return cachedPattern
    }

    val fromImage = inputFrame?.let { frame ->
      buildImageDerivedPattern(spec, frame)
    }
    if (fromImage != null) {
      imagePatternCache[specPatternCacheKey(spec)] = fromImage
      return fromImage
    }

    val seed = (((frameTimestampMs + inputIndex * 97L) % 255L) + 1L).toByte()
    return ByteArray(spec.elementCount) { seed }
  }

  private fun buildImageDerivedPattern(spec: OnnxInputSpec, frame: ModelInputFrame): ByteArray? {
    if (frame.width <= 0 || frame.height <= 0) return null
    val expectedPixels = frame.width * frame.height
    if (expectedPixels <= 0 || frame.lumaBytes.size < expectedPixels) return null

    val (targetWidth, targetHeight) = resolveTargetImageSize(spec, frame)
    if (targetWidth <= 0 || targetHeight <= 0) return null
    val resized = resizeLumaNearestNeighbor(
      source = frame.lumaBytes,
      sourceWidth = frame.width,
      sourceHeight = frame.height,
      targetWidth = targetWidth,
      targetHeight = targetHeight,
    )
    if (resized.isEmpty()) return null
    if (resized.size == spec.elementCount) {
      return resized
    }

    val planeSize = resized.size
    if (planeSize > 0 && spec.elementCount % planeSize == 0) {
      val output = ByteArray(spec.elementCount)
      var writeOffset = 0
      while (writeOffset < output.size) {
        System.arraycopy(resized, 0, output, writeOffset, planeSize)
        writeOffset += planeSize
      }
      return output
    }

    return ByteArray(spec.elementCount) { index -> resized[index % planeSize] }
  }

  private fun resolveTargetImageSize(
    spec: OnnxInputSpec,
    frame: ModelInputFrame,
  ): Pair<Int, Int> {
    var targetWidth = frame.width
    var targetHeight = frame.height
    if (spec.shape.size >= 2) {
      val h = spec.shape[spec.shape.size - 2].toInt()
      val w = spec.shape[spec.shape.size - 1].toInt()
      if (h > 0 && w > 0) {
        targetWidth = w
        targetHeight = h
      }
    }

    val targetPixels = targetWidth.toLong() * targetHeight.toLong()
    if (targetPixels > spec.elementCount.toLong()) {
      val side = sqrt(spec.elementCount.toDouble()).toInt().coerceAtLeast(1)
      targetWidth = side
      targetHeight = side
    }
    return targetWidth to targetHeight
  }

  private fun resizeLumaNearestNeighbor(
    source: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
  ): ByteArray {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
      return ByteArray(0)
    }
    if (source.size < sourceWidth * sourceHeight) {
      return ByteArray(0)
    }
    if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
      return source
    }

    val output = ByteArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
      val sourceY = (y.toLong() * sourceHeight / targetHeight)
        .toInt()
        .coerceIn(0, sourceHeight - 1)
      for (x in 0 until targetWidth) {
        val sourceX = (x.toLong() * sourceWidth / targetWidth)
          .toInt()
          .coerceIn(0, sourceWidth - 1)
        output[y * targetWidth + x] = source[sourceY * sourceWidth + sourceX]
      }
    }
    return output
  }

  private fun directByteBuffer(bytes: ByteArray): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
    buffer.put(bytes)
    buffer.rewind()
    return buffer
  }

  private fun directShortByteBuffer(size: Int): ByteBuffer {
    return ByteBuffer.allocateDirect(size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
  }

  private fun recommendedIntraOpThreads(): Int {
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return when {
      cores >= 8 -> 4
      cores >= 6 -> 3
      cores >= 4 -> 2
      else -> 1
    }
  }

  private fun specPatternCacheKey(spec: OnnxInputSpec): String {
    return "${spec.type}|${spec.elementCount}|${spec.shape.joinToString("x")}"
  }

  private fun floatToHalfBits(value: Float): Short {
    val bits = value.toRawBits()
    val sign = (bits ushr 16) and 0x8000
    var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
    var mantissa = bits and 0x7FFFFF

    if (exponent <= 0) {
      if (exponent < -10) {
        return sign.toShort()
      }
      mantissa = mantissa or 0x800000
      val shift = 14 - exponent
      var halfMantissa = mantissa ushr shift
      if (((mantissa ushr (shift - 1)) and 0x1) == 1) {
        halfMantissa += 1
      }
      return (sign or halfMantissa).toShort()
    }

    if (exponent >= 31) {
      return (sign or 0x7C00).toShort()
    }

    var halfBits = sign or (exponent shl 10) or (mantissa ushr 13)
    if ((mantissa and 0x1000) != 0) {
      halfBits += 1
    }
    return halfBits.toShort()
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
