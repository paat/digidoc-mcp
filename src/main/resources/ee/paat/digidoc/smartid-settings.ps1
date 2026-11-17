# Writes HKCU\Software\RIA\qdigidoc4 Smart-ID settings for DigiDoc4.
# The personal code is read from stdin only and is never printed.
# REG_SZ matches Qt 6 QSettings for QString and bool ("true"). Unverified until the Windows spike.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try {
    $raw = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrEmpty($raw)) { exit 2 }
    $code = $raw.TrimEnd([char]13, [char]10)
    if ($code.Length -eq 0) { exit 2 }
    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\RIA\qdigidoc4')
    if ($null -eq $key) { exit 2 }
    $string = [Microsoft.Win32.RegistryValueKind]::String
    $key.SetValue('SmartID', $code, $string)
    $key.SetValue('SmartIDCountry', 'EE', $string)
    $key.SetValue('SmartIDSettings', 'true', $string)
    $key.Dispose()
    exit 0
} catch {
    exit 1
}
