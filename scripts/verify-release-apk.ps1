param(
    [string]$Apk = (Join-Path $PSScriptRoot '../app/build/outputs/apk/release/app-release.apk'),
    [switch]$RequireClean
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$apkPath = (Resolve-Path -LiteralPath $Apk -ErrorAction Stop).Path
$revision = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') { throw 'Cannot determine the repository revision.' }
if ($RequireClean) {
    $dirty = & git -C $repo status --porcelain --untracked-files=normal
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read repository status.' }
    if ($dirty) { throw 'The repository has uncommitted changes; build a release from a clean committed revision.' }
}

$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $entry = $zip.GetEntry('META-INF/version-control-info.textproto')
    if (-not $entry) { throw 'APK does not contain version-control information.' }
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally {
    $zip.Dispose()
}

$matches = [regex]::Matches($metadata, 'revision:\s*"([0-9a-f]{40})"')
if ($matches.Count -ne 1 -or $matches[0].Groups[1].Value -ne $revision) {
    throw 'The APK embedded revision does not match the current repository commit.'
}
Write-Host "APK embedded revision matches $revision"
