param(
  [Parameter(Mandatory = $true)]
  [string]$ModelPath,
  [string]$Package = "io.carrotpilot.galaxy",
  [string]$DeviceModelPath = "/sdcard/Android/data/io.carrotpilot.galaxy/files/models/comma_model.onnx"
)

$ErrorActionPreference = "Stop"

if (!(Test-Path -LiteralPath $ModelPath)) {
  throw "Model file not found: $ModelPath"
}

$deviceDir = $DeviceModelPath -replace "/[^/]+$", ""
$resolvedModelPath = (Resolve-Path -LiteralPath $ModelPath).Path

Write-Host "model_local=$resolvedModelPath"
Write-Host "model_device=$DeviceModelPath"

adb devices | Out-Null
adb shell "mkdir -p '$deviceDir'" | Out-Null
adb push "$resolvedModelPath" "$DeviceModelPath" | Out-Null
adb shell "ls -l $DeviceModelPath"

$probeScript = Join-Path $PSScriptRoot "run_g2_onnx_compat_probe.ps1"
if (!(Test-Path -LiteralPath $probeScript)) {
  throw "Probe script missing: $probeScript"
}

powershell -ExecutionPolicy Bypass -File $probeScript -Package $Package
