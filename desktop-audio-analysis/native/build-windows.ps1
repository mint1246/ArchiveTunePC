param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir
)

$ErrorActionPreference = 'Stop'

$EssentiaCommit = '66a890f285d0e1988155c12d17a2068e406cdd90'
$EigenCommit = '3147391d946bb4b6c68edd901f2add6ac1f31f8c'
$Algorithms = @(
    'AutoCorrelation',
    'BeatTrackerDegara',
    'BinaryOperatorStream',
    'CartesianToPolar',
    'FFTK',
    'Flux',
    'FrameCutter',
    'HFC',
    'HPCP',
    'IFFTK',
    'IIR',
    'Key',
    'KeyExtractor',
    'LoudnessEBUR128',
    'LoudnessEBUR128Filter',
    'Magnitude',
    'Mean',
    'MelBands',
    'MovingAverage',
    'NoiseAdder',
    'OnsetDetection',
    'PeakDetection',
    'SpectralPeaks',
    'SpectralWhitening',
    'Spectrum',
    'StereoDemuxer',
    'TempoTapDegara',
    'TriangularBands',
    'UnaryOperatorStream',
    'Windowing'
) -join ','

$ProjectDir = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectDir 'build\native-deps\windows-x64'
$EssentiaDir = Join-Path $BuildRoot 'essentia'
$EigenDir = Join-Path $BuildRoot 'eigen'
$PrefixDir = Join-Path $BuildRoot 'prefix'
$PkgConfigDir = Join-Path $PrefixDir 'lib\pkgconfig'
$DllPath = Join-Path $OutputDir 'archivetune-audio-analysis.dll'
$BridgeSource = Join-Path $PSScriptRoot 'archivetune_audio_analysis.cpp'

function Find-Tool([string]$Name, [string[]]$Candidates) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $Candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw "Required native build tool '$Name' was not found. Install MSYS2 UCRT64/MinGW-w64 or put it on PATH."
}

$Git = Find-Tool 'git.exe' @('C:\Program Files\Git\cmd\git.exe')
$Python = Find-Tool 'python.exe' @(
    'C:\msys64\ucrt64\bin\python.exe',
    "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe",
    "$env:LOCALAPPDATA\Programs\Python\Python310\python.exe"
)
$Gxx = Find-Tool 'g++.exe' @('C:\msys64\ucrt64\bin\g++.exe', 'C:\msys64\mingw64\bin\g++.exe')
$PkgConfig = Find-Tool 'pkg-config.exe' @('C:\msys64\ucrt64\bin\pkg-config.exe', 'C:\msys64\mingw64\bin\pkg-config.exe')
$Strip = Find-Tool 'strip.exe' @('C:\msys64\ucrt64\bin\strip.exe', 'C:\msys64\mingw64\bin\strip.exe')

# Essentia contains tracked paths longer than the traditional Win32 MAX_PATH
# limit. Scope long-path handling to these dependency checkouts so native builds
# work from managed worktree directories without changing global Git settings.
$GitLongPaths = @('-c', 'core.longpaths=true')

New-Item -ItemType Directory -Force -Path $BuildRoot, $OutputDir, $PkgConfigDir | Out-Null

if (-not (Test-Path (Join-Path $EssentiaDir '.git'))) {
    & $Git @GitLongPaths clone --filter=blob:none --no-checkout https://github.com/MTG/essentia.git $EssentiaDir
    if ($LASTEXITCODE -ne 0) { throw 'Failed to clone Essentia.' }
}
& $Git @GitLongPaths -C $EssentiaDir fetch --depth 1 origin $EssentiaCommit
if ($LASTEXITCODE -ne 0) { throw 'Failed to fetch pinned Essentia revision.' }
& $Git @GitLongPaths -C $EssentiaDir checkout --force $EssentiaCommit
if ($LASTEXITCODE -ne 0) { throw 'Failed to checkout pinned Essentia revision.' }

if (-not (Test-Path (Join-Path $EigenDir '.git'))) {
    & $Git @GitLongPaths clone --filter=blob:none --no-checkout https://gitlab.com/libeigen/eigen.git $EigenDir
    if ($LASTEXITCODE -ne 0) { throw 'Failed to clone Eigen.' }
}
& $Git @GitLongPaths -C $EigenDir fetch --depth 1 origin $EigenCommit
if ($LASTEXITCODE -ne 0) { throw 'Failed to fetch pinned Eigen revision.' }
& $Git @GitLongPaths -C $EigenDir checkout --force $EigenCommit
if ($LASTEXITCODE -ne 0) { throw 'Failed to checkout pinned Eigen revision.' }

