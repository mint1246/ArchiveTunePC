param(
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot "..\build\model-artifacts\models"),
    [Parameter(Mandatory = $true)][string]$Bucket,
    [Parameter(Mandatory = $true)][string]$Endpoint
)

$ErrorActionPreference = "Stop"
$env:AWS_DEFAULT_REGION = "auto"

if (-not $env:AWS_ACCESS_KEY_ID -or -not $env:AWS_SECRET_ACCESS_KEY) {
    throw "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must contain the R2 API credentials."
}

$artifactRoot = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
$manifestPath = Join-Path $artifactRoot "manifest.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$chords = $manifest.models.chords

$files = @(
    [pscustomobject]@{ File = $chords.file; Size = [long]$chords.size; Sha256 = [string]$chords.sha256; ContentType = "application/octet-stream" },
    [pscustomobject]@{ File = $chords.assets.config.file; Size = [long]$chords.assets.config.size; Sha256 = [string]$chords.assets.config.sha256; ContentType = "application/json" },
    [pscustomobject]@{ File = $chords.assets.cqtPlan.file; Size = [long]$chords.assets.cqtPlan.size; Sha256 = [string]$chords.assets.cqtPlan.sha256; ContentType = "application/octet-stream" },
    [pscustomobject]@{ File = $chords.assets.notice.file; Size = [long]$chords.assets.notice.size; Sha256 = [string]$chords.assets.notice.sha256; ContentType = "text/markdown; charset=utf-8" }
)

foreach ($entry in $files) {
    $path = Join-Path $artifactRoot $entry.File
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing model artifact: $path"
    }
    $actualSize = (Get-Item -LiteralPath $path).Length
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
    if ($actualSize -ne $entry.Size -or $actualHash -ne $entry.Sha256) {
        throw "Pinned metadata mismatch for $($entry.File)"
    }
    aws s3 cp $path "s3://$Bucket/models/$($entry.File)" --endpoint-url $Endpoint --region auto --content-type $entry.ContentType --cache-control "public,max-age=31536000,immutable"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload $($entry.File) to R2."
    }
}

# Manifest goes last so clients can never observe references to model files that have not landed.
aws s3 cp $manifestPath "s3://$Bucket/models/manifest.json" --endpoint-url $Endpoint --region auto --content-type "application/json" --cache-control "no-cache"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to upload models/manifest.json to R2."
}

Write-Host "Published ArchiveTune chord model bundle and manifest."
