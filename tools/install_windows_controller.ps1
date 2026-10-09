param([switch]$Install)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# 1.0.22 can crash Bluetooth drivers with WDF_VIOLATION (power IRP).
# Upstream recommends 1.0.21: https://github.com/daynix/UsbDk/issues/115
$driverPackage = Join-Path $projectRoot '.tools/usbdk/UsbDk_1.0.21_x64.msi'
if (-not (Test-Path -LiteralPath $driverPackage)) {
    $driverDirectory = Split-Path -Parent $driverPackage
    New-Item -Path $driverDirectory -ItemType Directory -Force | Out-Null
    Invoke-WebRequest -Uri 'https://github.com/daynix/UsbDk/releases/download/v1.00-21/UsbDk_1.0.21_x64.msi' -OutFile $driverPackage
}
$signature = Get-AuthenticodeSignature -LiteralPath $driverPackage
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O="?Red Hat, Inc\.') {
    throw 'UsbDk package signature is not valid for Red Hat'
}
if (-not $Install) {
    Write-Output 'Verified official UsbDk 1.0.21 x64 package. Installation requires -Install and Windows administrator approval.'
    Write-Output 'The selected Bluetooth controller is temporarily reserved during Watch sessions; other devices using it disconnect until release.'
    exit 0
}
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $elevatedArguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Install' -f $PSCommandPath
    $elevated = Start-Process -FilePath "$env:WINDIR/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList $elevatedArguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
    if ($elevated.ExitCode -eq 3010) { Write-Output 'UsbDk replaced; Windows requires a restart before controller use.'; exit 3010 }
    if ($elevated.ExitCode -ne 0) { throw "UsbDk replacement failed ($($elevated.ExitCode)); see .tools/usbdk logs" }
    Write-Output 'Signed UsbDk 1.0.21 installed. Restart Watch Companion before pairing.'
    exit 0
}
$restartRequired = $false
$installed = Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object DisplayName -EQ 'UsbDk Runtime Libraries'
foreach ($product in $installed) {
    if ($product.DisplayVersion -eq '1.0.21') { Write-Output 'UsbDk 1.0.21 is already installed.'; exit 0 }
    if ($product.DisplayVersion -ne '1.0.22' -or $product.PSChildName -notmatch '^\{[0-9A-Fa-f-]{36}\}$') { throw 'Unexpected UsbDk installation; refusing automatic replacement.' }
    $uninstallLog = Join-Path $projectRoot '.tools/usbdk/remove-1.0.22.log'
    $removeArguments = '/x {0} /passive /norestart /log "{1}"' -f $product.PSChildName, $uninstallLog
    $remove = Start-Process -FilePath "$env:WINDIR/System32/msiexec.exe" -ArgumentList $removeArguments -WindowStyle Hidden -Wait -PassThru
    if ($remove.ExitCode -eq 3010) { $restartRequired = $true }
    elseif ($remove.ExitCode -ne 0) { throw "UsbDk 1.0.22 removal failed ($($remove.ExitCode)); see $uninstallLog" }
}
$installerLog = Join-Path $projectRoot '.tools/usbdk/install-1.0.21.log'
$installerArguments = '/i "{0}" /passive /norestart /log "{1}"' -f $driverPackage, $installerLog
$installer = Start-Process -FilePath "$env:WINDIR/System32/msiexec.exe" -ArgumentList $installerArguments -WindowStyle Hidden -Wait -PassThru
if ($installer.ExitCode -eq 3010) { $restartRequired = $true }
elseif ($installer.ExitCode -ne 0) { throw "UsbDk installation failed ($($installer.ExitCode)); see $installerLog" }
if ($restartRequired) { Write-Output 'UsbDk 1.0.21 installed; Windows requires a restart before use.'; exit 3010 }
Write-Output 'UsbDk 1.0.21 installed. Restart Watch Companion before starting a pairing session.'
