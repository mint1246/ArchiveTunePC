param(
    [Parameter(Mandatory = $true)][string]$SourceDir,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [Parameter(Mandatory = $true)][string]$WixDir,
    [Parameter(Mandatory = $true)][string]$IconPath,
    [Parameter(Mandatory = $true)][string]$LicensePath,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$UpgradeCode = "1bfa7db8-e785-4fd2-8fb1-5debbd28ac03"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$source = (Resolve-Path $SourceDir).Path
$wix = (Resolve-Path $WixDir).Path
$icon = (Resolve-Path $IconPath).Path
$license = (Resolve-Path $LicensePath).Path
$output = [System.IO.Path]::GetFullPath($OutputDir)
$exe = Join-Path $source "ArchiveTune.exe"

if (-not (Test-Path $exe -PathType Leaf)) {
    throw "Native ArchiveTune executable not found: $exe"
}

$heat = Join-Path $wix "heat.exe"
$candle = Join-Path $wix "candle.exe"
$light = Join-Path $wix "light.exe"
foreach ($tool in @($heat, $candle, $light)) {
    if (-not (Test-Path $tool -PathType Leaf)) {
        throw "WiX tool not found: $tool"
    }
}

New-Item -ItemType Directory -Force $output | Out-Null
$work = Join-Path $output "native-msi-work"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null

$rawFilesWxs = Join-Path $work "files.raw.wxs"
$filesWxs = Join-Path $work "files.wxs"
$productWxs = Join-Path $work "product.wxs"
$licenseRtf = Join-Path $work "license.rtf"
$msi = Join-Path $output "ArchiveTune-$Version.msi"

# Use compile-time deterministic component GUID generation. This keeps component identity stable
# across upgrades without checking hundreds of generated GUIDs into source control.
& $heat dir $source -nologo -cg NativeFiles -dr INSTALLFOLDER -srd -sreg -scom -ag -var var.SourceDir -out $rawFilesWxs
if ($LASTEXITCODE -ne 0) { throw "heat.exe failed with exit code $LASTEXITCODE" }

# Per-user MSI components should use HKCU registry keypaths rather than files beneath LocalAppData.
# Heat does not know our installation scope, so rewrite each harvested component accordingly.
[xml]$harvest = Get-Content -Raw $rawFilesWxs
$ns = $harvest.DocumentElement.NamespaceURI
$mgr = New-Object System.Xml.XmlNamespaceManager($harvest.NameTable)
$mgr.AddNamespace("w", $ns)

function Get-DeterministicGuid([string]$value) {
    $md5 = [System.Security.Cryptography.MD5]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes("ArchiveTune.NativeMsi.Component|$value")
        $hash = $md5.ComputeHash($bytes)
        return ([Guid]::new($hash)).ToString("B").ToUpperInvariant()
    } finally {
        $md5.Dispose()
    }
}

$components = $harvest.SelectNodes("//w:Component", $mgr)
$removeFolderDirectories = @{}
foreach ($component in $components) {
    $component.SetAttribute("Guid", (Get-DeterministicGuid $component.Id))
    foreach ($file in $component.SelectNodes("w:File", $mgr)) {
        if ($file.HasAttribute("KeyPath")) { $file.RemoveAttribute("KeyPath") }
    }

    $registry = $harvest.CreateElement("RegistryValue", $ns)
    $registry.SetAttribute("Root", "HKCU")
    $registry.SetAttribute("Key", "Software\Rukamori\ArchiveTune\InstallerComponents")
    $registry.SetAttribute("Name", $component.Id)
    $registry.SetAttribute("Type", "integer")
    $registry.SetAttribute("Value", "1")
    $registry.SetAttribute("KeyPath", "yes")
    [void]$component.AppendChild($registry)

    # ICE64 requires every directory below a user-profile root to have an uninstall row in the
    # RemoveFile table. One RemoveFolder entry per harvested directory is sufficient.
    $directoryId = $component.GetAttribute("Directory")
    if ($directoryId -and -not $removeFolderDirectories.ContainsKey($directoryId)) {
        $removeFolder = $harvest.CreateElement("RemoveFolder", $ns)
        $removeFolder.SetAttribute("Id", "Remove_$($component.Id)")
        $removeFolder.SetAttribute("Directory", $directoryId)
        $removeFolder.SetAttribute("On", "uninstall")
        [void]$component.AppendChild($removeFolder)
        $removeFolderDirectories[$directoryId] = $true
    }
}

