# Windows boot service (experimental 0.5)

This replaces the legacy `windows_service.py` adapter for boot-time use. The Android app stays at 0.4: no APK rebuild is needed. Mac/Linux and the ordinary desktop agent are unchanged.

**Validation boundary:** portable agent/policy tests and PowerShell syntax checks run in the development environment. The frozen Windows build, service installation, named-pipe ACLs, actual Windows boot/logout, and Windows-native operations still require testing on Windows. Start with the read-only checks below. This is not a signed, production-audited installer.

## What is installed

| Component | Identity | Purpose |
| --- | --- | --- |
| `LocalTaskNetwork` | LocalService plus its own service SID | Pinned HTTPS listener; cannot directly manage your desktop processes. |
| `LocalTaskBroker` | LocalSystem | Local named-pipe helper with only process listing/stop and confirmed logout/shutdown/restart operations. No TCP listener or shell-command endpoint. |

The helper authorizes the **user SID chosen during installation**, not the account running the service. An administrator's everyday account can be selected without running the network server as administrator. Process owner, critical status and creation time are checked through the same process handle used to terminate it. Other users' and SYSTEM processes remain view-only. Windows may additionally deny access to protected processes.

Log off requires exactly one active/disconnected session for the configured account, and rechecks its logon identity before dispatch. With no user logged in, power actions remain available and logout is unavailable. With multiple matching sessions, logout is refused rather than guessing. The service survives logout. Existing single-use, 60-second confirmations and cooldowns are enforced in the helper, not just the phone/network process.

The pipe rejects remote clients and grants client rights only to the network service SID. Requests/replies also use a protected IPC key, per-request nonce, bounded JSON, and HMAC authentication. The client does not retry a command when the reply is lost. The network service has only its required traverse privilege; the helper retains specific local privileges for session/token inspection, pipe authentication and shutdown. The network service can still request all operations authorized for a paired phone: this split does not make a compromised network process harmless.

Executable files go under `%ProgramFiles%\SolemnTreeApps\LocalTask\releases`; configuration and copied credentials go under `%ProgramData%\SolemnTreeApps\LocalTask`. Normal users cannot modify installed code or configuration. The network service can read its credentials/config and write only its own log directory. SYSTEM logs are kept in a separate directory it cannot modify.

## 1. Build once on Windows, as your normal user

Pull/download the updated repository. Use a standard, non-free-threaded Python installation. Python 3.15 is within the pinned PyInstaller package's declared supported range; the build smoke test must pass on your machine. The build uses its own virtual environment and does **not** run pywin32's global post-install script.

From the repository root:

```powershell
.\services\windows\Build-Windows.ps1 -Python "C:\Users\koehn\AppData\Local\Python\bin\python.exe"
```

Or pass the full path of your own Python executable. This builds `dist\LocalTaskHost\LocalTaskHost.exe` plus its `_internal` directory, then runs a read-only native API/import smoke test. Keep the entire folder together. The installed services use this bundled Python runtime, not your per-user Python installation or Microsoft Store aliases. Internet is needed to download build dependencies; runtime operation is local.

If PowerShell blocks downloaded scripts, review them and use the file Properties **Unblock** option or `Unblock-File` on those specific scripts. Do not disable machine-wide script policy.

## 2. Install in Administrator PowerShell

Stop any foreground agent and disable your old LocalTask scheduled task, if you created one, so it cannot compete for the port. The installer stops the legacy `LocalTask` service if it is running and disables it only after the new services start successfully.

From the repository root, for the account and address used in the current setup:

```powershell
.\services\windows\Install-Windows.ps1 `
    -Account "$env:COMPUTERNAME\koehn" `
    -PairingDirectory "C:\Users\koehn\.localtask" `
    -HostAddress "192.168.61.40"
```

The selected account must resolve to a real local/domain user SID. For a Microsoft account or domain account, use its Windows-resolvable account name; `whoami` in your ordinary desktop terminal shows that name. Do not select Administrators, LocalSystem or LocalService as the managed user.

For DHCP, select your adapter by the exact name shown by `Get-NetAdapter`, **instead of `-HostAddress`**:

```powershell
Get-NetAdapter
.\services\windows\Install-Windows.ps1 `
    -Account "$env:COMPUTERNAME\koehn" `
    -PairingDirectory "C:\Users\koehn\.localtask" `
    -InterfaceAlias "Ethernet"
```

