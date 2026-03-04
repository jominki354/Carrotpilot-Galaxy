package io.carrotpilot.galaxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.carrotpilot.galaxy.driving.DrivingCoreState
import io.carrotpilot.galaxy.model.ModelRuntimeMockScenario
import io.carrotpilot.galaxy.model.ModelRuntimeSourceMode
import io.carrotpilot.galaxy.model.ModelRuntimeState
import io.carrotpilot.galaxy.runtime.RuntimeSourceMode
import io.carrotpilot.galaxy.vehicle.VehicleRecognitionState
import io.carrotpilot.galaxy.vehicle.fake.FakeScenario

@Composable
fun DriveScreen(
  sourceMode: RuntimeSourceMode,
  fakeScenario: FakeScenario,
  fakeSuiteRunning: Boolean,
  fakeSuiteReport: List<String>,
  modelRuntimeState: ModelRuntimeState,
  drivingCoreState: DrivingCoreState,
  drivingEnableRequested: Boolean,
  modelSourceMode: ModelRuntimeSourceMode,
  modelScenario: ModelRuntimeMockScenario,
  modelSuiteRunning: Boolean,
  modelSuiteReport: List<String>,
  deviceBatteryTempC: Double?,
  state: VehicleRecognitionState,
  onSelectSourceMode: (RuntimeSourceMode) -> Unit,
  onSelectFakeScenario: (FakeScenario) -> Unit,
  onSelectModelSourceMode: (ModelRuntimeSourceMode) -> Unit,
  onSelectModelScenario: (ModelRuntimeMockScenario) -> Unit,
  onRunFakeSuite: () -> Unit,
  onStartModelRuntime: () -> Unit,
  onStopModelRuntime: () -> Unit,
  onResetModelRuntime: () -> Unit,
  onRunModelSuite: () -> Unit,
  onStartDrivingCore: () -> Unit,
  onStopDrivingCore: () -> Unit,
  onResetDrivingCore: () -> Unit,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onReset: () -> Unit,
) {
  var showAdvancedFakeControls by remember { mutableStateOf(false) }
  var showAdvancedModelControls by remember { mutableStateOf(false) }
  val scrollState = rememberScrollState()
  val clipboardManager = LocalClipboardManager.current
  val temperatureColor = when {
    deviceBatteryTempC == null -> MaterialTheme.colorScheme.onSurfaceVariant
    deviceBatteryTempC < 37.0 -> Color(0xFF2E7D32)
    deviceBatteryTempC < 41.0 -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
  }
  val temperatureBand = when {
    deviceBatteryTempC == null -> "UNKNOWN"
    deviceBatteryTempC < 37.0 -> "COOL"
    deviceBatteryTempC < 41.0 -> "WARM"
    else -> "HOT"
  }
  val temperatureText = deviceBatteryTempC?.let { String.format("%.1f", it) } ?: "-"
  val copyDebugText = {
    val candidatePreview = state.candidateCars.sorted().take(8).joinToString(", ")
    val suiteText = if (fakeSuiteReport.isEmpty()) "-" else fakeSuiteReport.joinToString("\n")
    val modelSuiteText = if (modelSuiteReport.isEmpty()) "-" else modelSuiteReport.joinToString("\n")
    val payload = buildString {
      appendLine("source_mode=$sourceMode")
      appendLine("device_battery_temp_c=$temperatureText")
      appendLine("device_temp_band=$temperatureBand")
      appendLine("fake_scenario=$fakeScenario")
      appendLine("fake_suite_status=${if (fakeSuiteRunning) "RUNNING" else "IDLE"}")
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
      appendLine("model_scenario=$modelScenario")
      appendLine("model_source_mode=$modelSourceMode")
      appendLine("model_suite_status=${if (modelSuiteRunning) "RUNNING" else "IDLE"}")
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
      appendLine("g3_enable_requested=$drivingEnableRequested")
      appendLine("g3_stage=${drivingCoreState.stage}")
      appendLine("g3_error=${drivingCoreState.error}")
      appendLine("g3_lat_active=${drivingCoreState.latActive}")
      appendLine("g3_long_active=${drivingCoreState.longActive}")
      appendLine("g3_sendcan_allowed=${drivingCoreState.sendcanAllowed}")
      appendLine("g3_planner_hz=${String.format("%.1f", drivingCoreState.plannerHz)}")
      appendLine("g3_control_hz=${String.format("%.1f", drivingCoreState.controlHz)}")
      appendLine("g3_planner_ticks=${drivingCoreState.plannerTicks}")
      appendLine("g3_control_ticks=${drivingCoreState.controlTicks}")
      appendLine("model_suite_report:")
      appendLine(modelSuiteText)
    }
    clipboardManager.setText(AnnotatedString(payload))
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .statusBarsPadding()
          .navigationBarsPadding()
          .verticalScroll(scrollState)
          .padding(16.dp)
          .padding(top = 52.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(text = "Carrotpilot Galaxy - G1 Vehicle Recognition", style = MaterialTheme.typography.titleMedium)
        Text(text = "Source mode: $sourceMode")
        Row(modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = { onSelectSourceMode(RuntimeSourceMode.REAL_USB) },
            enabled = sourceMode != RuntimeSourceMode.REAL_USB,
          ) {
            Text("Real USB")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = { onSelectSourceMode(RuntimeSourceMode.FAKE_USB) },
            enabled = sourceMode != RuntimeSourceMode.FAKE_USB,
          ) {
            Text("Fake USB")
          }
        }

        if (sourceMode == RuntimeSourceMode.FAKE_USB) {
          Text(text = "Fake scenario: $fakeScenario")
          Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onRunFakeSuite, enabled = !fakeSuiteRunning) {
              Text("Run Fake Suite")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = { showAdvancedFakeControls = !showAdvancedFakeControls },
            ) {
              Text(if (showAdvancedFakeControls) "Hide Advanced" else "Show Advanced")
            }
          }
          Text(
            text = "Guide: Run Fake Suite runs G1 automatically. Use Start G1 only for manual single-scenario debug.",
          )
          Text(text = "Suite status: ${if (fakeSuiteRunning) "RUNNING" else "IDLE"}")
          if (fakeSuiteReport.isNotEmpty()) {
            Text(text = "Suite report:")
            fakeSuiteReport.takeLast(6).forEach { line ->
              Text(text = line)
            }
          }

          if (showAdvancedFakeControls) {
            Row(modifier = Modifier.fillMaxWidth()) {
              Button(
                onClick = { onSelectFakeScenario(FakeScenario.HAPPY_PATH) },
                enabled = fakeScenario != FakeScenario.HAPPY_PATH,
              ) {
                Text("Happy")
              }
              Spacer(modifier = Modifier.width(8.dp))
              Button(
                onClick = { onSelectFakeScenario(FakeScenario.FINGERPRINT_TIMEOUT) },
                enabled = fakeScenario != FakeScenario.FINGERPRINT_TIMEOUT,
              ) {
                Text("FP Timeout")
              }
              Spacer(modifier = Modifier.width(8.dp))
              Button(
                onClick = { onSelectFakeScenario(FakeScenario.CAN_TIMEOUT) },
                enabled = fakeScenario != FakeScenario.CAN_TIMEOUT,
              ) {
                Text("CAN Timeout")
              }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
              Button(
                onClick = { onSelectFakeScenario(FakeScenario.INTERFACE_LOAD_FAIL) },
                enabled = fakeScenario != FakeScenario.INTERFACE_LOAD_FAIL,
              ) {
                Text("IF Load Fail")
              }
              Spacer(modifier = Modifier.width(8.dp))
              Button(
                onClick = { onSelectFakeScenario(FakeScenario.PANDA_CONNECT_FAIL) },
                enabled = fakeScenario != FakeScenario.PANDA_CONNECT_FAIL,
              ) {
                Text("Panda Fail")
              }
              Spacer(modifier = Modifier.width(8.dp))
              Button(
                onClick = { onSelectFakeScenario(FakeScenario.USB_PERMISSION_DENIED) },
                enabled = fakeScenario != FakeScenario.USB_PERMISSION_DENIED,
              ) {
                Text("USB Denied")
              }
            }
          }
        }

        Text(text = "Stage: ${state.stage}")
        Text(text = "Error: ${state.error}")
        Text(text = "Panda retries: ${state.pandaConnectRetries}")
        Text(text = "Identified car: ${state.identifiedCarFingerprint ?: "-"}")
        Text(text = "Observed signatures: ${state.observedSignatureCount}")
        val candidatePreview = state.candidateCars.sorted().take(4).joinToString(", ")
        Text(text = "Candidates: ${if (candidatePreview.isBlank()) "-" else candidatePreview}")
        if (state.carParams != null) {
          Text(
            text = "CarParams: ${state.carParams.carFingerprint} / ${state.carParams.safetyModel}(${state.carParams.safetyParam})",
          )
        }
        if (state.interfaceLoadFailureReason != null) {
          Text(text = "Interface load failure: ${state.interfaceLoadFailureReason}")
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = onStart,
          ) {
            Text("Start G1")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = onStop,
          ) {
            Text("Stop G1")
          }
        }

        Button(onClick = onReset) {
          Text("Reset")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "G2 Model Runtime", style = MaterialTheme.typography.titleMedium)
        Text(text = "Model source: $modelSourceMode")
        Row(modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = { onSelectModelSourceMode(ModelRuntimeSourceMode.MOCK) },
            enabled = modelSourceMode != ModelRuntimeSourceMode.MOCK,
          ) {
            Text("Model Mock")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = { onSelectModelSourceMode(ModelRuntimeSourceMode.REAL_CAMERA) },
            enabled = modelSourceMode != ModelRuntimeSourceMode.REAL_CAMERA,
          ) {
            Text("Real Camera")
          }
        }
        Text(text = "Model scenario: $modelScenario")
        Row(modifier = Modifier.fillMaxWidth()) {
          Button(onClick = onRunModelSuite, enabled = !modelSuiteRunning) {
            Text("Run Model Suite")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(onClick = onStartModelRuntime) {
            Text("Start G2")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(onClick = onStopModelRuntime) {
            Text("Stop G2")
          }
        }
        Text(
          text = "Guide: Run Model Suite uses MOCK mode. In REAL_CAMERA mode, use Start/Stop G2 for live camera ingest check.",
        )
        Row(modifier = Modifier.fillMaxWidth()) {
          Button(onClick = onResetModelRuntime) {
            Text("Reset G2")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(onClick = { showAdvancedModelControls = !showAdvancedModelControls }) {
            Text(if (showAdvancedModelControls) "Hide G2 Advanced" else "Show G2 Advanced")
          }
        }
        Text(text = "Model suite status: ${if (modelSuiteRunning) "RUNNING" else "IDLE"}")
        if (modelSuiteReport.isNotEmpty()) {
          Text(text = "Model suite report:")
          modelSuiteReport.takeLast(4).forEach { line ->
            Text(text = line)
          }
        }
        if (showAdvancedModelControls) {
          Row(modifier = Modifier.fillMaxWidth()) {
            Button(
              onClick = { onSelectModelScenario(ModelRuntimeMockScenario.HAPPY_PATH) },
              enabled = modelScenario != ModelRuntimeMockScenario.HAPPY_PATH,
            ) {
              Text("Model Happy")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = { onSelectModelScenario(ModelRuntimeMockScenario.CAMERA_PERMISSION_DENIED) },
              enabled = modelScenario != ModelRuntimeMockScenario.CAMERA_PERMISSION_DENIED,
            ) {
              Text("Cam Denied")
            }
          }
          Row(modifier = Modifier.fillMaxWidth()) {
            Button(
              onClick = { onSelectModelScenario(ModelRuntimeMockScenario.MODEL_TIMEOUT) },
              enabled = modelScenario != ModelRuntimeMockScenario.MODEL_TIMEOUT,
            ) {
              Text("Model Timeout")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = { onSelectModelScenario(ModelRuntimeMockScenario.POSE_TIMEOUT) },
              enabled = modelScenario != ModelRuntimeMockScenario.POSE_TIMEOUT,
            ) {
              Text("Pose Timeout")
            }
          }
        }
        Text(text = "G2 Stage: ${modelRuntimeState.stage}")
        Text(text = "G2 Error: ${modelRuntimeState.error}")
        Text(text = "G2 modelHz: ${String.format("%.1f", modelRuntimeState.modelHz)}")
        Text(text = "G2 poseHz: ${String.format("%.1f", modelRuntimeState.poseHz)}")
        Text(text = "G2 modelFrames: ${modelRuntimeState.modelFrameCount}")
        Text(text = "G2 poseSamples: ${modelRuntimeState.poseSampleCount}")
        Text(text = "G2 frameDropPerc: ${String.format("%.1f", modelRuntimeState.frameDropPerc)}")
        Text(text = "G2 inferenceBackend: ${modelRuntimeState.inferenceBackend}")
        Text(text = "G2 inferenceReady: ${modelRuntimeState.inferenceReady}")
        Text(text = "G2 inferenceOutputs: ${modelRuntimeState.inferenceOutputCount}")
        Text(text = "G2 inferenceLatencyP50(ms): ${String.format("%.2f", modelRuntimeState.inferenceLatencyMsP50)}")
        Text(text = "G2 inferenceLatencyP95(ms): ${String.format("%.2f", modelRuntimeState.inferenceLatencyMsP95)}")
        Text(text = "G2 inferenceFailures: ${modelRuntimeState.inferenceFailures}")
        Text(text = "G2 inferenceLastFailure: ${modelRuntimeState.inferenceLastFailure}")
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "G3 Driving Core", style = MaterialTheme.typography.titleMedium)
        Text(text = "G3 enableRequested: $drivingEnableRequested")
        Row(modifier = Modifier.fillMaxWidth()) {
          Button(onClick = onStartDrivingCore) {
            Text("Start G3")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(onClick = onStopDrivingCore) {
            Text("Stop G3")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(onClick = onResetDrivingCore) {
            Text("Reset G3")
          }
        }
        Text(text = "G3 stage: ${drivingCoreState.stage}")
        Text(text = "G3 error: ${drivingCoreState.error}")
        Text(text = "G3 latActive: ${drivingCoreState.latActive}")
        Text(text = "G3 longActive: ${drivingCoreState.longActive}")
        Text(text = "G3 sendcanAllowed: ${drivingCoreState.sendcanAllowed}")
        Text(text = "G3 plannerHz: ${String.format("%.1f", drivingCoreState.plannerHz)}")
        Text(text = "G3 controlHz: ${String.format("%.1f", drivingCoreState.controlHz)}")
        Text(text = "G3 plannerTicks: ${drivingCoreState.plannerTicks}")
        Text(text = "G3 controlTicks: ${drivingCoreState.controlTicks}")
        Spacer(modifier = Modifier.height(12.dp))
      }

      Row(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .statusBarsPadding()
          .padding(12.dp),
      ) {
        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = "Temp: ${temperatureText}C ($temperatureBand)",
            color = temperatureColor,
            style = MaterialTheme.typography.titleSmall,
          )
          Text(
            text = "Hz ${String.format("%.1f", modelRuntimeState.modelHz)}",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "P95 ${String.format("%.1f", modelRuntimeState.inferenceLatencyMsP95)} ms",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "Drop ${String.format("%.1f", modelRuntimeState.frameDropPerc)}%",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = copyDebugText) {
          Text("Copy Debug Text")
        }
      }
    }
  }
}