# Heat can emit structural directories that contain only child directories (for example
# native/windows-x64). They have no file component to host a RemoveFolder row, so create a tiny
# registry-backed cleanup component for each such directory.
$componentGroup = $harvest.SelectSingleNode("//w:ComponentGroup[@Id='NativeFiles']", $mgr)
foreach ($directory in $harvest.SelectNodes("//w:Directory", $mgr)) {
    $directoryId = $directory.GetAttribute("Id")
    if (-not $directoryId -or $removeFolderDirectories.ContainsKey($directoryId)) { continue }

    $cleanupId = "Cleanup_$directoryId"
    $cleanup = $harvest.CreateElement("Component", $ns)
    $cleanup.SetAttribute("Id", $cleanupId)
    $cleanup.SetAttribute("Directory", $directoryId)
    $cleanup.SetAttribute("Guid", (Get-DeterministicGuid $cleanupId))

    $removeFolder = $harvest.CreateElement("RemoveFolder", $ns)
    $removeFolder.SetAttribute("Id", "Remove_$directoryId")
    $removeFolder.SetAttribute("Directory", $directoryId)
    $removeFolder.SetAttribute("On", "uninstall")
    [void]$cleanup.AppendChild($removeFolder)

    $registry = $harvest.CreateElement("RegistryValue", $ns)
    $registry.SetAttribute("Root", "HKCU")
    $registry.SetAttribute("Key", "Software\Rukamori\ArchiveTune\InstallerComponents")
    $registry.SetAttribute("Name", $cleanupId)
    $registry.SetAttribute("Type", "integer")
    $registry.SetAttribute("Value", "1")
    $registry.SetAttribute("KeyPath", "yes")
    [void]$cleanup.AppendChild($registry)

    [void]$componentGroup.AppendChild($cleanup)
    $removeFolderDirectories[$directoryId] = $true
}

$xmlSettings = New-Object System.Xml.XmlWriterSettings
$xmlSettings.Indent = $true
$xmlSettings.Encoding = New-Object System.Text.UTF8Encoding($false)
$writer = [System.Xml.XmlWriter]::Create($filesWxs, $xmlSettings)
try { $harvest.Save($writer) } finally { $writer.Dispose() }

