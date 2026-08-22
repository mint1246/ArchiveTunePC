param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Bucket,
    [Parameter(Mandatory = $true)][string]$Endpoint,
    [Parameter(Mandatory = $true)][string]$PublicBaseUrl
)

$ErrorActionPreference = "Stop"
$env:AWS_DEFAULT_REGION = "auto"

if (-not $env:AWS_ACCESS_KEY_ID -or -not $env:AWS_SECRET_ACCESS_KEY) {
    throw "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must contain the R2 API credentials."
}

$resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path
$fileName = [IO.Path]::GetFileName($resolvedMsi)
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedMsi).Hash.ToLowerInvariant()
$objectKey = "releases/$fileName"
$publicBase = $PublicBaseUrl.TrimEnd('/')

aws s3 cp $resolvedMsi "s3://$Bucket/$objectKey" --endpoint-url $Endpoint --region auto --content-type "application/x-msi"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to upload MSI to R2 (aws exit code $LASTEXITCODE)."
}

$manifest = [ordered]@{
    version = $Version
    url = "$publicBase/$objectKey"
    sha256 = $sha256
} | ConvertTo-Json

$manifestPath = Join-Path $env:TEMP "archivetune-latest-$Version.json"
try {
    [IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
    aws s3 cp $manifestPath "s3://$Bucket/latest.json" --endpoint-url $Endpoint --region auto --content-type "application/json" --cache-control "no-cache"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload latest.json to R2 (aws exit code $LASTEXITCODE)."
    }
} finally {
    Remove-Item -LiteralPath $manifestPath -Force -ErrorAction SilentlyContinue
}

Write-Host "Published ArchiveTune $Version"
Write-Host "$publicBase/latest.json"