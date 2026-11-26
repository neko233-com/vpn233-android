param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Message
)

$ErrorActionPreference = 'Stop'
$ref = (& git symbolic-ref --quiet HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $ref) { throw 'A checked-out branch is required.' }
$parent = (& git rev-parse --verify $ref | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $parent) { throw 'Cannot resolve the current branch.' }
$tree = (& git write-tree | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $tree) { throw 'Cannot write the staged tree.' }
$commit = (& git commit-tree $tree -p $parent -m $Message | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $commit) { throw 'Cannot create the commit.' }
$content = (& git cat-file -p $commit | Out-String)
if ($LASTEXITCODE -ne 0 -or $content -match '(?im)^Co-authored-by:') { throw 'Commit contains a prohibited trailer.' }
& git update-ref -m "commit: $Message" $ref $commit $parent
if ($LASTEXITCODE -ne 0) { throw 'The branch moved while committing; no branch was overwritten.' }
Write-Host "Created commit $commit without co-author trailers."
