# LocalTask — local-network prototype 0.1

An Android remote process manager with a Python agent for Linux, Windows and macOS.
This is source code for a first development milestone, not a packaged or production-ready app.

## What this version does

- Connects to one computer at a time by private IPv4 address; switch computers by disconnecting.
- Refreshes processes every three seconds while the Android screen is active.
- Displays name, PID, owner, resident memory and sampled CPU usage; search and sort.
- Sends a termination request on Linux/macOS, or an explicitly confirmed force-stop on any platform.
- Uses HTTPS with an exact certificate fingerprint pin and a random 256-bit access key.
- Limits commands to processes owned by the agent's user and protects the agent, its ancestors and a few core PIDs.
- Checks PID and process creation time before sending a signal.
- Includes Linux user-service and macOS LaunchAgent templates, plus an optional Windows service adapter.

CPU can exceed 100% on multicore workloads (100% represents one core). The first reading says “sampling.” Inaccessible processes are omitted. A successful command means a signal was sent, not that the process has exited. On Unix, SIGTERM is a termination request, not a GUI “save and close” instruction. Windows only exposes force-stop in this prototype.

## 1. Start the computer agent

Use Python 3.12 and a **standard desktop user account**. Do not run the agent as root, Administrator or LocalSystem. No installation commands below should be run by the Android app.

Linux / macOS, from the `agent` directory:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python agent.py init
.venv/bin/python agent.py pair
.venv/bin/python agent.py serve --host 192.168.1.20
```

Windows PowerShell, from the `agent` directory:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe agent.py init
.\.venv\Scripts\python.exe agent.py pair
.\.venv\Scripts\python.exe agent.py serve --host 192.168.1.20
```

Replace `192.168.1.20` with that computer's actual local IPv4 address. `--host` defaults to loopback; it must be set to your LAN address for phone access. Use your router's DHCP reservation to keep the address stable. The agent accepts specific RFC1918 IPv4 bind addresses, not wildcard or public addresses. It does not automatically discover computers or configure routers.

The `pair` command displays `fingerprint` and `token`. Copy these directly from your computer into the app. This is manual provisioning using a long-lived secret; it is **not yet a one-time pairing exchange**. Anyone holding the token and fingerprint can access this agent if they can reach it. Do not send credentials in screenshots or commit them to source control.

Configuration is in your home directory's `.localtask` folder. Unix files are created with owner-only permissions. On Windows, verify the folder's Security properties limit access to your account and required system administrators. Keep the private key and token private.

Allow inbound TCP port 8765 on your computer's firewall **only from your trusted local subnet and only on the private/home network profile**. Do not forward the port on your router. Being on Wi-Fi alone does not guarantee reachability: guest networks often isolate devices. These controls, plus binding to the LAN address, define this prototype's local deployment; the agent does not independently restrict every incoming source address.

To revoke access:

```sh
python agent.py revoke
```

Use the virtual environment's Python as above. **Restart the agent immediately**: the running process keeps the old token in memory until restarted. Run `pair` again to provision the replacement token.

## 2. Build the Android app

The Android namespace and application ID are `com.solemntreeapps.localtask`.

The `android` directory is an Android Studio/Gradle project using Java and native Android widgets. It needs:

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Gradle 8.11.1
- Android Gradle Plugin 8.9.2 (specified in the build)
- Android 8.0 or newer on the phone

The Gradle wrapper binary is not bundled. With Gradle 8.11.1 installed, run from `android`:

```sh
gradle wrapper --gradle-version 8.11.1
```

Then open `android` in Android Studio, let dependencies sync, and use Run with your phone connected and USB debugging enabled. Alternatively:

```sh
./gradlew assembleDebug
```

