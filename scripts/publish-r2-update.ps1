param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][string]$ExecutablePath,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Bucket,
    [Parameter(Mandatory = $true)][string]$Endpoint,
    [Parameter(Mandatory = $true)][string]$PublicBaseUrl,
    [string]$AwsProfile = ""
)

$ErrorActionPreference = "Stop"
$env:AWS_DEFAULT_REGION = "auto"

if ([string]::IsNullOrWhiteSpace($AwsProfile) -and (-not $env:AWS_ACCESS_KEY_ID -or -not $env:AWS_SECRET_ACCESS_KEY)) {
    throw "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must contain the R2 API credentials."
}

$awsCommonArgs = @("--endpoint-url", $Endpoint, "--region", "auto")
if (-not [string]::IsNullOrWhiteSpace($AwsProfile)) {
    $awsCommonArgs += @("--profile", $AwsProfile)
}

$resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path
$resolvedExecutable = (Resolve-Path -LiteralPath $ExecutablePath).Path
$fileName = [IO.Path]::GetFileName($resolvedMsi)
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedMsi).Hash.ToLowerInvariant()
$exeSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedExecutable).Hash.ToLowerInvariant()
$objectKey = "releases/$fileName"
$publicBase = $PublicBaseUrl.TrimEnd('/')
$existingManifestUrl = "$publicBase/latest.json"

try {
    $existingManifest = Invoke-RestMethod -Uri $existingManifestUrl -Method Get -Headers @{ "Cache-Control" = "no-cache" }
} catch {
    throw "Failed to read the existing R2 latest.json before merging the desktop update: $($_.Exception.Message)"
}

aws s3 cp $resolvedMsi "s3://$Bucket/$objectKey" @awsCommonArgs --content-type "application/x-msi" --cache-control "no-cache"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to upload MSI to R2 (aws exit code $LASTEXITCODE)."
}

$manifestData = [ordered]@{
    version = $Version
    url = "${publicBase}/${objectKey}?sha256=$sha256"
    sha256 = $sha256
    exeSha256 = $exeSha256
}
if ($null -ne $existingManifest.android) {
    $manifestData["android"] = $existingManifest.android
}
$manifest = $manifestData | ConvertTo-Json -Depth 20

$manifestPath = Join-Path $env:TEMP "archivetune-latest-$Version.json"
try {
    [IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
    aws s3 cp $manifestPath "s3://$Bucket/latest.json" @awsCommonArgs --content-type "application/json" --cache-control "no-cache"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload latest.json to R2 (aws exit code $LASTEXITCODE)."
    }
} finally {
    Remove-Item -LiteralPath $manifestPath -Force -ErrorAction SilentlyContinue
}

Write-Host "Published ArchiveTune $Version"
Write-Host "$publicBase/latest.json"