# Essentia's current Windows waf branch assumes MSVC and accesses old private
# std::vector internals that modern MSVC no longer exposes. MinGW is both smaller
# here and avoids that obsolete code path. Keep the patch confined to the build cache.
$Wscript = Join-Path $EssentiaDir 'wscript'
$WscriptText = Get-Content -Raw -LiteralPath $Wscript
$WscriptText = $WscriptText.Replace(
    "ctx.env.CXXFLAGS = ['-std=' + ctx.options.STD]  # c++11 by default",
    "ctx.env.CXXFLAGS = ['-std=' + ctx.options.STD, '-Os', '-ffunction-sections', '-fdata-sections', '-DM_PI=3.14159265358979323846', '-DM_LN2=0.69314718055994530942']  # ArchiveTune size build"
)
$WindowsFlagsPattern = "(?ms)    if sys\.platform != 'win32':\r?\n        # msvc does not support -pipe\r?\n        ctx\.env\.CXXFLAGS \+= \['-pipe', '-Wall'\]\r?\n    else:\r?\n        ctx\.env\.CXXFLAGS \+= \['-W2', '-EHsc'\]"
$WscriptText = [regex]::Replace($WscriptText, $WindowsFlagsPattern, "    ctx.env.CXXFLAGS += ['-pipe', '-Wall']")
$WscriptText = $WscriptText.Replace("ctx.env.CXXFLAGS += ['-O2']  # '-march=native' ] # '-msse3', '-mfpmath=sse' ]", "ctx.env.CXXFLAGS += []  # ArchiveTune uses -Os above")
[System.IO.File]::WriteAllText($Wscript, $WscriptText, [System.Text.UTF8Encoding]::new($false))

$EigenInclude = (Join-Path $EigenDir '').Replace('\', '/')
$PrefixUnix = $PrefixDir.Replace('\', '/')
$EigenPc = @(
    "prefix=$PrefixUnix",
    "includedir=$EigenInclude",
    '',
    'Name: Eigen3',
    'Description: Eigen headers',
    'Version: 3.4.0',
    'Cflags: -I${includedir}'
) -join "`n"
Set-Content -LiteralPath (Join-Path $PkgConfigDir 'eigen3.pc') -Value $EigenPc -Encoding ASCII

$env:PKG_CONFIG = $PkgConfig
$env:PKG_CONFIG_PATH = $PkgConfigDir
$env:PKG_CONFIG_LIBDIR = $PkgConfigDir
$env:PYTHONUTF8 = '1'

Push-Location $EssentiaDir
try {
    & $Python waf clean | Out-Null
    & $Python waf configure `
        --mode=release `
        --build-static `
        --lightweight= `
        --fft=KISS `
        "--include-algos=$Algorithms" `
        "--prefix=$PrefixDir" `
        --check-cxx-compiler=g++ `
        --check-c-compiler=gcc
    if ($LASTEXITCODE -ne 0) { throw 'Essentia configure failed.' }

    $WafJobs = if ($env:ARCHIVETUNE_ESSENTIA_JOBS) { [int]$env:ARCHIVETUNE_ESSENTIA_JOBS } else { [Math]::Min([Environment]::ProcessorCount, 4) }
    & $Python waf -j $WafJobs build
    if ($LASTEXITCODE -ne 0) { throw 'Essentia build failed.' }
} finally {
    Pop-Location
}

$EssentiaLib = Join-Path $EssentiaDir 'build\src\libessentia.a'
if (-not (Test-Path -LiteralPath $EssentiaLib)) { throw "Essentia static library was not produced: $EssentiaLib" }

& $Gxx `
    $BridgeSource `
    "-I$(Join-Path $EssentiaDir 'src')" `
    "-I$EigenDir" `
    $EssentiaLib `
    '-std=c++14' `
    '-Os' `
    '-ffunction-sections' `
    '-fdata-sections' `
    '-shared' `
    '-static' `
    '-Wl,--gc-sections' `
    '-Wl,--exclude-all-symbols' `
    '-Wl,--strip-all' `
    '-lwinmm' `
    '-lws2_32' `
    '-lole32' `
    '-luuid' `
    '-o' $DllPath
if ($LASTEXITCODE -ne 0) { throw 'Failed to link archivetune-audio-analysis.dll.' }

& $Strip --strip-unneeded $DllPath
if ($LASTEXITCODE -ne 0) { throw 'Failed to strip archivetune-audio-analysis.dll.' }

Copy-Item -Force (Join-Path $EssentiaDir 'COPYING.txt') (Join-Path $OutputDir 'ESSENTIA-AGPL-3.0.txt')

$size = (Get-Item -LiteralPath $DllPath).Length
Write-Host ("Built {0} ({1:N2} MiB)" -f $DllPath, ($size / 1MB))
