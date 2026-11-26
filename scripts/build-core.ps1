param(
    [string]$NdkDir = $env:ANDROID_NDK_HOME
)

$ErrorActionPreference = "Stop"
if (-not $NdkDir) {
    $sdkDir = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $null }
    $localProperties = Join-Path $PSScriptRoot "..\local.properties"
    if (-not $sdkDir -and (Test-Path -LiteralPath $localProperties)) {
        $line = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $sdkDir = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    if ($sdkDir) { $NdkDir = Join-Path $sdkDir 'ndk/27.2.12479018' }
}
if (-not $NdkDir -or -not (Test-Path -LiteralPath $NdkDir)) {
    throw 'Android NDK not found. Set ANDROID_NDK_HOME or configure the Android SDK in local.properties.'
}

$NdkBin = Join-Path $NdkDir "toolchains\llvm\prebuilt\windows-x86_64\bin"
$OutDir = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Push-Location (Join-Path $PSScriptRoot "..\go-core")
try {
    $env:GOOS = "android"
    $env:GOARCH = "arm64"
    $env:CGO_ENABLED = "1"
    $env:CC = Join-Path $NdkBin "aarch64-linux-android24-clang.cmd"
    $env:CXX = Join-Path $NdkBin "aarch64-linux-android24-clang++.cmd"

    go build -buildmode=c-shared `
        -tags "cmfa,with_gvisor,with_low_memory" `
        -trimpath -ldflags="-s -w" `
        -o (Join-Path $OutDir "libmihomo.so") .
    if ($LASTEXITCODE -ne 0) { throw "go build failed with exit code $LASTEXITCODE" }

    & (Join-Path $NdkBin "llvm-strip.exe") --strip-unneeded (Join-Path $OutDir "libmihomo.so")
    if ($LASTEXITCODE -ne 0) { throw "llvm-strip failed with exit code $LASTEXITCODE" }
    Remove-Item (Join-Path $OutDir "libmihomo.h") -ErrorAction SilentlyContinue
    Write-Host "Built libmihomo.so -> $((Get-Item (Join-Path $OutDir 'libmihomo.so')).Length) bytes"
} finally {
    Pop-Location
}
