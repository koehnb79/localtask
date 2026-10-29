#Requires -RunAsAdministrator
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
foreach ($name in @('LocalTaskNetwork','LocalTaskBroker')) {
    $entry = Get-CimInstance Win32_Service -Filter "Name='$name'"
    if ($entry) {
        if ($entry.PathName -notlike '*\SolemnTreeApps\LocalTask\releases\*\LocalTaskHost.exe*') {
            throw "Refusing to remove an unrelated service named $name"
        }
        Stop-Service $name -ErrorAction Stop
        & "$env:SystemRoot\System32\sc.exe" delete $name
        if ($LASTEXITCODE -ne 0) { throw "Could not remove $name" }
    }
}
Get-NetFirewallRule -Name 'SolemnTreeApps-LocalTask' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host 'Boot services and firewall rule removed. Executables and pairing data are retained.'
Write-Host 'Protected files: C:\Program Files\SolemnTreeApps\LocalTask'
Write-Host 'Pairing/data: C:\ProgramData\SolemnTreeApps\LocalTask'
Write-Host 'The old LocalTask service, if present, remains disabled. Desktop scheduled tasks are not changed.'
