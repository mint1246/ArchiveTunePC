param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [ValidateSet("stable", "canary")][string]$Channel = "stable",
    [string]$Distribution = "gms",
    [string]$Device = "mobile",
    [string]$Architecture = "arm64",
    [string]$ReleaseNotes = "",
    [Parameter(Mandatory = $true)][string]$Bucket,
    [Parameter(Mandatory = $true)][string]$Endpoint,
    [Parameter(Mandatory = $true)][string]$PublicBaseUrl,
    [string]$ManifestPath = (Join-Path $PSScriptRoot "..\release\latest.json"),
    [string]$AwsProfile = "",
    [switch]$SkipUpload
)

$ErrorActionPreference = "Stop"
$env:AWS_DEFAULT_REGION = "auto"

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedApk).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $resolvedApk).Length
$artifactKey = "$Distribution-$Device-$Architecture"
$publicBase = $PublicBaseUrl.TrimEnd('/')
$versionedObjectKey = "releases/android/ArchiveTune-$Version-$artifactKey.apk"
$aliasName = if ($Channel -eq "stable") { "latest" } else { "canary" }
$aliasObjectKey = "releases/android/$aliasName-$artifactKey.apk"
$downloadUrl = "${publicBase}/${versionedObjectKey}?sha256=$sha256"
$publishedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$notes = if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) { "ArchiveTune $Version" } else { $ReleaseNotes.Trim() }

$manifestMap = [ordered]@{}
$existingManifest = $null
$existingManifestUrl = "$publicBase/latest.json"

try {
    $existingManifest = Invoke-RestMethod -Uri $existingManifestUrl -Method Get -Headers @{ "Cache-Control" = "no-cache" }
} catch {
    if (-not $SkipUpload) {
        throw "Failed to read the live R2 latest.json before publishing Android metadata: $($_.Exception.Message)"
    } elseif (Test-Path -LiteralPath $ManifestPath) {
        $existingManifest = Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json
    } else {
        throw "Failed to read the existing R2 latest.json and no local manifest is available to merge: $($_.Exception.Message)"
    }
}

foreach ($property in $existingManifest.PSObject.Properties) {
    $manifestMap[$property.Name] = $property.Value
}

$androidMap = [ordered]@{ schema = 1 }
$existingAndroid = $existingManifest.android
if ($null -ne $existingAndroid) {
    foreach ($property in $existingAndroid.PSObject.Properties) {
        if ($property.Name -ne "schema") {
            $androidMap[$property.Name] = $property.Value
        }
    }
}

$artifacts = [ordered]@{}
$existingChannel = if ($null -ne $existingAndroid) { $existingAndroid.$Channel } else { $null }
if (
    $null -ne $existingChannel -and
    $existingChannel.version -eq $Version -and
    $null -ne $existingChannel.artifacts
) {
    foreach ($property in $existingChannel.artifacts.PSObject.Properties) {
        $artifacts[$property.Name] = $property.Value
    }
}
$artifacts[$artifactKey] = [ordered]@{
    url = $downloadUrl
    sha256 = $sha256
    size = $size
}

$androidMap[$Channel] = [ordered]@{
    version = $Version
    versionCode = $VersionCode
    name = "ArchiveTune $Version"
    publishedAt = $publishedAt
    notes = $notes
    artifacts = $artifacts
}
if (-not $androidMap.Contains("stable")) {
    $androidMap["stable"] = $null
}
if (-not $androidMap.Contains("canary")) {
    $androidMap["canary"] = $null
}
$manifestMap["android"] = $androidMap

$manifestDirectory = Split-Path -Parent $ManifestPath
if ($manifestDirectory) {
    New-Item -ItemType Directory -Path $manifestDirectory -Force | Out-Null
}
$manifestJson = $manifestMap | ConvertTo-Json -Depth 30
[IO.File]::WriteAllText($ManifestPath, $manifestJson + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))

if (-not $SkipUpload) {
    if ([string]::IsNullOrWhiteSpace($AwsProfile) -and (-not $env:AWS_ACCESS_KEY_ID -or -not $env:AWS_SECRET_ACCESS_KEY)) {
        throw "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must contain the R2 API credentials."
    }

    $awsCommonArgs = @("--endpoint-url", $Endpoint, "--region", "auto")
    if (-not [string]::IsNullOrWhiteSpace($AwsProfile)) {
        $awsCommonArgs += @("--profile", $AwsProfile)
    }

    aws s3 cp $resolvedApk "s3://$Bucket/$versionedObjectKey" @awsCommonArgs --content-type "application/vnd.android.package-archive" --cache-control "no-cache"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload the versioned Android APK to R2 (aws exit code $LASTEXITCODE)."
    }

    aws s3 cp $resolvedApk "s3://$Bucket/$aliasObjectKey" @awsCommonArgs --content-type "application/vnd.android.package-archive" --cache-control "no-cache"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload the Android update alias to R2 (aws exit code $LASTEXITCODE)."
    }

    aws s3 cp $ManifestPath "s3://$Bucket/latest.json" @awsCommonArgs --content-type "application/json" --cache-control "no-cache"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload the merged latest.json to R2 (aws exit code $LASTEXITCODE)."
    }
}

Write-Host "Prepared ArchiveTune Android $Channel $Version ($artifactKey)"
Write-Host "APK SHA-256: $sha256"
Write-Host "Manifest: $ManifestPath"
if (-not $SkipUpload) {
    Write-Host "$publicBase/latest.json"
}