On Windows use `gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can install it on your connected phone. Dependency downloads require internet during setup; app-to-agent operation does not require cloud services.

Enter the computer IP, port, certificate fingerprint and access key. Credentials stay only in memory. Rotation, app recreation or closing the app requires reconnecting; multiple saved profiles and encrypted credential storage are future work. The app disables screenshots and Android backups, and pauses polling when backgrounded.

Target SDK 35 is intentionally pinned for this initial development build. Before publishing or raising the target SDK, check current Android local-network permissions and Play requirements.

## 3. Optional background startup

Get foreground operation working first. The following service integrations are supplied but **have not been tested on their target operating systems**. They are user-scoped deliberately; management of other users' processes and privileged system processes is outside this version's scope.

### Linux

Edit every placeholder and the IP in `services/localtask.service`. Copy it to `~/.config/systemd/user/localtask.service`, then:

```sh
systemctl --user daemon-reload
systemctl --user enable --now localtask.service
journalctl --user -u localtask.service
```

This starts for your user session. To remove startup: `systemctl --user disable --now localtask.service`, delete that unit file, and run `systemctl --user daemon-reload`. No automatic pre-login setup is included.

### macOS

Edit every path and IP in `services/com.localtask.agent.plist`. Place it in `~/Library/LaunchAgents/com.localtask.agent.plist`, then:

```sh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.localtask.agent.plist
```

Logs use the paths you configured in the plist. This is a login LaunchAgent, not a root LaunchDaemon. To stop and remove startup:

```sh
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.localtask.agent.plist
```

Then delete the plist.

### Windows

Foreground mode is the easiest first test. The optional `agent/windows_service.py` uses pywin32; install pywin32 into the same environment and follow its installation requirements for your Python distribution. Copy `windows-service.example.json` to `windows-service.json` alongside the adapter, and replace its paths/IP.

From an elevated terminal, register the adapter using that environment's Python:

```powershell
.\.venv\Scripts\python.exe windows_service.py install
```

Before starting it, use Windows Services → LocalTask → Properties → Log On to select a **standard user account that owns the processes you want to manage**. Give that account access to Python, the project, its configuration and “Log on as a service” where required. Set the startup type as desired. Do not start it with the default LocalSystem identity: the adapter rejects elevated accounts. Service-host Python DLL visibility and pywin32 setup may need adjustment on the target machine. The Windows Event Log receives startup failures.

Stop via Services. Remove registration from an elevated terminal with:

```powershell
.\.venv\Scripts\python.exe windows_service.py remove
```

Service mode and interactive mode must not bind the same port at once.

## Tests and current validation

From `agent`, install `requirements-dev.txt` and run:

```sh
python -m pytest tests -q
```

Tests cover missing/wrong credentials, invalid commands, protected processes, ownership checks, stale identities, Windows termination semantics, credential generation, command outcomes and actual HTTPS requests. TLS integration uses a simulated process provider; the networking and certificate handling on the Python server are real. Android certificate-pinning behavior still needs device testing.

Two additional tests enumerate real processes and terminate only a child process spawned by the test. They automatically skip if the runtime lacks a usable process namespace.

Development environment result: **10 passed, 2 skipped**. The two live process tests were skipped because this execution environment cannot resolve its own PID through `/proc`. This is not evidence of live Linux process management working here. Android compilation/device operation, Windows/macOS behavior, background-service installation and the full phone-to-computer path remain unverified. Run the tests on your computer before enabling background operation.

## Architecture and room to expand

Android UI → `AgentClient` → pinned HTTPS → `/v1` agent API → `Processes` → operating system.

`GET /v1/processes` returns `computer`, `os`, `sampled_at`, and `processes` containing `pid`, `created`, `name`, `owner`, `cpu`, `memory`, `manageable`.

`POST /v1/commands` accepts exactly `{ "pid": 123, "created": 1700000000.0, "action": "kill" }` or action `terminate`. Every endpoint requires `Authorization: Bearer <token>`. No arbitrary command/shell endpoint exists. There is no automatic command retry or offline queue.

Next milestones:

1. Verify a real Android-to-computer connection and build/install workflow.
2. Add encrypted saved computer profiles, QR pairing, per-device credentials/revocation and connection discovery.
3. Add incremental list updates, better UI state restoration and richer command outcomes.
4. Package and sign desktop installers; verify service lifecycle across all three OSes.
5. Add remote access through a private VPN first, or an outbound relay with explicit device identity and authorization. Keep the process API; extend the transport and authentication layers. Do not publish this prototype's port to the internet.

Before a broader release: independent security review, per-device authorization, request limits/rate limiting, dependency locking with hashes, audit retention, installer ACLs, update signing and platform/device tests. Current dependencies are directly version-pinned but their transitive versions are not locked. This is a personal development prototype, not an audited remote-management product.

## References

- Android build compatibility: https://developer.android.com/build/releases/agp-8-9-0-release-notes
- Process API and platform behavior: https://psutil.io/api/
- PID reuse handling: https://psutil.io/faq/
