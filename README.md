# LocalTask — local-network prototype 0.4

An Android remote process manager with a Python agent for Linux, Windows and macOS.
This is source code for a first development milestone, not a packaged or production-ready app.

Version 0.3.1 fixes a crash when opening **Scan computer QR** by explicitly including AndroidX Core in the app. Pull the update, sync Gradle, and rebuild/install over the existing app with the same signing key to retain saved computers.

## Computer actions (0.4)

Connect to a computer, tap **Computer actions**, choose **Log off**, **Shut down**, or **Restart**, and review the named computer, target session, and warning before confirming. Update **both the Android app and the agent folder** (including `computer_actions.py`), then restart the agent. No new Python dependencies are needed. Keep the existing `~/.localtask` directory: do not run `init` again. Saved credentials and QR profiles remain valid. Older agents continue to support process management and show an update message for computer actions.

These controls use the existing paired access key. Anyone you share the key/QR with can also request computer actions on an updated agent. Shutdown and restart affect the entire computer and other signed-in users. Log off is limited to the desktop session verified by the agent; the phone cannot supply a username, session ID, or command.

| Platform | Log off | Shut down / Restart |
| --- | --- | --- |
| Linux | Requires systemd-logind and an active local X11/Wayland session owned by the agent user. `loginctl terminate-session` ends that session's processes **without save prompts**. SSH/text sessions and other users' sessions are refused. | Uses systemd 248+ `systemctl --no-ask-password --check-inhibitors=yes poweroff/reboot`. Local logind/polkit policy must authorize the account. Blocking inhibitors are respected. |
| macOS | Requires the agent user to own the foreground console desktop. Sends a System Events logout request. | Requests System Events shutdown/restart from that same foreground user's agent. macOS may require Automation permission for the launcher to control System Events. Local save/permission dialogs can block or cancel actions. |
| Windows | Signs out the agent's own interactive session. Unavailable when the agent is running as a Windows service in session 0. | Uses the Windows shutdown tool with `/t 0` and **without `/f`**. The account needs the **Shut down the system** user right. Applications may block the request. |

Keep the agent as a standard desktop user. LocalTask never invokes sudo, requests elevation, changes OS security policy, bypasses shutdown inhibitors, or automatically installs permission grants. If denied, inspect the computer's local permissions; a Linux user service may have different authorization from an interactive terminal. No blanket passwordless sudo rule is required or recommended. On Mac, check **System Settings → Privacy & Security → Automation** and any on-screen prompts. Windows service accounts may lack shutdown rights even when a desktop user has them.

The final confirmation expires after 60 seconds and can be used only once. The agent rechecks the session before dispatch, rejects overlapping actions, and applies a one-minute cooldown after dispatch. It does not queue actions for the next startup. Preparing/cancelling a confirmation never runs a command. A successful response means the OS request was sent, **not** that logout/shutdown/restart completed. If the connection ends or the OS times out, the app reports an uncertain outcome and does not automatically retry. Check the computer before sending another request.

Logging off can stop the user agent. After restarting, it may not be reachable until someone signs in and its service starts again. A powered-off computer cannot be turned back on through these controls; Wake-on-LAN is not implemented. Save work before confirming any action.

Protocol: authenticated `GET /v1/computer-actions` lists support and restrictions; `POST /v1/computer-actions/prepare` accepts only `{"action":"logout|shutdown|restart"}` (one literal action) and returns a short-lived confirmation; `POST /v1/computer-actions/confirm` accepts only `{"confirmation":"..."}`. Session targets and executable arguments are resolved locally. Confirmations are stored only in memory and disappear when the agent restarts. Existing process APIs are unchanged.

Validation: automated tests mock all OS action dispatches. They cover authentication, strict request schemas, confirmation expiry/replay, concurrency/cooldown, session ownership, fixed platform commands, rejected requests, and uncertain timeouts. Actual logout/shutdown/restart on physical Windows, Mac, and Linux machines still needs testing after saving work.

## What this version does

- Saves named computer profiles with encrypted credentials on Android; tap a computer to connect.
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

**Credentials persist across agent restarts.** `init` creates the certificate and token once and refuses to overwrite an existing configuration. `serve` only reads them; `pair` only displays them. Use the same user account and `--config` directory each time. `revoke` explicitly changes only the token; replacing the certificate changes the fingerprint.

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

Choose **Add computer**, give it a name (for example “My Mac”), and enter its IP, port, certificate fingerprint and access key. Tap **Save and connect**. After closing/reopening the app or rebooting your phone, tap that computer on **Your computers** to reconnect. Profiles are saved after input validation, even if the computer is offline; a saved profile is not proof that a connection succeeded.

