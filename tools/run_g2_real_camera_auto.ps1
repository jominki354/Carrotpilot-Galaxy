param(
  [string]$Package = "io.carrotpilot.galaxy",
  [int]$ResumeBackgroundSeconds = 5,
  [int]$T3FirstWindowSeconds = 120,
  [int]$T3SecondWindowSeconds = 180
)

$ErrorActionPreference = "Stop"
$automationAction = "io.carrotpilot.galaxy.AUTOMATION"
$automationTag = "CPG_AUTOMATION"

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
$t3First = Get-AutoSnapshot
Print-Snapshot -Label "T3-2MIN" -Lines $t3First

Start-Sleep -Seconds $T3SecondWindowSeconds
$t3Second = Get-AutoSnapshot
Print-Snapshot -Label "T3-5MIN" -Lines $t3Second

Send-AutoCommand -Command "STOP_G2"
Start-Sleep -Seconds 1
$t3Stopped = Get-AutoSnapshot
Print-Snapshot -Label "T3-STOPPED" -Lines $t3Stopped

Write-Host ""
Write-Host "Auto run complete."
