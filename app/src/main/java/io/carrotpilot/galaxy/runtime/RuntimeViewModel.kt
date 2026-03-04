package io.carrotpilot.galaxy.runtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.carrotpilot.galaxy.model.ModelRuntimeCameraPipeline
import io.carrotpilot.galaxy.model.ModelRuntimeErrorCode
import io.carrotpilot.galaxy.model.ModelRuntimeMockPipeline
import io.carrotpilot.galaxy.model.ModelRuntimeMockScenario
import io.carrotpilot.galaxy.model.ModelRuntimeSourceMode
import io.carrotpilot.galaxy.model.ModelRuntimeStage
import io.carrotpilot.galaxy.model.ModelRuntimeState
import io.carrotpilot.galaxy.model.ModelInputFrame
import io.carrotpilot.galaxy.model.FallbackInferenceEngine
import io.carrotpilot.galaxy.model.OnnxPlaceholderInferenceEngine
import io.carrotpilot.galaxy.model.OnnxRuntimeAssetInferenceEngine
import io.carrotpilot.galaxy.model.OnnxRuntimeExternalFileInferenceEngine
import io.carrotpilot.galaxy.vehicle.AndroidCanIngestSource
import io.carrotpilot.galaxy.vehicle.AndroidUsbHostManager
import io.carrotpilot.galaxy.vehicle.DefaultCarInterfaceLoader
import io.carrotpilot.galaxy.vehicle.SimpleFingerprintEngine
import io.carrotpilot.galaxy.vehicle.VehicleRecognitionCoordinator
import io.carrotpilot.galaxy.vehicle.VehicleErrorCode
import io.carrotpilot.galaxy.vehicle.VehicleStage
import io.carrotpilot.galaxy.vehicle.VehicleRecognitionState
import io.carrotpilot.galaxy.vehicle.fake.FakeCanIngestSource
import io.carrotpilot.galaxy.vehicle.fake.FakeScenario
import io.carrotpilot.galaxy.vehicle.fake.FakeUsbHostManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RuntimeViewModel(
  application: Application,
) : AndroidViewModel(application) {
  private val appContext = application.applicationContext
  private val coordinatorMutex = Mutex()
  private var stateCollectorJob: Job? = null

  private val _sourceMode = MutableStateFlow(RuntimeSourceMode.REAL_USB)
  val sourceMode: StateFlow<RuntimeSourceMode> = _sourceMode.asStateFlow()

  private val _fakeScenario = MutableStateFlow(FakeScenario.HAPPY_PATH)
  val fakeScenario: StateFlow<FakeScenario> = _fakeScenario.asStateFlow()

  private val _fakeSuiteRunning = MutableStateFlow(false)
  val fakeSuiteRunning: StateFlow<Boolean> = _fakeSuiteRunning.asStateFlow()

  private val _fakeSuiteReport = MutableStateFlow<List<String>>(emptyList())
  val fakeSuiteReport: StateFlow<List<String>> = _fakeSuiteReport.asStateFlow()

  private var runtimeCoordinator: VehicleRecognitionCoordinator =
    createCoordinator(_sourceMode.value, _fakeScenario.value)

  private val _vehicleState = MutableStateFlow(runtimeCoordinator.state.value)
  val vehicleState: StateFlow<VehicleRecognitionState> = _vehicleState.asStateFlow()

  private val modelRuntimeMock = ModelRuntimeMockPipeline(scope = viewModelScope)
  private val modelRuntimeCamera = ModelRuntimeCameraPipeline(
    scope = viewModelScope,
    inferenceEngine = FallbackInferenceEngine(
      primary = OnnxRuntimeExternalFileInferenceEngine(
        context = appContext,
        relativeExternalFilePath = "models/comma_model.onnx",
      ),
      fallback = FallbackInferenceEngine(
        primary = OnnxRuntimeAssetInferenceEngine(
          context = appContext,
          modelAssetPath = "models/comma_model.onnx",
        ),
        fallback = FallbackInferenceEngine(
          primary = OnnxRuntimeAssetInferenceEngine(
            context = appContext,
            modelAssetPath = "models/mul_1.onnx",
          ),
          fallback = OnnxPlaceholderInferenceEngine(),
        ),
      ),
    ),
  )
  private var modelStateCollectorJob: Job? = null

  private val _modelRuntimeState = MutableStateFlow(modelRuntimeMock.state.value)
  val modelRuntimeState: StateFlow<ModelRuntimeState> = _modelRuntimeState.asStateFlow()

  private val _modelScenario = MutableStateFlow(ModelRuntimeMockScenario.HAPPY_PATH)
  val modelScenario: StateFlow<ModelRuntimeMockScenario> = _modelScenario.asStateFlow()

  private val _modelSourceMode = MutableStateFlow(ModelRuntimeSourceMode.MOCK)
  val modelSourceMode: StateFlow<ModelRuntimeSourceMode> = _modelSourceMode.asStateFlow()

  private val _modelSuiteRunning = MutableStateFlow(false)
  val modelSuiteRunning: StateFlow<Boolean> = _modelSuiteRunning.asStateFlow()

  private val _modelSuiteReport = MutableStateFlow<List<String>>(emptyList())
  val modelSuiteReport: StateFlow<List<String>> = _modelSuiteReport.asStateFlow()

  init {
    attachStateCollector(runtimeCoordinator)
    attachModelStateCollector(_modelSourceMode.value)
  }

  fun startVehicleRecognition() {
    viewModelScope.launch {
      coordinatorMutex.withLock {
        runtimeCoordinator.start()
      }
    }
  }

  fun stopVehicleRecognition() {
    viewModelScope.launch {
      coordinatorMutex.withLock {
        runtimeCoordinator.stop()
      }
    }
  }

  fun resetState() {
    viewModelScope.launch {
      coordinatorMutex.withLock {
        runtimeCoordinator.reset()
      }
    }
  }

  fun selectSourceMode(mode: RuntimeSourceMode) {
    if (_sourceMode.value == mode) return
    viewModelScope.launch {
      coordinatorMutex.withLock {
        runtimeCoordinator.stop()
        runtimeCoordinator = createCoordinator(mode, _fakeScenario.value)
        _sourceMode.value = mode
        _vehicleState.value = runtimeCoordinator.state.value
        attachStateCollector(runtimeCoordinator)
      }
    }
  }

  fun selectFakeScenario(scenario: FakeScenario) {
    if (_fakeScenario.value == scenario) return
    viewModelScope.launch {
      coordinatorMutex.withLock {
        _fakeScenario.value = scenario
        if (_sourceMode.value == RuntimeSourceMode.FAKE_USB) {
          runtimeCoordinator.stop()
          runtimeCoordinator = createCoordinator(RuntimeSourceMode.FAKE_USB, scenario)
          _vehicleState.value = runtimeCoordinator.state.value
          attachStateCollector(runtimeCoordinator)
        }
      }
    }
  }

  fun runFakeSuite() {
    if (_fakeSuiteRunning.value) return

    viewModelScope.launch {
      _fakeSuiteRunning.value = true
      _fakeSuiteReport.value = listOf("Running fake suite...")

      val scenarios = listOf(
        FakeScenario.HAPPY_PATH,
        FakeScenario.USB_PERMISSION_DENIED,
        FakeScenario.PANDA_CONNECT_FAIL,
        FakeScenario.FINGERPRINT_TIMEOUT,
        FakeScenario.CAN_TIMEOUT,
        FakeScenario.INTERFACE_LOAD_FAIL,
      )

      val reportLines = mutableListOf<String>()
      for (scenario in scenarios) {
        val line = runFakeScenarioOnce(scenario)
        reportLines += line
        _fakeSuiteReport.value = reportLines.toList()
      }

      coordinatorMutex.withLock {
        runtimeCoordinator.stop()
        runtimeCoordinator = createCoordinator(RuntimeSourceMode.FAKE_USB, FakeScenario.HAPPY_PATH)
        _sourceMode.value = RuntimeSourceMode.FAKE_USB
        _fakeScenario.value = FakeScenario.HAPPY_PATH
        _vehicleState.value = runtimeCoordinator.state.value
        attachStateCollector(runtimeCoordinator)
      }

      _fakeSuiteRunning.value = false
    }
  }

  fun selectModelScenario(scenario: ModelRuntimeMockScenario) {
    _modelScenario.value = scenario
  }

  fun selectModelSourceMode(mode: ModelRuntimeSourceMode) {
    if (_modelSourceMode.value == mode) return
    viewModelScope.launch {
      modelRuntimeMock.stop()
      modelRuntimeCamera.stopSession()
      _modelSourceMode.value = mode
      attachModelStateCollector(mode)
    }
  }

  fun startModelRuntimeMock() {
    viewModelScope.launch {
      _modelSourceMode.value = ModelRuntimeSourceMode.MOCK
      attachModelStateCollector(ModelRuntimeSourceMode.MOCK)
      modelRuntimeCamera.stopSession()
      modelRuntimeMock.start(_modelScenario.value)
    }
  }

  fun stopModelRuntimeMock() {
    viewModelScope.launch {
      modelRuntimeMock.stop()
    }
  }

  fun resetModelRuntimeMock() {
    modelRuntimeMock.reset()
  }

  fun startModelRuntimeRealCameraSession(permissionGranted: Boolean) {
    viewModelScope.launch {
      _modelSourceMode.value = ModelRuntimeSourceMode.REAL_CAMERA
      attachModelStateCollector(ModelRuntimeSourceMode.REAL_CAMERA)
      modelRuntimeMock.stop()
      modelRuntimeCamera.startSession(permissionGranted)
    }
  }

  fun markModelRuntimeCameraPermissionDenied() {
    _modelSourceMode.value = ModelRuntimeSourceMode.REAL_CAMERA
    modelRuntimeCamera.markPermissionDenied()
    viewModelScope.launch {
      modelRuntimeMock.stop()
      attachModelStateCollector(ModelRuntimeSourceMode.REAL_CAMERA)
    }
  }

  fun stopModelRuntimeRealCameraSession() {
    viewModelScope.launch {
      modelRuntimeCamera.stopSession()
    }
  }

  fun resetModelRuntimeRealCameraSession() {
    modelRuntimeCamera.reset()
  }

  fun onRealCameraFrame(timestampMs: Long, frame: ModelInputFrame? = null) {
    modelRuntimeCamera.onCameraFrame(timestampMs, frame)
  }

  fun onRealCameraSourceError() {
    modelRuntimeCamera.onCameraSourceError()
  }

  fun runModelSuite() {
    if (_modelSuiteRunning.value) return

    viewModelScope.launch {
      _modelSourceMode.value = ModelRuntimeSourceMode.MOCK
      attachModelStateCollector(ModelRuntimeSourceMode.MOCK)
      modelRuntimeCamera.stopSession()
      _modelSuiteRunning.value = true
      _modelSuiteReport.value = listOf("Running model suite...")
      val scenarios = listOf(
        ModelRuntimeMockScenario.HAPPY_PATH,
        ModelRuntimeMockScenario.CAMERA_PERMISSION_DENIED,
        ModelRuntimeMockScenario.MODEL_TIMEOUT,
        ModelRuntimeMockScenario.POSE_TIMEOUT,
      )

      val reportLines = mutableListOf<String>()
      for (scenario in scenarios) {
        val line = runModelScenarioOnce(scenario)
        reportLines += line
        _modelSuiteReport.value = reportLines.toList()
      }

      _modelScenario.value = ModelRuntimeMockScenario.HAPPY_PATH
      modelRuntimeMock.stop()
      _modelSuiteRunning.value = false
    }
  }

  private fun attachStateCollector(coordinator: VehicleRecognitionCoordinator) {
    stateCollectorJob?.cancel()
    stateCollectorJob = viewModelScope.launch {
      coordinator.state.collect { state ->
        _vehicleState.value = state
      }
    }
  }

  private fun attachModelStateCollector(mode: ModelRuntimeSourceMode) {
    modelStateCollectorJob?.cancel()
    modelStateCollectorJob = viewModelScope.launch {
      val source = when (mode) {
        ModelRuntimeSourceMode.MOCK -> modelRuntimeMock.state
        ModelRuntimeSourceMode.REAL_CAMERA -> modelRuntimeCamera.state
      }
      source.collect { state ->
        _modelRuntimeState.value = state
      }
    }
  }

  private suspend fun runFakeScenarioOnce(scenario: FakeScenario): String {
    coordinatorMutex.withLock {
      runtimeCoordinator.stop()
      runtimeCoordinator = createCoordinator(RuntimeSourceMode.FAKE_USB, scenario)
      _sourceMode.value = RuntimeSourceMode.FAKE_USB
      _fakeScenario.value = scenario
      _vehicleState.value = runtimeCoordinator.state.value
      attachStateCollector(runtimeCoordinator)
      runtimeCoordinator.reset()
      runtimeCoordinator.start()
    }

    val timeoutMs = when (scenario) {
      FakeScenario.HAPPY_PATH -> 4_000L
      FakeScenario.USB_PERMISSION_DENIED -> 1_500L
      FakeScenario.PANDA_CONNECT_FAIL -> 1_500L
      FakeScenario.FINGERPRINT_TIMEOUT -> 7_000L
      FakeScenario.CAN_TIMEOUT -> 5_000L
      FakeScenario.INTERFACE_LOAD_FAIL -> 4_000L
    }

    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() <= deadline) {
      val state = _vehicleState.value
      if (isScenarioSatisfied(scenario, state)) {
        coordinatorMutex.withLock { runtimeCoordinator.stop() }
        return formatScenarioResultLine(scenario, state, pass = true)
      }
      delay(100)
    }

    coordinatorMutex.withLock { runtimeCoordinator.stop() }
    return formatScenarioResultLine(scenario, _vehicleState.value, pass = false)
  }

  private fun isScenarioSatisfied(
    scenario: FakeScenario,
    state: VehicleRecognitionState,
  ): Boolean {
    return when (scenario) {
      FakeScenario.HAPPY_PATH ->
        state.stage == VehicleStage.SAFETY_READY && state.error == VehicleErrorCode.NONE

      FakeScenario.USB_PERMISSION_DENIED ->
        state.error == VehicleErrorCode.USB_PERMISSION_DENIED

      FakeScenario.PANDA_CONNECT_FAIL ->
        state.error == VehicleErrorCode.PANDA_CONNECT_FAIL

      FakeScenario.FINGERPRINT_TIMEOUT ->
        state.error == VehicleErrorCode.FINGERPRINT_TIMEOUT

      FakeScenario.CAN_TIMEOUT ->
        state.error == VehicleErrorCode.CAN_TIMEOUT

      FakeScenario.INTERFACE_LOAD_FAIL ->
        state.error == VehicleErrorCode.INTERFACE_LOAD_FAIL
    }
  }

  private fun formatScenarioResultLine(
    scenario: FakeScenario,
    state: VehicleRecognitionState,
    pass: Boolean,
  ): String {
    val status = if (pass) "PASS" else "FAIL"
    val car = state.identifiedCarFingerprint ?: "-"
    return "[$status] $scenario -> ${state.stage}/${state.error}/${state.pandaConnectRetries}/$car"
  }

  private suspend fun runModelScenarioOnce(scenario: ModelRuntimeMockScenario): String {
    _modelScenario.value = scenario
    modelRuntimeMock.stop()
    modelRuntimeMock.start(scenario)

    val timeoutMs = when (scenario) {
      ModelRuntimeMockScenario.HAPPY_PATH -> 5_000L
      ModelRuntimeMockScenario.CAMERA_PERMISSION_DENIED -> 1_500L
      ModelRuntimeMockScenario.MODEL_TIMEOUT -> 4_000L
      ModelRuntimeMockScenario.POSE_TIMEOUT -> 4_000L
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() <= deadline) {
      val state = _modelRuntimeState.value
      if (isModelScenarioSatisfied(scenario, state)) {
        modelRuntimeMock.stop()
        return formatModelScenarioResultLine(scenario, state, pass = true)
      }
      delay(100L)
    }

    modelRuntimeMock.stop()
    return formatModelScenarioResultLine(scenario, _modelRuntimeState.value, pass = false)
  }

  private fun isModelScenarioSatisfied(
    scenario: ModelRuntimeMockScenario,
    state: ModelRuntimeState,
  ): Boolean {
    return when (scenario) {
      ModelRuntimeMockScenario.HAPPY_PATH ->
        state.stage == ModelRuntimeStage.STABLE &&
          state.error == ModelRuntimeErrorCode.NONE &&
          state.modelHz in 16.0..24.5

      ModelRuntimeMockScenario.CAMERA_PERMISSION_DENIED ->
        state.error == ModelRuntimeErrorCode.CAMERA_PERMISSION_DENIED

      ModelRuntimeMockScenario.MODEL_TIMEOUT ->
        state.error == ModelRuntimeErrorCode.MODEL_TIMEOUT

      ModelRuntimeMockScenario.POSE_TIMEOUT ->
        state.error == ModelRuntimeErrorCode.POSE_TIMEOUT
    }
  }

  private fun formatModelScenarioResultLine(
    scenario: ModelRuntimeMockScenario,
    state: ModelRuntimeState,
    pass: Boolean,
  ): String {
    val status = if (pass) "PASS" else "FAIL"
    val modelHzText = String.format("%.1f", state.modelHz)
    val poseHzText = String.format("%.1f", state.poseHz)
    return "[$status] $scenario -> ${state.stage}/${state.error}/modelHz=$modelHzText/poseHz=$poseHzText"
  }

  fun buildDebugSnapshotText(): String {
    val state = _vehicleState.value
    val modelRuntimeState = _modelRuntimeState.value
    val candidatePreview = state.candidateCars.sorted().take(8).joinToString(", ")
    val suiteText = if (_fakeSuiteReport.value.isEmpty()) "-" else _fakeSuiteReport.value.joinToString("\n")
    val modelSuiteText = if (_modelSuiteReport.value.isEmpty()) "-" else _modelSuiteReport.value.joinToString("\n")

    return buildString {
      appendLine("source_mode=${_sourceMode.value}")
      appendLine("fake_scenario=${_fakeScenario.value}")
      appendLine("fake_suite_status=${if (_fakeSuiteRunning.value) "RUNNING" else "IDLE"}")
      appendLine("g1_stage=${state.stage}")
      appendLine("g1_error=${state.error}")
      appendLine("panda_retries=${state.pandaConnectRetries}")
      appendLine("identified_car=${state.identifiedCarFingerprint ?: "-"}")
      appendLine("observed_signatures=${state.observedSignatureCount}")
      appendLine("candidates=${if (candidatePreview.isBlank()) "-" else candidatePreview}")
      appendLine(
        "car_params=${state.carParams?.carFingerprint ?: "-"}|" +
          "${state.carParams?.safetyModel ?: "-"}|" +
          "${state.carParams?.safetyParam ?: "-"}",
      )
      appendLine("interface_load_failure=${state.interfaceLoadFailureReason ?: "-"}")
      appendLine("fake_suite_report:")
      appendLine(suiteText)
      appendLine("model_scenario=${_modelScenario.value}")
      appendLine("model_source_mode=${_modelSourceMode.value}")
      appendLine("model_suite_status=${if (_modelSuiteRunning.value) "RUNNING" else "IDLE"}")
      appendLine("g2_stage=${modelRuntimeState.stage}")
      appendLine("g2_error=${modelRuntimeState.error}")
      appendLine("g2_model_hz=${String.format("%.1f", modelRuntimeState.modelHz)}")
      appendLine("g2_pose_hz=${String.format("%.1f", modelRuntimeState.poseHz)}")
      appendLine("g2_model_frames=${modelRuntimeState.modelFrameCount}")
      appendLine("g2_pose_samples=${modelRuntimeState.poseSampleCount}")
      appendLine("g2_frame_drop_perc=${String.format("%.1f", modelRuntimeState.frameDropPerc)}")
      appendLine("g2_inference_backend=${modelRuntimeState.inferenceBackend}")
      appendLine("g2_inference_ready=${modelRuntimeState.inferenceReady}")
      appendLine("g2_inference_outputs=${modelRuntimeState.inferenceOutputCount}")
      appendLine("g2_inference_latency_ms_p50=${String.format("%.2f", modelRuntimeState.inferenceLatencyMsP50)}")
      appendLine("g2_inference_latency_ms_p95=${String.format("%.2f", modelRuntimeState.inferenceLatencyMsP95)}")
      appendLine("g2_inference_failures=${modelRuntimeState.inferenceFailures}")
      appendLine("g2_inference_last_failure=${modelRuntimeState.inferenceLastFailure}")
      appendLine("model_suite_report:")
      appendLine(modelSuiteText)
    }
  }

  private fun createCoordinator(
    mode: RuntimeSourceMode,
    fakeScenario: FakeScenario,
  ): VehicleRecognitionCoordinator {
    return when (mode) {
      RuntimeSourceMode.REAL_USB -> {
        val usb = AndroidUsbHostManager(appContext)
        VehicleRecognitionCoordinator(
          scope = viewModelScope,
          usbHostManager = usb,
          canIngestSource = AndroidCanIngestSource(usb),
          fingerprintEngine = SimpleFingerprintEngine(),
          carInterfaceLoader = DefaultCarInterfaceLoader(),
        )
      }

      RuntimeSourceMode.FAKE_USB -> {
        val failForCars = if (fakeScenario == FakeScenario.INTERFACE_LOAD_FAIL) {
          setOf("HYUNDAI_CASPER_EV")
        } else {
          emptySet()
        }
        VehicleRecognitionCoordinator(
          scope = viewModelScope,
          usbHostManager = FakeUsbHostManager(scenario = fakeScenario),
          canIngestSource = FakeCanIngestSource(scenario = fakeScenario),
          fingerprintEngine = SimpleFingerprintEngine(),
          carInterfaceLoader = DefaultCarInterfaceLoader(failForCars = failForCars),
        )
      }
    }
  }
}