**Edit / forget** lets you change an IP address or update credentials after an explicit agent rotation. Forgetting a profile removes it from this phone; it does not revoke the agent token. The app never silently accepts a changed certificate fingerprint. A reserved IP on your router helps avoid address changes.

All profile fields are encrypted using AES-GCM with a key in Android Keystore. The app disables screenshots, backups, and autofill/view-state saving for credential fields, and pauses polling when backgrounded. Credentials survive normal app updates signed with the same key. Uninstalling the app or clearing app data removes saved profiles. If the encryption key is lost or data cannot be decrypted, the app reports the problem instead of overwriting profiles; **Forget all saved computers** offers explicit recovery. No biometric prompt is required for reconnecting.

Update the installed app with the same package name and signing key to retain data. The namespace remains `com.solemntreeapps.localtask`; this release is version 0.3 (version code 6).

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

Initial version 0.1 development result: **10 passed, 2 skipped**. The two live process tests were skipped because this execution environment cannot resolve its own PID through `/proc`. This is not evidence of live Linux process management working here. Android compilation/device operation, Windows/macOS behavior, background-service installation and the full phone-to-computer path remain unverified. Run the tests on your computer before enabling background operation.

### Saved-profile validation (0.2)

The user reported successful macOS and Linux operation after the saved-profile update. Windows remains untested. Device tests in `ProfileStoreTest` use a separate test-only preferences file and Keystore alias; they cover reload after store recreation, editing/deletion, encrypted-at-rest contents, tamper detection, missing-key recovery and preserving data on load failure. Run from `android` with an emulator or device attached:

```sh
./gradlew connectedDebugAndroidTest
```

Version 0.2 validation: **12 agent tests passed, 1 skipped** (the runtime could not expose a live child PID). This includes a new check that credentials are identical when read from separate agent processes. All three Android production Java sources compiled against Robolectric’s Android 15 API implementation using the Eclipse Java compiler in Java 8 compatibility mode. This is a source/type check, not a Gradle APK build. Device-test sources passed a Java syntax check; the instrumentation tests could not be executed here because no Android SDK/emulator is available. Manual checks: save two computers; force-close/reopen the app; connect to each; edit only one IP; forget one and verify the other remains; restart the Mac agent and reconnect without re-entering credentials. Agent credentials are unchanged by this update.

### System-bar layout fix (0.2.1)

Every activity screen now uses one shared safe-area container. On Android 11+ it applies the current system-bar, display-cutout and keyboard insets as padding outside the content, including outside the connection screens' scroll view. Padding is reassigned on each inset update, so it does not accumulate when switching screens or showing/hiding the keyboard. Android 8–10 retain the platform's existing decor fitting. The light app theme requests dark system-bar icons.

The fix addresses the reported status-bar overlap on Android 17 without opting out of edge-to-edge layout or using a guessed status-bar height. Java source/type compilation passed against the Android 15 API implementation; Android 17 visual/device verification is still pending. Check the saved-computer list, add/edit form and process list in portrait and landscape, with the keyboard open and closed, using gesture or three-button navigation. Update the existing app with the same signing key to retain saved computers.

### Startup crash fix (0.2.2)

Version 0.2.1 requested the window inset controller before installing its content view. Android's PhoneWindow implementation dereferences the decor view inside that call, so checking the returned controller for null does not protect against a missing decor view. Version 0.2.2 requests the controller only after `setContentView` installs the decor. The system-bar/cutout/keyboard padding remains in place; profile storage is unchanged.

