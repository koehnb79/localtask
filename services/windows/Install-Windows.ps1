#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Account,
    [string]$PairingDirectory,
    [string]$HostAddress,
    [string]$InterfaceAlias,
    [ValidateRange(1,65535)][int]$Port = 8765,
    [string]$PackageDirectory = (Join-Path $PSScriptRoot '..\..\dist\LocalTaskHost')
)
$ErrorActionPreference = 'Stop'
if ([bool]$HostAddress -eq [bool]$InterfaceAlias) { throw 'Specify exactly one of -HostAddress or -InterfaceAlias.' }
$root = Join-Path $env:ProgramFiles 'SolemnTreeApps\LocalTask'
$data = Join-Path $env:ProgramData 'SolemnTreeApps\LocalTask'
$networkName = 'LocalTaskNetwork'
$brokerName = 'LocalTaskBroker'
$release = Join-Path $root ('releases\' + [guid]::NewGuid().ToString('N'))
$exe = Join-Path $release 'LocalTaskHost.exe'
$firewallName = 'SolemnTreeApps-LocalTask'
$services = @($networkName, $brokerName)
$old = @{}
$created = @()
$oldConfig = $null
$oldFirewall = $null
$firewallTouched = $false
$legacy = Get-Service -Name 'LocalTask' -ErrorAction SilentlyContinue
$legacyWasRunning = $legacy -and $legacy.Status -eq 'Running'

function Invoke-Sc([string[]]$Arguments) {
    & "$env:SystemRoot\System32\sc.exe" @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Service configuration failed ($LASTEXITCODE)." }
}
function Assert-NoReparse([string]$Path) {
    $walk = [IO.Path]::GetFullPath($Path)
    while ($walk) {
        if (Test-Path -LiteralPath $walk) {
            if ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Reparse points are not allowed in service paths: $walk"
            }
        }
        $parent = Split-Path -Parent $walk
        if ($parent -eq $walk) { break }; $walk = $parent
    }
}
function Set-ProtectedDirectory([string]$Path, [string]$NetworkRights = '') {
    Assert-NoReparse $Path
    if (Test-Path -LiteralPath $Path) {
        $acl = Get-Acl -LiteralPath $Path
        $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
        if ($owner -notin @('S-1-5-18','S-1-5-32-544')) { throw "Refusing existing directory not owned by SYSTEM/Administrators: $Path" }
    } else { New-Item -ItemType Directory -Path $Path | Out-Null }
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid), 'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    }
    if ($NetworkRights) {
        $sid = ([Security.Principal.NTAccount]::new('NT SERVICE', $networkName)).Translate([Security.Principal.SecurityIdentifier])
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,$NetworkRights,'ContainerInherit,ObjectInherit','None','Allow'))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
Assert-NoReparse $package
if (-not (Test-Path -LiteralPath (Join-Path $package 'LocalTaskHost.exe'))) { throw 'Build the Windows package first.' }
if (Get-ChildItem -LiteralPath $package -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
    throw 'The package must not contain symlinks or junctions.'
}
if (Test-Path -LiteralPath $data) {
    Assert-NoReparse $data
    if (Get-ChildItem -LiteralPath $data -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
        throw 'Service data must not contain symlinks or junctions.'
    }
}
# Protect ancestor directories too; users must not be able to replace a child directory.
$existingNetwork = Get-Service $networkName -ErrorAction SilentlyContinue
$initialRights = if ($existingNetwork) { 'ReadAndExecute' } else { '' }
foreach ($p in @((Split-Path $root), $root, (Join-Path $root 'releases'), (Split-Path $data), $data)) {
    Set-ProtectedDirectory $p $initialRights
}
if (Test-Path -LiteralPath (Join-Path $data 'service.json')) { $oldConfig = [IO.File]::ReadAllBytes((Join-Path $data 'service.json')) }
foreach ($name in $services) {
    $entry = Get-CimInstance Win32_Service -Filter "Name='$name'"
    if ($entry) {
        if ($entry.PathName -notlike "*\SolemnTreeApps\LocalTask\releases\*\LocalTaskHost.exe*" -or
            ($name -eq $networkName -and $entry.StartName -notin @('NT AUTHORITY\LocalService','NT AUTHORITY\Local Service')) -or
            ($name -eq $brokerName -and $entry.StartName -ne 'LocalSystem')) {
            throw "An incompatible service already uses $name; no service was replaced."
        }
        $old[$name] = $entry
    }
}
try {
    if ($legacyWasRunning) { Stop-Service 'LocalTask' -ErrorAction Stop }
    foreach ($name in $services) {
        if (Get-Service $name -ErrorAction SilentlyContinue) { Stop-Service $name -ErrorAction Stop }
    }
    New-Item -ItemType Directory -Path $release | Out-Null
    Copy-Item -Path (Join-Path $package '*') -Destination $release -Recurse
    # Source files can carry explicit ACLs; replace them with protected inherited ACLs.
    & "$env:SystemRoot\System32\icacls.exe" $release /reset /T /Q
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect packaged executable files.' }
    foreach ($name in @($brokerName, $networkName)) {
        $role = if ($name -eq $brokerName) { 'broker' } else { 'network' }
        $binary = '"' + $exe + '" ' + $role
        if ($old.ContainsKey($name)) {
            $changed = Invoke-CimMethod -InputObject $old[$name] -MethodName Change -Arguments @{PathName=$binary}
            if ($changed.ReturnValue -ne 0) { throw "Could not update executable path for $name" }
        }
        else {
            if ($name -eq $networkName) {
                $credential = [Management.Automation.PSCredential]::new('NT AUTHORITY\LocalService', [Security.SecureString]::new())
                New-Service -Name $name -BinaryPathName $binary -DisplayName 'LocalTask Network Agent' -StartupType Manual -Credential $credential | Out-Null
            } else {
                New-Service -Name $name -BinaryPathName $binary -DisplayName 'LocalTask Local Helper' -StartupType Manual | Out-Null
            }
            $created += $name
        }
        Invoke-Sc @('sidtype',$name,'unrestricted')
        Invoke-Sc @('failure',$name,'reset=','86400','actions=','restart/5000/restart/15000/restart/60000')
        Invoke-Sc @('failureflag',$name,'1')
    }
    Invoke-Sc @('config',$networkName,'depend=',$brokerName)
    Invoke-Sc @('privs',$networkName,'SeChangeNotifyPrivilege')
    Invoke-Sc @('privs',$brokerName,'SeChangeNotifyPrivilege/SeImpersonatePrivilege/SeTcbPrivilege/SeShutdownPrivilege')
    # Give only this service SID access, not every service running as LocalService.
    Set-ProtectedDirectory (Split-Path $root) 'ReadAndExecute'
    Set-ProtectedDirectory (Split-Path $data) 'ReadAndExecute'
    Set-ProtectedDirectory $root 'ReadAndExecute'
    Set-ProtectedDirectory (Join-Path $root 'releases') 'ReadAndExecute'
    Set-ProtectedDirectory $release 'ReadAndExecute'
    Set-ProtectedDirectory $data 'ReadAndExecute'
    Set-ProtectedDirectory (Join-Path $data 'credentials') 'ReadAndExecute'
    Set-ProtectedDirectory (Join-Path $data 'logs') 'ReadAndExecute'
    # Never write SYSTEM logs into a directory writable by the network service.
    Set-ProtectedDirectory (Join-Path $data 'logs\broker')
    Set-ProtectedDirectory (Join-Path $data 'logs\network') 'Modify'
    $setup = @('setup','--account',$Account,'--port',"$Port")
    if ($HostAddress) { $setup += @('--host',$HostAddress) } else { $setup += @('--interface',$InterfaceAlias) }
    if ($PairingDirectory) { $setup += @('--source',(Resolve-Path -LiteralPath $PairingDirectory).Path) }
    & $exe @setup
    if ($LASTEXITCODE -ne 0) { throw 'Configuration validation failed; existing pairing was not replaced.' }
    # ProgramData files inherit read-only access for the network service.
    foreach ($file in @('service.json','ipc.key','credentials\key.pem','credentials\cert.pem','credentials\token')) {
        $path = Join-Path $data $file
        Assert-NoReparse $path
        & "$env:SystemRoot\System32\icacls.exe" $path /reset /Q
        if ($LASTEXITCODE -ne 0) { throw 'Configuration/credential ACL setup failed.' }
    }
    $oldRule = Get-NetFirewallRule -Name $firewallName -ErrorAction SilentlyContinue
    if ($oldRule) {
        $appFilter = $oldRule | Get-NetFirewallApplicationFilter
        if ($appFilter.Program -notlike "$root\releases\*\LocalTaskHost.exe") { throw 'An unrelated firewall rule uses the LocalTask rule name.' }
        $portFilter = $oldRule | Get-NetFirewallPortFilter
        $addressFilter = $oldRule | Get-NetFirewallAddressFilter
        $oldFirewall = @{ Name=$firewallName; DisplayName=$oldRule.DisplayName; Direction=$oldRule.Direction;
            Action=$oldRule.Action; Enabled=$oldRule.Enabled; Profile=$oldRule.Profile;
            Program=$appFilter.Program; Protocol=$portFilter.Protocol; LocalPort=$portFilter.LocalPort;
            RemotePort=$portFilter.RemotePort; LocalAddress=$addressFilter.LocalAddress; RemoteAddress=$addressFilter.RemoteAddress }
    }
    $firewallTouched = $true
    if ($oldRule) { Remove-NetFirewallRule -Name $firewallName }
    New-NetFirewallRule -Name $firewallName -DisplayName 'LocalTask (private local network)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -Program $exe -Profile Private -RemoteAddress LocalSubnet | Out-Null
    foreach ($name in @($brokerName, $networkName)) { Invoke-Sc @('config',$name,'start=','delayed-auto') }
    Start-Service $brokerName
    Start-Service $networkName
    Start-Sleep -Seconds 3
    foreach ($name in $services) {
        if ((Get-Service $name).Status -ne 'Running') { throw "$name did not remain running. Check $data\logs." }
    }
    if ($legacy) { Set-Service 'LocalTask' -StartupType Disabled }
    Write-Host "Installed for boot. Logs: $data\logs"
    Write-Host 'Running service status is not proof HTTPS is reachable. Check network.log and connect from your phone.'
    Write-Host 'Existing credentials were retained. Keep the old copy backed up until you verify the new setup.'
} catch {
    $failure = $_
    foreach ($name in $services) { Stop-Service $name -ErrorAction SilentlyContinue }
    foreach ($name in $created) { & "$env:SystemRoot\System32\sc.exe" delete $name | Out-Null }
    foreach ($name in $old.Keys) {
        Invoke-CimMethod -InputObject $old[$name] -MethodName Change -Arguments @{PathName=$old[$name].PathName} -ErrorAction Continue | Out-Null
    }
    if ($oldConfig) { [IO.File]::WriteAllBytes((Join-Path $data 'service.json'), $oldConfig) }
    if ($firewallTouched) {
        Get-NetFirewallRule -Name $firewallName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
        if ($oldFirewall) { New-NetFirewallRule @oldFirewall -ErrorAction Continue | Out-Null }
    }
    # Never delete pairing data on a failed install. Leave all services stopped if rollback is incomplete.
    Write-Warning 'Install/update failed. Previous service executable paths and configuration were restored where present. Services remain stopped; pairing data is retained. Check firewall settings and logs before restarting.'
    throw $failure
}