# WixUI_InstallDir expects an RTF license. Convert the repository's plain-text license without
# introducing another checked-in generated asset.
$licenseText = Get-Content -Raw $license
$licenseText = $licenseText.Replace("\", "\\").Replace("{", "\{").Replace("}", "\}")
$licenseText = $licenseText -replace "`r?`n", "\par `r`n"
$rtf = "{\rtf1\ansi\deff0{\fonttbl{\f0 Segoe UI;}}\fs18 $licenseText}"
[System.IO.File]::WriteAllText($licenseRtf, $rtf, [System.Text.Encoding]::ASCII)

function Escape-Xml([string]$value) {
    return [System.Security.SecurityElement]::Escape($value)
}

$sourceXml = Escape-Xml $source
$iconXml = Escape-Xml $icon
$licenseRtfXml = Escape-Xml $licenseRtf
$versionXml = Escape-Xml $Version
$upgradeXml = Escape-Xml $UpgradeCode

$product = @"
<?xml version="1.0" encoding="utf-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi" xmlns:util="http://schemas.microsoft.com/wix/UtilExtension">
  <Product Id="*"
           Name="ArchiveTune"
           Language="1033"
           Version="$versionXml"
           Manufacturer="ArchiveTune"
           UpgradeCode="$upgradeXml">
    <Package Description="A private, feature-rich YouTube Music client and music player."
             Manufacturer="ArchiveTune"
             InstallerVersion="500"
             Compressed="yes"
             InstallScope="perUser"
             Platform="x64" />
    <MediaTemplate EmbedCab="yes" CompressionLevel="medium" />

    <Property Id="WIXUI_INSTALLDIR" Value="INSTALLFOLDER" />
    <Property Id="ARPCONTACT" Value="ArchiveTune" />
    <Property Id="ARPCOMMENTS" Value="A private, feature-rich YouTube Music client and music player." />
    <Property Id="ARPPRODUCTICON" Value="ArchiveTuneIcon" />
    <Icon Id="ArchiveTuneIcon" SourceFile="$iconXml" />
    <WixVariable Id="WixUILicenseRtf" Value="$licenseRtfXml" />

    <!-- Match the existing jpackage installer identity and allow install-over-existing for
         upgrades, downgrades, and same-version development builds. -->
    <Upgrade Id="$upgradeXml">
      <UpgradeVersion OnlyDetect="no"
                      Property="JP_UPGRADABLE_FOUND"
                      Maximum="$versionXml"
                      IncludeMaximum="yes"
                      MigrateFeatures="yes" />
      <UpgradeVersion OnlyDetect="no"
                      Property="JP_DOWNGRADABLE_FOUND"
                      Minimum="$versionXml"
                      IncludeMinimum="yes"
                      MigrateFeatures="yes" />
    </Upgrade>

    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="SystemFolder" />
      <Directory Id="LocalAppDataFolder">
        <Directory Id="INSTALLFOLDER" Name="ArchiveTune App" />
      </Directory>
      <Directory Id="ProgramMenuFolder">
        <Directory Id="ArchiveTuneProgramMenuDir" Name="ArchiveTune" />
      </Directory>
    </Directory>

    <DirectoryRef Id="ArchiveTuneProgramMenuDir">
      <Component Id="StartMenuShortcut" Guid="{C3D9777B-569E-4F6E-92F5-770D079BC05C}">
        <Shortcut Id="ArchiveTuneStartMenuShortcut"
                  Name="ArchiveTune"
                  Description="ArchiveTune"
                  Target="[INSTALLFOLDER]ArchiveTune.exe"
                  WorkingDirectory="INSTALLFOLDER"
                  Icon="ArchiveTuneIcon" />
        <RemoveFolder Id="RemoveArchiveTuneProgramMenu" Directory="ArchiveTuneProgramMenuDir" On="uninstall" />
        <RegistryValue Root="HKCU"
                       Key="Software\Rukamori\ArchiveTune"
                       Name="StartMenuShortcut"
                       Type="integer"
                       Value="1"
                       KeyPath="yes" />
      </Component>
    </DirectoryRef>

    <DirectoryRef Id="INSTALLFOLDER">
      <Component Id="NativeInstallCleanup" Guid="{9908D096-D6A6-4CD9-9A83-E65F81697EFC}">
        <util:RemoveFolderEx On="uninstall" Property="INSTALLFOLDER" />
        <RegistryValue Root="HKCU"
                       Key="Software\Rukamori\ArchiveTune"
                       Name="NativeInstallFolder"
                       Type="string"
                       Value="[INSTALLFOLDER]"
                       KeyPath="yes" />
      </Component>
    </DirectoryRef>

    <Feature Id="DefaultFeature" Title="ArchiveTune" Level="1">
      <ComponentGroupRef Id="NativeFiles" />
      <ComponentRef Id="StartMenuShortcut" />
      <ComponentRef Id="NativeInstallCleanup" />
    </Feature>

    <!-- Preserve the legacy migration behavior used by the current JVM MSI. -->
    <CustomAction Id="MigrateLegacyUserData"
                  Directory="SystemFolder"
                  ExeCommand="&quot;[SystemFolder]xcopy.exe&quot; &quot;[LocalAppDataFolder]ArchiveTune\data\*&quot; &quot;[LocalAppDataFolder]Rukamori\ArchiveTune\data\&quot; /D /E /I /H /R /Y /Q"
                  Execute="immediate"
                  Return="ignore" />
    <CustomAction Id="SetARPInstallLocation"
                  Property="ARPINSTALLLOCATION"
                  Value="[INSTALLFOLDER]" />

    <InstallExecuteSequence>
      <Custom Action="SetARPInstallLocation" After="CostFinalize">Not Installed</Custom>
      <Custom Action="MigrateLegacyUserData" After="CostFinalize">
        NOT Installed AND (JP_UPGRADABLE_FOUND OR JP_DOWNGRADABLE_FOUND)
      </Custom>
      <RemoveExistingProducts After="InstallValidate" />
    </InstallExecuteSequence>

    <UIRef Id="WixUI_InstallDir" />
  </Product>
</Wix>
"@
[System.IO.File]::WriteAllText($productWxs, $product, ([System.Text.UTF8Encoding]::new($false)))

Push-Location $work
try {
    & $candle -nologo -arch x64 -ext WixUtilExtension -ext WixUIExtension -dSourceDir="$source" product.wxs files.wxs
    if ($LASTEXITCODE -ne 0) { throw "candle.exe failed with exit code $LASTEXITCODE" }

    Remove-Item $msi -Force -ErrorAction SilentlyContinue
    # ICE61 intentionally dislikes same-version replacement and ICE91 assumes the package might
    # switch to per-machine. ArchiveTune deliberately supports same-version upgrades and is fixed
    # to per-user installation, so suppress only those two validators.
    & $light -nologo -spdb -sice:ICE61 -sice:ICE91 -ext WixUtilExtension -ext WixUIExtension -cultures:en-us -out $msi product.wixobj files.wixobj
    if ($LASTEXITCODE -ne 0) { throw "light.exe failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$rawBytes = (Get-ChildItem $source -File -Recurse | Measure-Object Length -Sum).Sum
$msiBytes = (Get-Item $msi).Length
Write-Host ("Native MSI: {0:N2} MiB ({1:N1}% smaller than {2:N2} MiB unpacked)" -f ($msiBytes / 1MB), ((1 - $msiBytes / $rawBytes) * 100), ($rawBytes / 1MB))
Write-Host "Output: $msi"
