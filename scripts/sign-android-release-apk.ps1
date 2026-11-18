param(
    [Parameter(Mandatory = $true)][string]$UnsignedApkPath,
    [Parameter(Mandatory = $true)][string]$OutputApkPath,
    [int]$ExpectedVersionCode = 0,
    [string]$ExpectedSignerSha256 = "",
    [int]$RotationMinSdkVersion = 28
)

$ErrorActionPreference = "Stop"

function Get-AndroidBuildToolsDir {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        "/usr/local/lib/android/sdk",
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android/Sdk" } else { $null }),
        $(if ($env:USERPROFILE) { Join-Path $env:USERPROFILE "Android/Sdk" } else { $null })
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($sdkRoot in $candidates) {
        $btRoot = Join-Path $sdkRoot "build-tools"
        if (Test-Path -LiteralPath $btRoot) {
            $latest =
                Get-ChildItem -LiteralPath $btRoot -Directory |
                Where-Object { $_.Name -match '^\d+(?:\.\d+){1,3}$' } |
                Sort-Object { [version]$_.Name } -Descending |
                Select-Object -First 1
            if ($latest) {
                return $latest.FullName
            }
        }
    }
    throw "Android SDK build-tools directory was not found via ANDROID_HOME / ANDROID_SDK_ROOT."
}

