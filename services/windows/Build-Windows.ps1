[CmdletBinding()]
param([string]$Python = 'python.exe')
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Build this package on Windows.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$agent = Join-Path $repo 'agent'
$buildEnv = Join-Path $repo '.windows-build-env'
function Invoke-Checked([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed ($LASTEXITCODE). No service has been installed." }
}
# Build as your ordinary user. Installation is a separate elevated step.
Invoke-Checked $Python @('-m', 'venv', $buildEnv)
$py = Join-Path $buildEnv 'Scripts\python.exe'
Invoke-Checked $py @('-m','pip','install','-r',(Join-Path $agent 'requirements-windows-boot.txt'))
Invoke-Checked $py @('-m','PyInstaller','--noconfirm','--clean','--onedir','--name','LocalTaskHost',
    '--distpath',(Join-Path $repo 'dist'), '--workpath',(Join-Path $repo 'build\windows'),
    '--specpath',(Join-Path $repo 'build'), '--paths',$agent,
    '--hidden-import','win32timezone','--hidden-import','uvicorn.loops.asyncio',
    '--hidden-import','uvicorn.protocols.http.h11_impl','--hidden-import','uvicorn.lifespan.on',
    (Join-Path $agent 'windows_boot_host.py'))
Invoke-Checked (Join-Path $repo 'dist\LocalTaskHost\LocalTaskHost.exe') @('selftest')
Write-Host "Built: $(Join-Path $repo 'dist\LocalTaskHost')"
Write-Host 'Next: run Install-Windows.ps1 from an Administrator PowerShell.'
