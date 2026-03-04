package io.carrotpilot.galaxy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.carrotpilot.galaxy.model.AndroidCameraFrameSource
import io.carrotpilot.galaxy.model.ModelRuntimeSourceMode
import io.carrotpilot.galaxy.model.ModelRuntimeStage
import io.carrotpilot.galaxy.runtime.RuntimeViewModel
import io.carrotpilot.galaxy.ui.DriveScreen

class MainActivity : ComponentActivity() {
  private companion object {
    const val AUTOMATION_ACTION = "io.carrotpilot.galaxy.AUTOMATION"
    const val AUTOMATION_EXTRA_COMMAND = "cmd"
    const val AUTOMATION_LOG_TAG = "CPG_AUTOMATION"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      val vm: RuntimeViewModel = viewModel()
      val state by vm.vehicleState.collectAsStateWithLifecycle()
      val sourceMode by vm.sourceMode.collectAsStateWithLifecycle()
      val fakeScenario by vm.fakeScenario.collectAsStateWithLifecycle()
      val fakeSuiteRunning by vm.fakeSuiteRunning.collectAsStateWithLifecycle()
      val fakeSuiteReport by vm.fakeSuiteReport.collectAsStateWithLifecycle()
      val modelRuntimeState by vm.modelRuntimeState.collectAsStateWithLifecycle()
      val modelSourceMode by vm.modelSourceMode.collectAsStateWithLifecycle()
      val modelScenario by vm.modelScenario.collectAsStateWithLifecycle()
      val modelSuiteRunning by vm.modelSuiteRunning.collectAsStateWithLifecycle()
      val modelSuiteReport by vm.modelSuiteReport.collectAsStateWithLifecycle()
      val lifecycleOwner = LocalLifecycleOwner.current
      val context = LocalContext.current
      val cameraFrameSource = remember { AndroidCameraFrameSource(context.applicationContext) }
      var resumeRealCameraOnForeground by remember { mutableStateOf(false) }
      val latestModelSourceMode by rememberUpdatedState(modelSourceMode)
      val latestModelStage by rememberUpdatedState(modelRuntimeState.stage)

      val startRealCameraSession = {
        vm.startModelRuntimeRealCameraSession(permissionGranted = true)
        cameraFrameSource.start(
          owner = lifecycleOwner,
          onFrame = vm::onRealCameraFrame,
          onError = { vm.onRealCameraSourceError() },
        )
      }
      val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
      ) { granted ->
        if (granted) {
          startRealCameraSession()
        } else {
          cameraFrameSource.stop()
          vm.markModelRuntimeCameraPermissionDenied()
        }
      }

