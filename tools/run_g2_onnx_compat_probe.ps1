param(
  [string]$Package = "io.carrotpilot.galaxy",
  [int]$WarmupSeconds = 4
)

$ErrorActionPreference = "Stop"
$automationAction = "io.carrotpilot.galaxy.AUTOMATION"
$automationTag = "CPG_AUTOMATION"

function Send-AutoCommand {
  param([string]$Command)
  adb shell am broadcast -a $automationAction --es cmd $Command | Out-Null
}

function Get-Snapshot {
  adb logcat -c | Out-Null
  Send-AutoCommand -Command "DUMP"
  Start-Sleep -Seconds 1
  $raw = adb logcat -d -s "$automationTag`:I" "*:S"
  $lines = $raw -split "`r?`n" |
    Where-Object { $_ -match "${automationTag}:" } |
    ForEach-Object { ($_ -replace ".*${automationTag}:\s?", "").TrimEnd() }

  $map = @{}
  foreach ($line in $lines) {
    if ($line -match "^(?<key>[^=]+)=(?<value>.*)$") {
      $map[$matches["key"]] = $matches["value"]
    }
  }
  return $map
}

adb devices | Out-Null
adb shell pm grant $Package android.permission.CAMERA | Out-Null
adb shell am force-stop $Package | Out-Null
adb shell am start -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 2

Send-AutoCommand -Command "MODEL_SOURCE_REAL_CAMERA"
Start-Sleep -Seconds 1
Send-AutoCommand -Command "RESET_G2"
Start-Sleep -Seconds 1
Send-AutoCommand -Command "START_G2"
Start-Sleep -Seconds $WarmupSeconds

$snapshot = Get-Snapshot
Send-AutoCommand -Command "STOP_G2"

$backend = $snapshot["g2_inference_backend"]
$ready = $snapshot["g2_inference_ready"]
$failuresText = if ($snapshot.ContainsKey("g2_inference_failures")) { $snapshot["g2_inference_failures"] } else { "0" }
$failures = [long]$failuresText
$stage = $snapshot["g2_stage"]
$g2Error = $snapshot["g2_error"]
$p95 = $snapshot["g2_inference_latency_ms_p95"]

Write-Host "backend=$backend"
Write-Host "ready=$ready"
Write-Host "failures=$failures"
Write-Host "stage=$stage"
Write-Host "error=$g2Error"
Write-Host "p95_ms=$p95"

if ($backend -eq "ONNX_RUNTIME_ANDROID[models/comma_model.onnx]" -and $ready -eq "true" -and $failures -eq 0) {
  Write-Host "compat_verdict=PASS_COMMA_ORT"
  exit 0
}

if ($backend -eq "ONNX_RUNTIME_ANDROID[models/mul_1.onnx]" -and $ready -eq "true" -and $failures -eq 0) {
  Write-Host "compat_verdict=PASS_PROBE_FALLBACK"
  exit 0
}

if ($backend -eq "ONNX_PLACEHOLDER") {
  Write-Host "compat_verdict=FAIL_ORT_INIT"
  exit 2
}

Write-Host "compat_verdict=UNKNOWN"
exit 3
