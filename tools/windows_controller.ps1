# Read-only controller discovery and selection, shared by preflight and launch.
function Get-WatchController {
    param([Parameter(Mandatory=$true)][string]$InstanceId)
    if($InstanceId -notmatch '^USB\\VID_[0-9A-F]{4}&PID_[0-9A-F]{4}\\[^\\]+$'){
        throw 'Select a USB Bluetooth controller, not a USB hub or another device'
    }
    $device=Get-PnpDevice -PresentOnly -InstanceId $InstanceId -ErrorAction Stop
    $service=(Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName DEVPKEY_Device_Service -ErrorAction Stop).Data
    $compatible=(Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName DEVPKEY_Device_CompatibleIds -ErrorAction Stop).Data
    if($service -notin @('BTHUSB','WINUSB') -or
       $compatible -notcontains 'USB\Class_E0&SubClass_01&Prot_01' -or
       ($service -eq 'BTHUSB' -and $device.Class -ne 'Bluetooth')){
        throw 'Selected device does not expose a supported Bluetooth USB controller'
    }
    [pscustomobject]@{InstanceId=$device.InstanceId;Name=$device.FriendlyName;Status=$device.Status;Service=$service}
}

function Get-WatchControllers {
    Get-PnpDevice -PresentOnly -ErrorAction Stop |
        Where-Object InstanceId -Match '^USB\\VID_[0-9A-F]{4}&PID_[0-9A-F]{4}\\[^\\]+$' |
        ForEach-Object {
            # Devices can disappear during enumeration; unrelated USB nodes
            # and unsupported drivers never enter the selectable inventory.
            try {Get-WatchController -InstanceId $_.InstanceId} catch {}
        } | Sort-Object Name,InstanceId
}

function Select-WatchController {
    param([string]$InstanceId,[switch]$NonInteractive)
    if($InstanceId){return Get-WatchController -InstanceId $InstanceId}
    $controllers=@(Get-WatchControllers)
    if($controllers.Count -eq 0){throw 'No supported USB Bluetooth controller is present'}
    if($controllers.Count -eq 1){return $controllers[0]}
    if($NonInteractive){throw 'Multiple Bluetooth controllers are present; specify -InstanceId'}
    Write-Host 'Choose the Bluetooth controller to reserve for Watch Companion:'
    for($index=0;$index -lt $controllers.Count;$index++){
        Write-Host ('{0}. {1} [{2}] {3}' -f ($index+1),$controllers[$index].Name,$controllers[$index].Service,$controllers[$index].InstanceId)
    }
    $answer=Read-Host 'Controller number (empty to cancel)'
    $choice=0
    if(-not [int]::TryParse($answer,[ref]$choice) -or $choice -lt 1 -or $choice -gt $controllers.Count){
        throw 'Controller selection cancelled or invalid; no device was changed'
    }
    # Revalidate presence and driver immediately before starting the lease.
    Get-WatchController -InstanceId $controllers[$choice-1].InstanceId
}