Added `MainActivityLaunchTest` to launch the real activity and exercise initial inset dispatch. Run it on a connected emulator/device with:

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solemntreeapps.localtask.MainActivityLaunchTest
```

The test does not clear or edit saved computers. Java production-source compilation passed; the launch regression test and Android 17 runtime check still require a connected device. Install the update with the same signing key without uninstalling to preserve profiles.

### Launcher icon (0.2.3)

LocalTask now uses the mint connected-computer tree artwork as its Android launcher icon. The original full-color image is stored at `android/app/src/main/res/drawable-nodpi/localtask_icon.png`. Adaptive-icon resources provide a navy background and inset the artwork by 16% on each side for circular and rounded launcher masks. Both normal and round manifest icon entries use the adaptive resource. Android 8.0 is the app's minimum supported version, so all supported devices can load the API 26 adaptive icon.

The selected artwork was created with the built-in image-generation tool. Design prompt: “A clean mint connected-computer tree symbol on a midnight navy background, no text, readable at launcher-icon sizes.” Experimental transparent exports were not included. This version uses the full-color icon; a custom monochrome themed-icon layer is not included.

Resource XML and references were checked; launcher rendering on an Android device still needs verification. Rebuild and install over the existing app with the same signing key to retain saved profiles. If the launcher caches the previous icon, restart the launcher or phone rather than uninstalling the app.

### QR sharing and in-app agent setup (0.3)

**Send a computer:** on Your computers, tap **Edit / forget** for the saved computer, then **Share saved computer by QR**. Confirm the access warning to display the code. Unsaved edits are not included. The QR is hidden if the app is backgrounded; screenshots remain disabled. Repeat for additional computers.

**Receive a computer:** on a second phone with LocalTask 0.3 or later, tap **Scan computer QR**, allow camera access, scan the first phone's screen, review the name/address/fingerprint, then tap **Save computer**. Import does not connect or issue commands. Exact duplicate connection credentials are skipped. Differing profiles create new entries with local IDs instead of overwriting existing entries. A newly imported computer uses the receiving phone's encrypted profile storage. If camera permission is denied, it can be enabled through Android app settings; manual entry remains available.

A QR contains the computer's long-lived access token and certificate fingerprint. It is a credential, not a public invitation or an expiring one-time code. Anyone who scans it can manage that computer when reachable. The receiving phone must still be on the trusted local network. No cloud upload or gallery export is performed. Camera frames are decoded on-device and barcode images are not saved. Revoke on the agent and restart it to invalidate the shared token; **all phones using that token must then be updated**. Editing or removing a profile on one phone does not change another phone's copy. Per-device permissions and revocation are future work.

**Agent setup guide:** the home screen now contains **Agent setup guide**, with offline Mac, Linux and Windows pages explaining where to obtain the code, Python setup, how to find the local IP, first-run commands, pairing, firewall configuration, subsequent starts and troubleshooting. It links to this repository, Python downloads and the full README. Because the repository is private, downloaders need a GitHub account with repository access, or source files supplied by the owner. QR access to a running agent does not grant GitHub access. No prebuilt desktop installers are offered or implied.

Validation: nine JVM tests passed, including actual QR pixel encoding/decoding, Unicode and credential round trips, local-ID generation, invalid/missing fields, public-address rejection, malformed/oversized inputs and unsupported versions. Production Java sources compiled against the Android API and ZXing jars; manifest XML parsed. A full Gradle APK build, camera permission flow, physical phone-to-phone scanning and tutorial layout still require device testing. No agent update is needed.

To rerun the new tests with Gradle:

```sh
cd android
./gradlew testDebugUnitTest
```

## Architecture and room to expand

Android UI → `AgentClient` → pinned HTTPS → `/v1` agent API → `Processes` → operating system.

`GET /v1/processes` returns `computer`, `os`, `sampled_at`, and `processes` containing `pid`, `created`, `name`, `owner`, `cpu`, `memory`, `manageable`.

`POST /v1/commands` accepts exactly `{ "pid": 123, "created": 1700000000.0, "action": "kill" }` or action `terminate`. Every endpoint requires `Authorization: Bearer <token>`. No arbitrary command/shell endpoint exists. There is no automatic command retry or offline queue.

Next milestones:

1. Verify a real Android-to-computer connection and build/install workflow.
2. Add QR provisioning directly from agents, per-device credentials/revocation and connection discovery.
3. Add incremental list updates, better UI state restoration and richer command outcomes.
4. Package and sign desktop installers; verify service lifecycle across all three OSes.
5. Add remote access through a private VPN first, or an outbound relay with explicit device identity and authorization. Keep the process API; extend the transport and authentication layers. Do not publish this prototype's port to the internet.

Before a broader release: independent security review, per-device authorization, request limits/rate limiting, dependency locking with hashes, audit retention, installer ACLs, update signing and platform/device tests. Current dependencies are directly version-pinned but their transitive versions are not locked. This is a personal development prototype, not an audited remote-management product.

## References

- Android build compatibility: https://developer.android.com/build/releases/agp-8-9-0-release-notes
- Process API and platform behavior: https://psutil.io/api/
- PID reuse handling: https://psutil.io/faq/

- Android Keystore AES-GCM reference: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec

- Edge-to-edge and window insets: https://developer.android.com/develop/ui/views/layout/edge-to-edge

- QR scanner library: https://github.com/journeyapps/zxing-android-embedded
- QR encoding/decoding: https://github.com/zxing/zxing
