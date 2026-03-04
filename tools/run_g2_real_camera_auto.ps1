param(
  [string]$Package = "io.carrotpilot.galaxy",
  [int]$ResumeBackgroundSeconds = 5,
  [int]$T3FirstWindowSeconds = 120,
  [int]$T3SecondWindowSeconds = 180
)

$ErrorActionPreference = "Stop"
$automationAction = "io.carrotpilot.galaxy.AUTOMATION"
$automationTag = "CPG_AUTOMATION"
$originalScreenOffTimeout = $null

function Send-AutoCommand {
  param([string]$Command)
  adb shell am broadcast -a $automationAction --es cmd $Command | Out-Null
}

function Get-AutoSnapshot {
  adb logcat -c | Out-Null
  Send-AutoCommand -Command "DUMP"
  Start-Sleep -Seconds 1
  $raw = adb logcat -d -s "$automationTag`:I" "*:S"
  $lines = $raw -split "`r?`n" |
    Where-Object { $_ -match "${automationTag}:" } |
    ForEach-Object { ($_ -replace ".*${automationTag}:\s?", "").TrimEnd() }
  return $lines
}

function Print-Snapshot {
  param(
    [string]$Label,
    [string[]]$Lines
  )
  Write-Host ""
  Write-Host "===== $Label ====="
  foreach ($line in $Lines) {
    Write-Host $line
  }
}

function ConvertTo-SnapshotMap {
  param([string[]]$Lines)
  $map = @{}
  foreach ($line in $Lines) {
    if ($line -match "^(?<key>[^=]+)=(?<value>.*)$") {
      $map[$matches["key"]] = $matches["value"]
    }
  }
  return $map
}

function Test-SnapshotHealthy {
  param([hashtable]$Map)
  return (
    $Map["g2_stage"] -eq "STABLE" -and
    $Map["g2_error"] -eq "NONE" -and
    $Map["g2_inference_ready"] -eq "true" -and
    $Map["g2_inference_failures"] -eq "0"
  )
}

function Print-BenchmarkSummary {
  param(
    [hashtable]$T2Running,
    [hashtable]$T2AfterResume,
    [hashtable]$T3First,
    [hashtable]$T3Second
  )
  $t2Pass = (Test-SnapshotHealthy -Map $T2Running) -and (Test-SnapshotHealthy -Map $T2AfterResume)
  $t3Pass = (Test-SnapshotHealthy -Map $T3First) -and (Test-SnapshotHealthy -Map $T3Second)
  $overall = if ($t2Pass -and $t3Pass) { "PASS" } else { "FAIL" }

  Write-Host ""
  Write-Host "===== BENCH-SUMMARY ====="
  Write-Host "bench_verdict=$overall"
  Write-Host "t2_verdict=$(if ($t2Pass) { "PASS" } else { "FAIL" })"
  Write-Host "t3_verdict=$(if ($t3Pass) { "PASS" } else { "FAIL" })"
  Write-Host "t3_2min_model_hz=$($T3First["g2_model_hz"])"
  Write-Host "t3_2min_p95_ms=$($T3First["g2_inference_latency_ms_p95"])"
  Write-Host "t3_2min_drop_perc=$($T3First["g2_frame_drop_perc"])"
  Write-Host "t3_5min_model_hz=$($T3Second["g2_model_hz"])"
  Write-Host "t3_5min_p95_ms=$($T3Second["g2_inference_latency_ms_p95"])"
  Write-Host "t3_5min_drop_perc=$($T3Second["g2_frame_drop_perc"])"
}

function Ensure-DeviceAwake {
  adb shell input keyevent 224 | Out-Null
  adb shell wm dismiss-keyguard | Out-Null
}

function Set-BenchmarkScreenTimeout {
  param([int]$TotalBenchmarkSeconds)
  $global:originalScreenOffTimeout = (adb shell settings get system screen_off_timeout).Trim()
  $targetMs = [Math]::Max(1800000, ($TotalBenchmarkSeconds + 120) * 1000)
  adb shell settings put system screen_off_timeout $targetMs | Out-Null
}

function Restore-ScreenTimeout {
  if ($null -ne $global:originalScreenOffTimeout -and $global:originalScreenOffTimeout -ne "") {
    adb shell settings put system screen_off_timeout $global:originalScreenOffTimeout | Out-Null
  }
}

try {
  adb devices | Out-Null
  Set-BenchmarkScreenTimeout -TotalBenchmarkSeconds ($ResumeBackgroundSeconds + $T3FirstWindowSeconds + $T3SecondWindowSeconds + 30)
  Ensure-DeviceAwake
  adb shell pm grant $Package android.permission.CAMERA | Out-Null
  adb shell am force-stop $Package | Out-Null
  adb shell am start -n "$Package/.MainActivity" | Out-Null
  Start-Sleep -Seconds 2

  Send-AutoCommand -Command "MODEL_SOURCE_REAL_CAMERA"
  Start-Sleep -Seconds 1
  Send-AutoCommand -Command "RESET_G2"
  Start-Sleep -Seconds 1
  Send-AutoCommand -Command "START_G2"
  Start-Sleep -Seconds 3

  $t2Running = Get-AutoSnapshot
  Print-Snapshot -Label "T2-RUNNING" -Lines $t2Running

  adb shell input keyevent 3 | Out-Null
  Start-Sleep -Seconds $ResumeBackgroundSeconds
  adb shell am start -n "$Package/.MainActivity" | Out-Null
  Start-Sleep -Seconds 3

  $t2AfterResume = Get-AutoSnapshot
  Print-Snapshot -Label "T2-AFTER-RESUME" -Lines $t2AfterResume

  Send-AutoCommand -Command "STOP_G2"
  Start-Sleep -Seconds 1
  $t2Stopped = Get-AutoSnapshot
  Print-Snapshot -Label "T2-STOPPED" -Lines $t2Stopped

  Send-AutoCommand -Command "MODEL_SOURCE_REAL_CAMERA"
  Start-Sleep -Seconds 1
  Send-AutoCommand -Command "RESET_G2"
  Start-Sleep -Seconds 1
  Send-AutoCommand -Command "START_G2"

  Start-Sleep -Seconds $T3FirstWindowSeconds
  Ensure-DeviceAwake
  $t3First = Get-AutoSnapshot
  Print-Snapshot -Label "T3-2MIN" -Lines $t3First

  Start-Sleep -Seconds $T3SecondWindowSeconds
  Ensure-DeviceAwake
  $t3Second = Get-AutoSnapshot
  Print-Snapshot -Label "T3-5MIN" -Lines $t3Second

  Send-AutoCommand -Command "STOP_G2"
  Start-Sleep -Seconds 1
  $t3Stopped = Get-AutoSnapshot
  Print-Snapshot -Label "T3-STOPPED" -Lines $t3Stopped

  Print-BenchmarkSummary `
    -T2Running (ConvertTo-SnapshotMap -Lines $t2Running) `
    -T2AfterResume (ConvertTo-SnapshotMap -Lines $t2AfterResume) `
    -T3First (ConvertTo-SnapshotMap -Lines $t3First) `
    -T3Second (ConvertTo-SnapshotMap -Lines $t3Second)

  Write-Host ""
  Write-Host "Auto run complete."
}
finally {
  Restore-ScreenTimeout
}
