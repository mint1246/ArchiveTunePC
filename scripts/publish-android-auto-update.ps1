param(
    [string]$CommitMessage = "",
    [string]$ReleaseNotes = "",
    [int]$VersionCode = 0,
    [ValidateSet("stable", "canary")][string]$Channel = "stable",
    [string]$AwsProfile = "archivetune-r2",
    [string]$Bucket = "archivetune-updates",
    [string]$Endpoint = $(if ($env:R2_ENDPOINT) { $env:R2_ENDPOINT } else { "" }),
    [string]$PublicBaseUrl = "https://pub-90881360725b42cab24caebe4e55fa2d.r2.dev",
    [string]$ExpectedSignerSha256 = "cd0f69369f343d3fa9e66fd353953a53c789bc16bf3374a339c33389cc82df81",
    [switch]$SkipPush
)

$ErrorActionPreference = "Stop"

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

function Get-BuildToolsDirectory {
    $sdkRoot = if ($env:ANDROID_HOME) {
        $env:ANDROID_HOME
    } elseif ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_SDK_ROOT
    } else {
        Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }

    $root = Join-Path $sdkRoot "build-tools"
    if (-not (Test-Path -LiteralPath $root)) {
        throw "Android build-tools directory was not found at $root"
    }

    $candidate =
        Get-ChildItem -LiteralPath $root -Directory |
        Where-Object { $_.Name -match '^\d+(?:\.\d+){1,3}$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1

    if (-not $candidate) {
        throw "No Android build-tools installation was found under $root"
    }
    return $candidate.FullName
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradleFile = Join-Path $repoRoot "app\build.gradle.kts"
$artifactDirectory = Join-Path $repoRoot "release\android"
$publisher = Join-Path $PSScriptRoot "publish-r2-android-update.ps1"
$temporaryManifest = Join-Path $env:TEMP "archivetune-latest-publish.json"

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldKeystorePassword = $env:ARCHIVETUNE_APK_KS_PASS
$oldKeyPassword = $env:ARCHIVETUNE_APK_KEY_PASS

Push-Location $repoRoot
try {
    if (-not (Test-Path -LiteralPath $gradleFile)) {
        throw "Could not find app/build.gradle.kts"
    }
    if (-not (Test-Path -LiteralPath $publisher)) {
        throw "Could not find scripts/publish-r2-android-update.ps1"
    }
    if ([string]::IsNullOrWhiteSpace($Endpoint)) {
        $localEndpointFile = Join-Path $repoRoot ".secrets/android-signing/r2-endpoint.txt"
        if (Test-Path -LiteralPath $localEndpointFile) {
            $Endpoint = [IO.File]::ReadAllText($localEndpointFile).Trim()
        } else {
            throw "R2 endpoint is required via -Endpoint, R2_ENDPOINT env var, or .secrets/android-signing/r2-endpoint.txt"
        }
    }

    $branch = (git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($branch)) {
        throw "Android auto-update publishing requires a normal checked-out Git branch"
    }

    $gradleText = [IO.File]::ReadAllText($gradleFile)
    $currentCodeMatch = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
    $versionNameMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
    if (-not $currentCodeMatch.Success -or -not $versionNameMatch.Success) {
        throw "Could not determine Android versionCode/versionName from app/build.gradle.kts"
    }

    $currentCode = [int]$currentCodeMatch.Groups[1].Value
    $versionName = $versionNameMatch.Groups[1].Value
    $explicitVersionCode = $VersionCode -gt 0
    $nextCode = if ($explicitVersionCode) { $VersionCode } else { $currentCode + 1 }
    if ($explicitVersionCode -and $nextCode -lt $currentCode) {
        throw "VersionCode cannot go backwards from $currentCode (requested $nextCode)"
    }

    $gradleText = [regex]::Replace(
        $gradleText,
        'versionCode\s*=\s*\d+',
        "versionCode = $nextCode",
        1
    )
    [IO.File]::WriteAllText($gradleFile, $gradleText, [Text.UTF8Encoding]::new($false))

    Invoke-Native -FilePath git -Arguments @("diff", "--check")
    Invoke-Native -FilePath git -Arguments @("add", "-A")

    $message = if ([string]::IsNullOrWhiteSpace($CommitMessage)) {
        "chore(android): publish auto-update $versionName ($nextCode)"
    } else {
        $CommitMessage.Trim()
    }
    Invoke-Native -FilePath git -Arguments @("commit", "-m", $message)
    $commit = (git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Failed to read committed HEAD" }

    $androidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path -LiteralPath (Join-Path $androidStudioJbr "bin\java.exe")) {
        $env:JAVA_HOME = $androidStudioJbr
        $env:Path = "$androidStudioJbr\bin;$oldPath"
    }

    Write-Host "Building Android $versionName ($nextCode) from $commit"
    Invoke-Native -FilePath ".\gradlew.bat" -Arguments @(
        ":app:assembleGmsMobileArm64Release",
        "--console=plain"
    )

    $unsignedApk =
        Get-ChildItem "app\build\outputs\apk\gmsMobileArm64\release" -Filter "*.apk" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $unsignedApk) {
        throw "Gradle completed but no gmsMobileArm64 release APK was found"
    }

    New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
    $signedApk = Join-Path $artifactDirectory "ArchiveTune-$versionName-gms-mobile-arm64.apk"
    $signerScript = Join-Path $PSScriptRoot "sign-android-release-apk.ps1"
    if (-not (Test-Path -LiteralPath $signerScript)) {
        throw "Expected Android release signing script was not found at $signerScript"
    }

    & $signerScript `
        -UnsignedApkPath $unsignedApk.FullName `
        -OutputApkPath $signedApk `
        -ExpectedVersionCode $nextCode `
        -ExpectedSignerSha256 $ExpectedSignerSha256
    if ($LASTEXITCODE -ne 0) {
        throw "Android release signing failed with exit code $LASTEXITCODE"
    }
    $signerSha256 = $ExpectedSignerSha256.ToLowerInvariant()

    $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $signedApk).Length

    if (-not $SkipPush) {
        Invoke-Native -FilePath git -Arguments @("push", "origin", $branch)
    }

    $notes = if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) { $message } else { $ReleaseNotes.Trim() }
    & $publisher `
        -ApkPath $signedApk `
        -Version $versionName `
        -VersionCode $nextCode `
        -Distribution "gms" `
        -Device "mobile" `
        -Architecture "arm64" `
        -Channel $Channel `
        -ReleaseNotes $notes `
        -Bucket $Bucket `
        -Endpoint $Endpoint `
        -PublicBaseUrl $PublicBaseUrl `
        -ManifestPath $temporaryManifest `
        -AwsProfile $AwsProfile
    if ($LASTEXITCODE -ne 0) {
        throw "R2 publisher failed with exit code $LASTEXITCODE"
    }

    $cacheBust = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $live = Invoke-RestMethod -Uri "$($PublicBaseUrl.TrimEnd('/'))/latest.json?t=$cacheBust" -Headers @{ "Cache-Control" = "no-cache" }
    $liveChannel = $live.android.$Channel
    $liveArtifact = $liveChannel.artifacts.'gms-mobile-arm64'
    if ([int]$liveChannel.versionCode -ne $nextCode) {
        throw "Live manifest verification failed: expected versionCode $nextCode, got $($liveChannel.versionCode)"
    }
    if ($liveArtifact.sha256.ToLowerInvariant() -ne $sha256) {
        throw "Live manifest SHA-256 does not match the signed APK"
    }
    if ([long]$liveArtifact.size -ne $size) {
        throw "Live manifest APK size does not match the signed APK"
    }

    $aliasName = if ($Channel -eq "stable") { "latest" } else { "canary" }
    $aliasKey = "releases/android/$aliasName-gms-mobile-arm64.apk"
    $headJson = & aws s3api head-object `
        --bucket $Bucket `
        --key $aliasKey `
        --endpoint-url $Endpoint `
        --region auto `
        --profile $AwsProfile `
        --output json
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to verify the published R2 APK object"
    }
    $head = $headJson | ConvertFrom-Json
    if ([long]$head.ContentLength -ne $size) {
        throw "Published R2 APK size mismatch: expected $size, got $($head.ContentLength)"
    }

    Write-Host ""
    Write-Host "ArchiveTune Android auto-update published successfully"
    Write-Host "Commit:      $commit"
    Write-Host "Version:     $versionName ($nextCode)"
    Write-Host "Channel:     $Channel"
    Write-Host "SHA-256:     $sha256"
    Write-Host "Signer:      $signerSha256"
    Write-Host "APK bytes:   $size"
    Write-Host "Manifest:    $($PublicBaseUrl.TrimEnd('/'))/latest.json"
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
    $env:ARCHIVETUNE_APK_KS_PASS = $oldKeystorePassword
    $env:ARCHIVETUNE_APK_KEY_PASS = $oldKeyPassword
    Pop-Location
}