      val startModelRuntime = {
        resumeRealCameraOnForeground = false
        when (modelSourceMode) {
          ModelRuntimeSourceMode.MOCK -> vm.startModelRuntimeMock()
          ModelRuntimeSourceMode.REAL_CAMERA -> {
            val granted = ContextCompat.checkSelfPermission(
              context,
              Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
              startRealCameraSession()
            } else {
              cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
          }
        }
      }
      val stopModelRuntime = {
        resumeRealCameraOnForeground = false
        cameraFrameSource.stop()
        when (modelSourceMode) {
          ModelRuntimeSourceMode.MOCK -> vm.stopModelRuntimeMock()
          ModelRuntimeSourceMode.REAL_CAMERA -> vm.stopModelRuntimeRealCameraSession()
        }
      }
      val resetModelRuntime = {
        resumeRealCameraOnForeground = false
        cameraFrameSource.stop()
        when (modelSourceMode) {
          ModelRuntimeSourceMode.MOCK -> vm.resetModelRuntimeMock()
          ModelRuntimeSourceMode.REAL_CAMERA -> vm.resetModelRuntimeRealCameraSession()
        }
      }
      val selectModelSourceMode = { mode: ModelRuntimeSourceMode ->
        resumeRealCameraOnForeground = false
        cameraFrameSource.stop()
        vm.stopModelRuntimeRealCameraSession()
        vm.selectModelSourceMode(mode)
      }
      val runModelSuite = {
        resumeRealCameraOnForeground = false
        cameraFrameSource.stop()
        vm.stopModelRuntimeRealCameraSession()
        vm.runModelSuite()
      }
      val latestStartModelRuntime by rememberUpdatedState(startModelRuntime)
      val latestStopModelRuntime by rememberUpdatedState(stopModelRuntime)
      val latestResetModelRuntime by rememberUpdatedState(resetModelRuntime)
      val latestSelectModelSourceMode by rememberUpdatedState(selectModelSourceMode)
      val latestRunModelSuite by rememberUpdatedState(runModelSuite)
      val latestDebugSnapshot by rememberUpdatedState(vm.buildDebugSnapshotText())

      DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
          when (event) {
            Lifecycle.Event.ON_STOP -> {
              if (latestModelSourceMode == ModelRuntimeSourceMode.REAL_CAMERA &&
                latestModelStage != ModelRuntimeStage.STOPPED
              ) {
                resumeRealCameraOnForeground = true
                cameraFrameSource.stop()
                vm.stopModelRuntimeRealCameraSession()
              }
            }

            Lifecycle.Event.ON_START -> {
              if (resumeRealCameraOnForeground &&
                latestModelSourceMode == ModelRuntimeSourceMode.REAL_CAMERA
              ) {
                val granted = ContextCompat.checkSelfPermission(
                  context,
                  Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                  startRealCameraSession()
                } else {
                  cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                resumeRealCameraOnForeground = false
              }
            }

            else -> Unit
          }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
          lifecycleOwner.lifecycle.removeObserver(observer)
          cameraFrameSource.stop()
          vm.stopModelRuntimeRealCameraSession()
        }
      }

      DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
          override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != AUTOMATION_ACTION) return
            val command = intent.getStringExtra(AUTOMATION_EXTRA_COMMAND)?.trim().orEmpty()
            when (command) {
              "MODEL_SOURCE_REAL_CAMERA" ->
                latestSelectModelSourceMode(ModelRuntimeSourceMode.REAL_CAMERA)

              "MODEL_SOURCE_MOCK" ->
                latestSelectModelSourceMode(ModelRuntimeSourceMode.MOCK)

              "START_G2" ->
                latestStartModelRuntime()

              "STOP_G2" ->
                latestStopModelRuntime()

              "RESET_G2" ->
                latestResetModelRuntime()

              "RUN_MODEL_SUITE" ->
                latestRunModelSuite()

              "DUMP" ->
                Log.i(AUTOMATION_LOG_TAG, latestDebugSnapshot)

              else ->
                Log.w(AUTOMATION_LOG_TAG, "unknown cmd='$command'")
            }
          }
        }
        val filter = IntentFilter(AUTOMATION_ACTION)
        ContextCompat.registerReceiver(
          context,
          receiver,
          filter,
          ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
          runCatching { context.unregisterReceiver(receiver) }
        }
      }

      DriveScreen(
        sourceMode = sourceMode,
        fakeScenario = fakeScenario,
        fakeSuiteRunning = fakeSuiteRunning,
        fakeSuiteReport = fakeSuiteReport,
        modelRuntimeState = modelRuntimeState,
        modelSourceMode = modelSourceMode,
        modelScenario = modelScenario,
        modelSuiteRunning = modelSuiteRunning,
        modelSuiteReport = modelSuiteReport,
        state = state,
        onSelectSourceMode = vm::selectSourceMode,
        onSelectFakeScenario = vm::selectFakeScenario,
        onSelectModelSourceMode = selectModelSourceMode,
        onSelectModelScenario = vm::selectModelScenario,
        onRunFakeSuite = vm::runFakeSuite,
        onStartModelRuntime = startModelRuntime,
        onStopModelRuntime = stopModelRuntime,
        onResetModelRuntime = resetModelRuntime,
        onRunModelSuite = runModelSuite,
        onStart = vm::startVehicleRecognition,
        onStop = vm::stopVehicleRecognition,
        onReset = vm::resetState,
      )
    }
  }
}
