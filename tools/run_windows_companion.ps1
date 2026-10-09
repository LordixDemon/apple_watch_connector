param(
    [string]$InstanceId,
    [string]$Bundle=(Join-Path $PSScriptRoot '..\apple-watch-companion\build\windows\x64\runner\Release'),
    [switch]$Check,
    [switch]$NonInteractive,
    [switch]$ControllerLease,
    [string]$LeaseDirectory,
    [string]$RecoveryRecord
)
$ErrorActionPreference='Stop'
# Start-Process inherits PowerShell 7's module path; Windows PowerShell must load its own modules.
if($PSVersionTable.PSVersion.Major -lt 6){
    $env:PSModulePath=@((Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'WindowsPowerShell\Modules'),(Join-Path $env:ProgramFiles 'WindowsPowerShell\Modules'),(Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\Modules')) -join ';'
}
$Bundle=[IO.Path]::GetFullPath($Bundle)
$application=Join-Path $Bundle 'apple_watch_companion.exe'
$helper=Join-Path $Bundle 'watch-windows-hci.exe'
. (Join-Path $PSScriptRoot 'windows_controller.ps1')
if($ControllerLease -and -not $InstanceId){throw 'A controller lease requires an explicit -InstanceId'}
$signature=Get-AuthenticodeSignature (Join-Path $env:WINDIR 'System32\drivers\WinUSB.sys')
if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft'){throw 'The inbox Microsoft WinUSB driver failed signature verification'}
if($Check){
    $controllers=if($InstanceId){@(Get-WatchController -InstanceId $InstanceId)}else{@(Get-WatchControllers)}
    if($controllers.Count -eq 0){throw 'No supported USB Bluetooth controller is present'}
    $controllers | Select-Object InstanceId,Name,Status,Service,@{Name='WinUsbSignature';Expression={$signature.Status}},@{Name='Application';Expression={$application}}
    return
}
$device=Select-WatchController -InstanceId $InstanceId -NonInteractive:$NonInteractive
$InstanceId=$device.InstanceId
$service=$device.Service
if(-not (Test-Path -LiteralPath $application) -or -not (Test-Path -LiteralPath $helper)){throw 'Build the Windows application first'}

if(-not $ControllerLease){
    $LeaseDirectory=Join-Path $env:LOCALAPPDATA ('watch-companion\controller-leases\'+[guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $LeaseDirectory -Force | Out-Null
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"'),'-ControllerLease','-InstanceId',('"'+$InstanceId+'"'),'-Bundle',('"'+$Bundle+'"'),'-LeaseDirectory',('"'+$LeaseDirectory+'"'))
    if($RecoveryRecord){$arguments+=@('-RecoveryRecord',('"'+[IO.Path]::GetFullPath($RecoveryRecord)+'"'))}
    $supervisor=Start-Process 'powershell.exe' -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -PassThru
    try {
        $deadline=(Get-Date).AddMinutes(2)
        while(-not (Test-Path -LiteralPath (Join-Path $LeaseDirectory 'ready'))){
            if($supervisor.HasExited -or (Get-Date) -gt $deadline){throw ('Controller preparation failed. See '+$LeaseDirectory)}
            Start-Sleep -Milliseconds 200
        }
        # Launch from the caller, so the GUI and DPAPI worker stay unelevated.
        $gui=Start-Process -FilePath $application -WorkingDirectory $Bundle -WindowStyle Normal -PassThru
        Set-Content -LiteralPath (Join-Path $LeaseDirectory 'application.pid') -Value $gui.Id -Encoding ASCII
        Write-Output ('Interface running; controller recovery: '+$LeaseDirectory)
        $gui.WaitForExit()
    } finally {Set-Content -LiteralPath (Join-Path $LeaseDirectory 'stop') -Value 'STOP' -Encoding ASCII}
    return
}

$principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'The controller supervisor must run elevated'}
Start-Transcript -Path (Join-Path $LeaseDirectory 'controller.log') -Force | Out-Null
Add-Type -Path (Join-Path $PSScriptRoot 'windows_controller_driver.cs')
$parameters='HKLM:\SYSTEM\CurrentControlSet\Enum\'+$InstanceId+'\Device Parameters'
$originalInf=(Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName DEVPKEY_Device_DriverInfPath).Data
$oldGuids=(Get-ItemProperty -LiteralPath $parameters).DeviceInterfaceGUIDs
$restore=$service -eq 'BTHUSB'
if($RecoveryRecord){
    $recovery=Get-Content -LiteralPath $RecoveryRecord -Raw | ConvertFrom-Json
    if($recovery.instance -ne $InstanceId){throw 'Recovery record belongs to another device'}
    $originalInf=$recovery.inf;$oldGuids=$recovery.guids;$restore=$true
}
if($restore -and $originalInf -notmatch '^(oem[0-9]+|bth)\.inf$'){throw 'Unexpected original Bluetooth INF'}
$record=@{instance=$InstanceId;inf=$originalInf;hadGuids=$null -ne $oldGuids;guids=$oldGuids;restore=$restore}
$record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LeaseDirectory 'original.json') -Encoding UTF8
try {
    if($service -eq 'BTHUSB'){
        $inf=Join-Path $env:WINDIR 'INF\winusb.inf'
        $description=([WatchControllerDriver]::Run($InstanceId,$inf,'',$false) -split "`n")[0].Split('|')[0].Trim()
        [WatchControllerDriver]::Run($InstanceId,$inf,$description,$true)
        New-ItemProperty -LiteralPath $parameters -Name DeviceInterfaceGUIDs -Value @('{22546198-F6EB-4A03-BDAC-20C4BF9D17FD}') -PropertyType MultiString -Force | Out-Null
        & (Join-Path $env:WINDIR 'System32\pnputil.exe') /restart-device $InstanceId
    }
    $deadline=(Get-Date).AddSeconds(40)
    do {
        $inventory=(& $helper --list) | ConvertFrom-Json
        if($inventory.usbBackend -eq 'winusb' -and $inventory.rawHciAvailable){break}
        if((Get-Date) -gt $deadline){throw 'WinUSB did not expose the Bluetooth HCI interface'}
        Start-Sleep -Milliseconds 500
    } while($true)
    Set-Content -LiteralPath (Join-Path $LeaseDirectory 'ready') -Value 'READY' -Encoding ASCII
    $deadline=(Get-Date).AddMinutes(2)
    while(-not (Test-Path -LiteralPath (Join-Path $LeaseDirectory 'application.pid'))){
        if((Get-Date) -gt $deadline -or (Test-Path -LiteralPath (Join-Path $LeaseDirectory 'stop'))){return}
        Start-Sleep -Milliseconds 200
    }
    $guiId=[int](Get-Content -LiteralPath (Join-Path $LeaseDirectory 'application.pid'))
    $gui=Get-CimInstance Win32_Process -Filter "ProcessId=$guiId"
    if(-not $gui -or $gui.ExecutablePath -ne $application){throw 'Application process does not match the requested bundle'}
    while((Get-Process -Id $guiId -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath (Join-Path $LeaseDirectory 'stop'))){Start-Sleep -Milliseconds 500}
} finally {
    if($restore){
        $inf=Join-Path $env:WINDIR ('INF\'+$originalInf)
        $description=([WatchControllerDriver]::Run($InstanceId,$inf,'',$false,$true) -split "`n")[0].Split('|')[0].Trim()
        [WatchControllerDriver]::Run($InstanceId,$inf,$description,$true,$true)
        if($oldGuids){New-ItemProperty -LiteralPath $parameters -Name DeviceInterfaceGUIDs -Value $oldGuids -PropertyType MultiString -Force | Out-Null}
        else{Remove-ItemProperty -LiteralPath $parameters -Name DeviceInterfaceGUIDs -ErrorAction SilentlyContinue}
        if((Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName DEVPKEY_Device_Service).Data -ne 'BTHUSB'){throw 'Original Bluetooth driver did not resume'}
    }
    Set-Content -LiteralPath (Join-Path $LeaseDirectory 'restored') -Value 'RESTORED' -Encoding ASCII
    Stop-Transcript | Out-Null
}