Use `Wi-Fi` if that is the actual adapter name. The service waits for one private IPv4 address on that adapter, and restarts its listener after an address change. It does not choose another adapter, bind a wildcard/public address, or select between multiple private addresses. The phone still saves an IP: edit its profile if DHCP changes the address. A router reservation avoids that manual update.

The installer copies the existing certificate, private key and access token and verifies they match. **Do not run `init` again.** Existing phone pairing/QR credentials stay valid. Updates preserve the installed credentials and reject a different source pairing or a different managed account. The original `.localtask` directory is retained as a backup; the installed services subsequently read the protected ProgramData copy.

The installer creates one inbound firewall rule for this executable/port, **Private profile and LocalSubnet only**. It does not change your network profile. Both services use automatic delayed startup and recovery restarts. Delayed startup and network readiness mean the phone may need to wait after boot. Service startup runs without a saved desktop password.

## 3. Verify without executing computer actions

From Administrator PowerShell:

```powershell
.\services\windows\Check-Windows.ps1
```

This reports service state, performs authenticated HTTPS requests using the installed certificate/token, and reads process/action availability through the helper. It does not execute any stop/logout/shutdown operation and does not print access keys.

Then connect from your existing Android profile. Verify that only the selected account's noncritical processes are manageable. Save work before testing an action. For the final boot check, restart Windows normally, leave it at the sign-in screen, and try connecting from your phone. Logout should be unavailable until a matching session exists; shutdown/restart should be available. After signing in, test logout only when your work is saved, and verify the agent is still reachable afterward.

Local checks do not prove the LAN firewall is correctly configured; the phone connection is a separate test. A `Running` service can still be waiting for its network address. Inspect logs:

```powershell
Get-Content "$env:ProgramData\SolemnTreeApps\LocalTask\logs\network\network.log" -Tail 40
Get-Content "$env:ProgramData\SolemnTreeApps\LocalTask\logs\broker\broker.log" -Tail 40
```

If `check` reports helper timeout, inspect both services and the helper log. If the log reports waiting for an address, check the adapter name/IP. For port conflicts, stop the old foreground/scheduled-task agent. If the phone cannot connect but `check` passes, check the LAN IP, Windows network profile, firewall, and guest Wi-Fi isolation.

## Updating and removing

Build the new package as your normal user, then rerun `Install-Windows.ps1` elevated with the same account and desired interface/address. `-PairingDirectory` is optional after the first successful install. The script stops both services, installs a new protected release directory, updates executable paths/firewall, and restarts them. Older release folders are retained for diagnosis. Do not replace files under Program Files manually while the services run.

On failure, existing executable paths/configuration/firewall are restored where possible and services are left stopped. Credentials are not deleted. Inspect the error and logs before rerunning the installer or restarting services. This is not an all-or-nothing MSI transaction; startup/recovery settings and ACL tightening may persist after a failed install.

To uninstall the new services and their firewall rule:

```powershell
.\services\windows\Uninstall-Windows.ps1
```

It retains installed code and credentials. It does not re-enable the old service or change scheduled tasks. Delete retained folders manually only after deciding you no longer need the pairing/backup.

**Credential rotation:** the old desktop `.localtask` copy is no longer live. To revoke a shared key, use the source agent's `revoke` command with an explicit `--config "$env:ProgramData\SolemnTreeApps\LocalTask\credentials"` in an elevated terminal that has the agent dependencies, then restart `LocalTaskNetwork` and update phone profiles. The fingerprint remains unchanged. Never paste the token into diagnostics.

## Scope and sources

The only administrator change at install is the local service/package/configuration/firewall setup. No remote shell, arbitrary executable launch, changing the managed SID from a phone, automatic sign-in, or Wake-on-LAN is included. Use on a trusted LAN. Install packages are not code-signed and dependency transitive hashes are not locked; independent security review and real Windows service lifecycle tests are still required before broader distribution.

- Service SID isolation: https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_sid_info
- Named-pipe permissions: https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-security-and-access-rights
- Windows session logoff: https://learn.microsoft.com/en-us/windows/win32/api/wtsapi32/nf-wtsapi32-wtslogoffsession
- PyInstaller: https://pyinstaller.org/en/stable/requirements.html
