param(
    [string]$Serial,
    [string]$Apk = (Join-Path $PSScriptRoot '../app/build/outputs/apk/release/app-release.apk')
)

$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb -ErrorAction Stop).Source
$apkPath = (Resolve-Path -LiteralPath $Apk -ErrorAction Stop).Path

if (-not $Serial) {
    $devices = @(& $adb devices | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
    })
    if ($devices.Count -ne 1) { throw 'Specify -Serial when zero or multiple ADB devices are connected.' }
    $Serial = $devices[0]
}

$sdkDir = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
$localProperties = Join-Path $PSScriptRoot '../local.properties'
if (-not $sdkDir -and (Test-Path -LiteralPath $localProperties)) {
    $line = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdkDir = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
if (-not $sdkDir) { throw 'Android SDK not found; set ANDROID_SDK_ROOT or configure local.properties.' }
$aapt = Get-ChildItem (Join-Path $sdkDir 'build-tools') -Filter aapt.exe -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $aapt) { throw 'Android SDK aapt not found.' }
$badging = (& $aapt dump badging $apkPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $badging -notmatch "(?m)^package: name='com\.neko233\.vpn233'") {
    throw 'The APK is not a valid vpn233 package.'
}

function Invoke-Agent([string]$Method) {
    # The response can contain subscription metadata; keep it in memory and never print it.
    $raw = (& $adb -s $Serial shell content call --uri 'content://com.neko233.vpn233.agent' --method $Method 2>&1 | Out-String)
    $match = [regex]::Match($raw, 'json=(\{.*\})\}\]', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { return $null }
    try { return ($match.Groups[1].Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

$installedPath = (& $adb -s $Serial shell pm path com.neko233.vpn233 2>&1 | Out-String)
$wasInstalled = $installedPath -match '(?m)^package:'
$previousStatus = if ($wasInstalled) { (Invoke-Agent 'status').vpnStatus } else { 'STOPPED' }
if ($wasInstalled -and $previousStatus -notin @('RUNNING', 'STARTING', 'STOPPED', 'STOPPING')) {
    throw 'Cannot determine the current VPN state; installation was not started.'
}
$shouldRun = $previousStatus -in @('RUNNING', 'STARTING')

Write-Host 'Installing the signed vpn233 release...'
$installOutput = (& $adb -s $Serial install -r --no-incremental $apkPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $installOutput -notmatch '\bSuccess\b') {
    $failure = [regex]::Match($installOutput, 'INSTALL_FAILED_[A-Z_]+')
    if ($failure.Success) { throw "ADB installation failed: $($failure.Value)" }
    throw 'ADB installation failed or was cancelled by the device.'
}

if (-not $wasInstalled) {
    Write-Host 'Installed. First-time VPN permission must be granted in Android.'
    $global:LASTEXITCODE = 0
    return
}

$stoppedObservations = 0
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    Start-Sleep -Seconds 2
    $current = Invoke-Agent 'status'
    if ($shouldRun -and $current.vpnStatus -eq 'RUNNING') {
        Write-Host 'Installed; the previously running VPN is active.'
        $global:LASTEXITCODE = 0
        return
    }
    if (-not $shouldRun -and $current.vpnStatus -eq 'STOPPED') {
        $stoppedObservations++
        if ($stoppedObservations -ge 2) {
            Write-Host 'Installed; the previously disconnected VPN remains stopped.'
            $global:LASTEXITCODE = 0
            return
        }
        continue
    }
    $stoppedObservations = 0
    if ($attempt -ge 1) {
        $result = if ($shouldRun) { Invoke-Agent 'start' } else { Invoke-Agent 'stop' }
        if ($result.error -eq 'VPN_PERMISSION_REQUIRED') { throw 'Android VPN permission is required before the VPN can be restored.' }
    }
}
throw 'The APK was installed, but the previous VPN state could not be restored automatically.'
