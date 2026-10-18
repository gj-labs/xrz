# Run with PowerShell 7 from any directory. Targets only the registered S25.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
. "$root/scripts/Enter-Dev.ps1"
$device = (Get-Content "$root/config/devices.local.json" -Raw | ConvertFrom-Json).s25
if (-not $device.serial) { throw 'Register the s25 target first' }
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
function Invoke-S25([string[]]$AdbArgs) {
    $output = & $adb -s $device.serial @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "S25 ADB operation failed: $output" }
    $output
}
$soc = (Invoke-S25 @('shell','getprop','ro.soc.model')).Trim()
if ($soc -notmatch '^SM8750(?:-.*)?$') { throw 'Registered target is not the expected S25 SoC' }
$modelDir = "$root/models/esm2-8m/s25/bindlab-portable"
$manifest = Get-Content "$modelDir/manifest.json" -Raw | ConvertFrom-Json
$pte = "$modelDir/esm2_8M_portable-cpu.pte"
if ((Get-FileHash $pte -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.pte_sha256) { throw 'Model hash mismatch' }
if ($manifest.executorch -ne '1.5.1' -or $manifest.backend -ne 'portable-cpu') { throw 'Unexpected export runtime/backend' }
Invoke-S25 @('install','-r',"$PSScriptRoot/android/app-phone/build/outputs/apk/debug/app-phone-debug.apk")
Invoke-S25 @('install','-r',"$PSScriptRoot/android/app-phone/build/outputs/apk/androidTest/debug/app-phone-debug-androidTest.apk")
$pkg = 'com.bindlab.phone.custody'
Invoke-S25 @('shell','am','start','-n',"$pkg/com.bindlab.phone.MainActivity")
$remote = "/sdcard/Android/data/$pkg/files/models"
Invoke-S25 @('shell','mkdir','-p',$remote)
Invoke-S25 @('push',$pte,"$remote/esm2_8M_portable-cpu.pte")
Invoke-S25 @('push',"$modelDir/manifest.json","$remote/manifest.json")
Invoke-S25 @('reverse','tcp:8011','tcp:8011')
$result = Invoke-S25 @('shell','am','instrument','-w',"$pkg.test/com.bindlab.phone.EmbeddingVerification")
$result | Set-Content "$root/logs/bindlab-s25-verification.local.txt"
$result
if (($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1' -or ($result -join "`n") -notmatch 'PASS:') { throw 'Device verification failed; see local log' }
Invoke-S25 @('shell','am','start','-n',"$pkg/com.bindlab.phone.MainActivity")
