#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
Get-Service -Name LocalTaskBroker,LocalTaskNetwork | Format-Table Name,Status,StartType
$service = Get-CimInstance Win32_Service -Filter "Name='LocalTaskNetwork'"
if ($service.PathName -notmatch '^"([^"]+\\SolemnTreeApps\\LocalTask\\releases\\[^\\]+\\LocalTaskHost.exe)" network$') {
    throw 'Unrecognized service executable path'
}
& $Matches[1] check
if ($LASTEXITCODE -ne 0) { throw 'Check failed. Inspect ProgramData\SolemnTreeApps\LocalTask\logs.' }
