# Isolated behavioral tests; no PnP device or driver is changed.
$ErrorActionPreference='Stop'
foreach($name in @('windows_controller.ps1','run_windows_companion.ps1')){
    $tokens=$null;$errors=$null
    [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors) | Out-Null
    if($errors.Count){throw ('PowerShell parse failed: '+$name)}
}
. (Join-Path $PSScriptRoot 'windows_controller.ps1')
$script:devices=@()
$script:answer=''
$script:prompts=0
function Get-PnpDevice {
    param([switch]$PresentOnly,[string]$InstanceId,[string]$ErrorAction)
    if($InstanceId){
        $found=@($script:devices | Where-Object InstanceId -EQ $InstanceId)
        if($found.Count -ne 1){throw 'Device absent'}
        return $found[0]
    }
    $script:devices
}
function Get-PnpDeviceProperty {
    param([string]$InstanceId,[string]$KeyName,[string]$ErrorAction)
    $device=Get-PnpDevice -InstanceId $InstanceId
    if($KeyName -eq 'DEVPKEY_Device_Service'){return [pscustomobject]@{Data=$device.Service}}
    [pscustomobject]@{Data=$device.Compatible}
}
function Read-Host {
    param([string]$Prompt)
    $script:prompts++
    $script:answer
}
function Get-AuthenticodeSignature {
    param([string]$FilePath)
    [pscustomobject]@{Status='Valid';SignerCertificate=[pscustomobject]@{Subject='CN=Microsoft Test'}}
}
function Start-Process {throw 'Read-only preflight attempted to start a process'}
function Assert-Equal($Actual,$Expected,$Message){if($Actual -ne $Expected){throw $Message}}
function Assert-Fails([scriptblock]$Action,$Message){
    $failed=$false
    try{& $Action | Out-Null}catch{$failed=$true}
    if(-not $failed){throw $Message}
}
function Device($Id,$Name,$Service='BTHUSB',$Class='Bluetooth',$Compatible='USB\Class_E0&SubClass_01&Prot_01'){
    [pscustomobject]@{InstanceId=$Id;FriendlyName=$Name;Service=$Service;Class=$Class;Status='OK';Compatible=@($Compatible)}
}
$first=Device 'USB\VID_1234&PID_5678\TEST_A' 'Alpha'
$second=Device 'USB\VID_4321&PID_8765\TEST_B' 'Beta' 'WINUSB' 'USBDevice'
$unrelated=Device 'USB\VID_1111&PID_2222\HUB' 'Hub' 'USBHUB3' 'USB' 'USB\Class_09'
$script:devices=@($first,$unrelated)
Assert-Equal (Select-WatchController).InstanceId $first.InstanceId 'Single controller selection failed'
Assert-Equal @(Get-WatchControllers).Count 1 'USB hub entered the inventory'
Assert-Fails {Select-WatchController -InstanceId $unrelated.InstanceId} 'Explicit hub selection succeeded'
$script:devices=@($second,$first)
$previousWindows=$env:WINDIR
try {
    $env:WINDIR=[IO.Path]::GetTempPath()
    $checked=@(. (Join-Path $PSScriptRoot 'run_windows_companion.ps1') -Check -Bundle ([IO.Path]::GetTempPath()))
    Assert-Equal $checked.Count 2 'Preflight did not list every supported controller'
    Assert-Equal $checked[0].InstanceId $first.InstanceId 'Preflight inventory ordering failed'
    Assert-Equal $checked[1].WinUsbSignature 'Valid' 'Preflight signature projection failed'
} finally {$env:WINDIR=$previousWindows}
Assert-Fails {Select-WatchController -NonInteractive} 'Multiple controllers were silently selected'
Assert-Equal $script:prompts 0 'Noninteractive mode prompted'
Assert-Equal (Select-WatchController -InstanceId $second.InstanceId).InstanceId $second.InstanceId 'Explicit WinUSB selection failed'
$script:answer='2'
Assert-Equal (Select-WatchController).InstanceId $second.InstanceId 'Interactive controller choice failed'
$script:answer='0'
Assert-Fails {Select-WatchController} 'Out-of-range controller choice succeeded'
$script:answer=''
Assert-Fails {Select-WatchController} 'Cancelled controller choice succeeded'
$script:devices=@()
Assert-Fails {Select-WatchController} 'Absent controllers were accepted'
Assert-Fails {Select-WatchController -InstanceId $first.InstanceId} 'An unplugged controller was accepted'
Write-Output 'Windows controller selection: all behavioral checks passed'
