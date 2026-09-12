param(
    [string]$CommitMessage = "",
    [string]$ReleaseNotes = "",
    [int]$VersionCode = 0,
    [ValidateSet("stable", "canary")][string]$Channel = "stable",
    [string]$AwsProfile = "archivetune-r2",
    [string]$Bucket = "archivetune-updates",
    [string]$Endpoint = "https://b6d8d21b428522e1a0ab90b4f3c0bdea.r2.cloudflarestorage.com",
    [string]$PublicBaseUrl = "https://pub-90881360725b42cab24caebe4e55fa2d.r2.dev",
    [string]$ExpectedSignerSha256 = "6db8326a550861f0f96b9adcb706207d75ac4480de454fb8af95daa5d6c7f9d9",
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
        "--no-daemon",
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
    $buildTools = Get-BuildToolsDirectory
    $apksigner = Join-Path $buildTools "apksigner.bat"
    $aapt = Join-Path $buildTools "aapt.exe"
    $keystore = Join-Path $repoRoot "app\persistent-debug.keystore"
    if (-not (Test-Path -LiteralPath $keystore)) {
        throw "Expected updater signing keystore was not found at $keystore"
    }

    if ([string]::IsNullOrWhiteSpace($env:ARCHIVETUNE_APK_KS_PASS)) {
        $env:ARCHIVETUNE_APK_KS_PASS = "android"
    }
    if ([string]::IsNullOrWhiteSpace($env:ARCHIVETUNE_APK_KEY_PASS)) {
        $env:ARCHIVETUNE_APK_KEY_PASS = $env:ARCHIVETUNE_APK_KS_PASS
    }

    Invoke-Native -FilePath $apksigner -Arguments @(
        "sign",
        "--ks", $keystore,
        "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "env:ARCHIVETUNE_APK_KS_PASS",
        "--key-pass", "env:ARCHIVETUNE_APK_KEY_PASS",
        "--out", $signedApk,
        $unsignedApk.FullName
    )

    $verifyOutput = (& $apksigner verify --verbose --print-certs $signedApk 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed:`n$verifyOutput"
    }
    $signerMatch = [regex]::Match($verifyOutput, 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if (-not $signerMatch.Success) {
        throw "Could not read APK signer SHA-256 from apksigner output"
    }
    $signerSha256 = $signerMatch.Groups[1].Value.ToLowerInvariant()
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSignerSha256) -and $signerSha256 -ne $ExpectedSignerSha256.ToLowerInvariant()) {
        throw "APK signer mismatch. Expected $ExpectedSignerSha256, got $signerSha256"
    }

    $badgingOutput = & $aapt dump badging $signedApk 2>&1
    $aaptExitCode = $LASTEXITCODE
    if ($aaptExitCode -ne 0) {
        throw "aapt could not inspect the signed APK (exit code $aaptExitCode):`n$($badgingOutput | Out-String)"
    }
    $badging = ($badgingOutput | Select-Object -First 1 | Out-String).Trim()
    $builtCodeMatch = [regex]::Match($badging, "versionCode='(\d+)'")
    if (-not $builtCodeMatch.Success -or [int]$builtCodeMatch.Groups[1].Value -ne $nextCode) {
        throw "Built APK does not contain versionCode ${nextCode}: $badging"
    }

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