$isWin = ($env:OS -eq "Windows_NT") -or ([System.Environment]::OSVersion.Platform -eq "Win32NT")
$buildTools = Get-AndroidBuildToolsDir
$apksignerName = if ($isWin) { "apksigner.bat" } else { "apksigner" }
$aaptName = if ($isWin) { "aapt.exe" } else { "aapt" }
$apksigner = Join-Path $buildTools $apksignerName
$aapt = Join-Path $buildTools $aaptName
if (-not (Test-Path -LiteralPath $apksigner)) {
    throw "apksigner was not found at $apksigner"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$localSecretsDir = Join-Path $repoRoot ".secrets/android-signing"
$tempRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$workDir = Join-Path $tempRoot ("archivetune-apk-sign-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

$savedEnv = @{
    AT_REL_KS_PASS  = $env:AT_REL_KS_PASS
    AT_REL_KEY_PASS = $env:AT_REL_KEY_PASS
    AT_LEG_KS_PASS  = $env:AT_LEG_KS_PASS
}

try {
    $resolvedInput = (Resolve-Path -LiteralPath $UnsignedApkPath).Path
    $outParent = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputApkPath))
    if ($outParent) {
        New-Item -ItemType Directory -Force -Path $outParent | Out-Null
    }

    $releaseKsPath = $null
    $releaseAlias = $env:ANDROID_RELEASE_KEY_ALIAS
    $releaseStorePass = $env:ANDROID_RELEASE_STORE_PASSWORD
    $releaseKeyPass = $env:ANDROID_RELEASE_KEY_PASSWORD

    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_RELEASE_KEYSTORE_BASE64)) {
        $releaseKsPath = Join-Path $workDir "release.keystore"
        [System.IO.File]::WriteAllBytes($releaseKsPath, [Convert]::FromBase64String($env:ANDROID_RELEASE_KEYSTORE_BASE64.Trim()))
    } elseif (Test-Path -LiteralPath (Join-Path $localSecretsDir "archivetune-release.keystore")) {
        $releaseKsPath = Join-Path $localSecretsDir "archivetune-release.keystore"
        if ([string]::IsNullOrWhiteSpace($releaseAlias)) {
            $releaseAlias = "archivetune-release"
        }
        $localPassFile = Join-Path $localSecretsDir "release-store-password.txt"
        if ([string]::IsNullOrWhiteSpace($releaseStorePass) -and (Test-Path -LiteralPath $localPassFile)) {
            $releaseStorePass = [System.IO.File]::ReadAllText($localPassFile).Trim()
        }
    } else {
        throw "No Android release keystore configured. Set ANDROID_RELEASE_KEYSTORE_BASE64 (CI) or populate .secrets/android-signing/ locally."
    }

    if ([string]::IsNullOrWhiteSpace($releaseKeyPass)) {
        $releaseKeyPass = $releaseStorePass
    }
    if ([string]::IsNullOrWhiteSpace($releaseAlias) -or [string]::IsNullOrWhiteSpace($releaseStorePass)) {
        throw "ANDROID_RELEASE_KEY_ALIAS and ANDROID_RELEASE_STORE_PASSWORD are required."
    }

    $legacyKsPath = $null
    $lineagePath = $null
    $legacyAlias = $env:ANDROID_LEGACY_KEY_ALIAS
    $legacyStorePass = $env:ANDROID_LEGACY_STORE_PASSWORD

    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_LEGACY_KEYSTORE_BASE64) -and -not [string]::IsNullOrWhiteSpace($env:ANDROID_SIGNING_LINEAGE_BASE64)) {
        $legacyKsPath = Join-Path $workDir "legacy.keystore"
        $lineagePath = Join-Path $workDir "signing.lineage"
        [System.IO.File]::WriteAllBytes($legacyKsPath, [Convert]::FromBase64String($env:ANDROID_LEGACY_KEYSTORE_BASE64.Trim()))
        [System.IO.File]::WriteAllBytes($lineagePath, [Convert]::FromBase64String($env:ANDROID_SIGNING_LINEAGE_BASE64.Trim()))
    } elseif (
        (Test-Path -LiteralPath (Join-Path $localSecretsDir "legacy-persistent-debug.keystore")) -and
        (Test-Path -LiteralPath (Join-Path $localSecretsDir "archivetune-release.lineage"))
    ) {
        $legacyKsPath = Join-Path $localSecretsDir "legacy-persistent-debug.keystore"
        $lineagePath = Join-Path $localSecretsDir "archivetune-release.lineage"
        if ([string]::IsNullOrWhiteSpace($legacyAlias)) {
            $legacyAlias = "androiddebugkey"
        }
        if ([string]::IsNullOrWhiteSpace($legacyStorePass)) {
            $legacyStorePass = "android"
        }
    }

    if ([string]::IsNullOrWhiteSpace($ExpectedSignerSha256)) {
        if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_EXPECTED_SIGNER_SHA256)) {
            $ExpectedSignerSha256 = $env:ANDROID_EXPECTED_SIGNER_SHA256.Trim()
        } elseif (Test-Path -LiteralPath (Join-Path $localSecretsDir "expected-signer-sha256.txt")) {
            $ExpectedSignerSha256 = [System.IO.File]::ReadAllText((Join-Path $localSecretsDir "expected-signer-sha256.txt")).Trim()
        }
    }

    $env:AT_REL_KS_PASS = $releaseStorePass
    $env:AT_REL_KEY_PASS = $releaseKeyPass

    $signArgs = @("sign")
    if ($legacyKsPath -and $lineagePath) {
        if ([string]::IsNullOrWhiteSpace($legacyAlias) -or [string]::IsNullOrWhiteSpace($legacyStorePass)) {
            throw "Legacy keystore alias and password are required when signing with a v3 rotation lineage."
        }
        $env:AT_LEG_KS_PASS = $legacyStorePass
        $signArgs += @(
            "--ks", $legacyKsPath,
            "--ks-key-alias", $legacyAlias,
            "--ks-pass", "env:AT_LEG_KS_PASS",
            "--key-pass", "env:AT_LEG_KS_PASS",
            "--next-signer"
        )
    }
    $signArgs += @(
        "--ks", $releaseKsPath,
        "--ks-key-alias", $releaseAlias,
        "--ks-pass", "env:AT_REL_KS_PASS",
        "--key-pass", "env:AT_REL_KEY_PASS"
    )
    if ($lineagePath) {
        $signArgs += @(
            "--lineage", $lineagePath,
            "--rotation-min-sdk-version", "$RotationMinSdkVersion"
        )
    }
    $signArgs += @("--out", $OutputApkPath, $resolvedInput)

    & $apksigner @signArgs
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner sign failed with exit code $LASTEXITCODE"
    }

    $verifyOutput = (& $apksigner verify --verbose --print-certs $OutputApkPath 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed:`n$verifyOutput"
    }

    $signerMatches = [regex]::Matches($verifyOutput, 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if ($signerMatches.Count -eq 0) {
        throw "Could not read APK signer SHA-256 from apksigner output"
    }
    $digests = @($signerMatches | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Select-Object -Unique)
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSignerSha256)) {
        $expectedLower = $ExpectedSignerSha256.ToLowerInvariant()
        if ($digests -notcontains $expectedLower) {
            throw "APK signer mismatch. Expected $expectedLower among [$($digests -join ', ')]"
        }
    }

    if ($ExpectedVersionCode -gt 0 -and (Test-Path -LiteralPath $aapt)) {
        $badgingOutput = & $aapt dump badging $OutputApkPath 2>&1
        $aaptExitCode = $LASTEXITCODE
        if ($aaptExitCode -ne 0) {
            throw "aapt could not inspect the signed APK (exit code $aaptExitCode):`n$($badgingOutput | Out-String)"
        }
        $badging = ($badgingOutput | Select-Object -First 1 | Out-String).Trim()
        $builtCodeMatch = [regex]::Match($badging, "versionCode='(\d+)'")
        if (-not $builtCodeMatch.Success -or [int]$builtCodeMatch.Groups[1].Value -ne $ExpectedVersionCode) {
            throw "Built APK does not contain versionCode ${ExpectedVersionCode}: $badging"
        }
    }

    Write-Host "Signed and verified Android release APK: $OutputApkPath"
    Write-Host "Active signer SHA-256: $($digests[0])"
} finally {
    foreach ($k in $savedEnv.Keys) {
        if ($null -eq $savedEnv[$k]) {
            Remove-Item "Env:$k" -ErrorAction SilentlyContinue
        } else {
            Set-Item "Env:$k" -Value $savedEnv[$k]
        }
    }
    if (Test-Path -LiteralPath $workDir) {
        Remove-Item -LiteralPath $workDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
