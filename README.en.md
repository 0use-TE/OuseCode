# OpenCode Android

[简体中文](README.md)

A standalone Android application bundling PRoot, Ubuntu 24.04.5 ARM64, Node.js 24.22.1, Git, and OpenCode 2.3.2. It displays OpenCode's own agent interface in a WebView. Termux, a separate computer/server, and Android root are not required.

The display name is OpenCode; the internal project directory remains OuseCode. The launcher uses OpenCode's official dark logo with its original proportions and gray/white palette, adapted to Android icon masks. The source artwork is `artwork/opencode-logo-dark.svg`; this is an independent Android host project.

The first launch extracts the environment; later launches reuse it. Model requests, package installation, and online updates require a network. The independent package ID is `io.github.ouse.ousecode`, so it can coexist with DSH Ha.

## Usage

1. Install `dist/OpenCode-0.2.4-arm64.apk`, open OpenCode, and wait for initial extraction. The app automatically pairs with its bundled server using OpenCode's official one-time flow; no server address needs to be entered.
2. Select a project and configure a model in OpenCode. The default workspace is `/root/workspace`.
3. OpenCode fills the app content area. Tap the edge handle to reveal settings, then tap again to open the panel. Drag the handle to either edge; its position is retained, and the button collapses after 3 idle seconds. Physical keyboards can use `Ctrl+Alt+O` for settings and `Ctrl+Shift+R` to reload.
4. Settings has Terminal, Android access, Runtime, and About pages. Terminal opens Ubuntu and edits its display name; Runtime provides reload, restart, OpenCode update, logs, and stop. The display name changes only the Bash prompt (default `ouse@OpenCode`), keeping root and `/root` unchanged. New terminals pick it up automatically; run `source ~/.bashrc` in an existing terminal. Names accept 1–40 letters, numbers, spaces, and `_ . @ -`. Existing prompt configuration lines are preserved; `.bashrc` sources the separate `.ousecode/prompt.bash` file.
5. An Android foreground service manages the runtime when the screen is closed. Returning from the launcher or notification reuses the existing page and preserves its state without replaying startup. Stopping it ends active tasks while retaining projects and sessions.

The host converts classified two-finger trackpad pans to wheel input for conversations, model lists, and other web scroll areas. Ordinary touchscreen input is unchanged. Trackpad hover is normalized as mouse input so cursor shapes can switch between dividers and content.

`Ctrl+Alt+T` toggles the temporary Ubuntu terminal from the OpenCode webpage or the terminal itself. Settings also opens it. This is interactive Bash inside PRoot, starting in `/root/workspace` and sharing project files with OpenCode, rather than an Android shell. It supports physical keyboards, IME, Tab completion, arrow-key history, Ctrl+C, and long-press copy/paste (`Ctrl+Shift+V` to paste). Hiding retains the working directory and jobs; × or `exit` ends the session. Restarting/stopping the environment ends it too. OpenCode's own shortcuts, including Ctrl+backtick, remain unchanged. Termux terminal components are bundled in the APK; no Termux app installation is needed.

The black-and-white rain and title decoding are drawn natively with Kotlin Canvas; startup does not load an animation webpage. Extraction shows real stage progress, while stages without a measurable percentage use an indeterminate bar. Once the first web frame is ready, a fixed 700 ms transition fades out the boot screen and fades in the page with subtle scaling. Android's animation duration scale still applies. The rain pauses when hidden or in the background. No first-run name dialog interrupts entry; change the default anytime in settings.

## Android access

This optional feature defaults to off. Normal Ubuntu use does not require Shizuku. To access the Android host, install and start [Shizuku](https://shizuku.rikka.app/), then authorize OpenCode in Settings → Android access. Its initial system authorization needs user consent; Shizuku must be started again after a device reboot.

- **Off** rejects Android tool calls and ends open Android terminals and active Android commands.
- **Ask each time** uses a native Android approval card for every AI call to `android_exec`. Manually opening an Android terminal asks once for that interactive session.
- **Smart** automatically allows only recognized status queries. File/system changes, installation, process control, sensitive reads, scripts, redirection, pipelines, and compound or unrecognized commands need approval for that exact call. The native dialog shows the full command and AI-provided purpose. Denial, 30 seconds without a reply, or changing modes cancels it; approval is never remembered. Local rules make this decision, rather than trusting the AI to declare its own command safe.
- **Run directly** adds no confirmation for AI calls or manually opening Android terminals. Initial Shizuku authorization is still required.

AI uses the MCP tool `android_exec` with `command`, optional `purpose`, and `timeoutSeconds` (default 30, range 1–120). Results include stdout, stderr, exit code, and timeout status. Normal project commands continue to run in Ubuntu. Android commands start in `/data/local/tmp` with Shizuku's actual identity: typically shell UID 2000 in ADB mode or UID 0 in Root mode. This does not grant Root automatically.

Open Android terminal in settings provides a separate interactive Android shell. Smart and Ask modes confirm once when entering a manual session; typed commands are not intercepted individually. Hiding retains the session; × ends it. Ubuntu and `Ctrl+Alt+T` keep their existing behavior. Mode changes reload OpenCode configuration without restarting Ubuntu, but cancel OpenCode's pending permission requests and forms.

Ask and Smart approvals are enforced for the current call by the Android host; OpenCode's persistent approvals cannot bypass them. Ubuntu tool permissions are unchanged. Smart recognizes a limited query grammar, not full shell semantics, and asks for everything it cannot recognize. PRoot is not a security boundary for hostile code; installed programs share the application's identity. The MCP endpoint listens only on loopback with random credentials, and the host rejects execution in Off mode.

The card shows the command, purpose, time remaining, and Deny / Allow once. Its deadline is 30 seconds after creation; switching apps or redisplaying cannot reset it, and late approval is rejected. With display-over-other-apps permission the card appears above other apps; otherwise the same card appears inside OpenCode. An approval notification also provides the actions and countdown. Settings → Android access links to overlay and approval notification settings. Denial, expiration, mode changes, and stopping the environment remove the corresponding presentation.

Requires Android 8.0+, ARM64, and at least 1.5 GB of available space for initial setup. Device power management still controls background survival. This preview targets SDK 28 to support Linux executables inside PRoot and is intended for APK sideloading, not Google Play publication.

## Updates and data

- Settings can update OpenCode independently of the APK. Updating stops active tasks and retains an `opencode.previous` executable.
- Major versions can migrate session databases. An old executable is not a data backup; export important projects and sessions before upgrading.
- Plugins use OpenCode's own configuration and installation system. Plugin-specific Web UI support depends on the plugin.
- PRoot and Android host updates require a new APK. This preview does not automatically migrate different Ubuntu base versions.
- Projects, credentials, and sessions live in private `files/home`; executable files in `files/engine`; Ubuntu in `files/runtime/rootfs`. Uninstalling removes this data; replacing the APK preserves it.
- The server listens only on `127.0.0.1` with a random local password. The WebView supplies credentials only to the local server. External sign-in opens in a browser. No model accounts or API keys are preconfigured.

## Building

Windows sources are in `D:\Projects\OuseCode`. The current WSL Ubuntu-22.04 build copy is `/home/ouse/projects/OuseCode`. Dependencies: JDK 17, Android SDK 36, Gradle 9.3.1, AGP 9.1.0, and NDK 28.2.13676358. Preview APKs use the build account's local Android debug keystore; retain that key to install future APK updates over this build.

With runtime assets under `app/src/main/assets/runtime` and native libraries under `app/src/main/jniLibs`:

```bash
bash tools/build.sh
```

For the current Windows/WSL setup:

```powershell
wsl -d Ubuntu-22.04 -- bash /mnt/d/Projects/OuseCode/tools/sync-build.sh
```

`tools/prepare_runtime.py` composes the runtime using verified DSH Go build caches for Ubuntu Base, additional Ubuntu packages, Node, and PRoot. It copies no DSH engine or user data. OpenCode comes from its official Linux ARM64 distribution. Input metadata and hashes are recorded in `runtime-inputs.json` and `ubuntu-packages.lock.json`.

```bash
python3 tools/prepare_runtime.py \
  --dsh /home/ouse/projects/dsh-Go \
  --source /home/ouse/projects/OuseCode \
  --engine-archive /path/to/opencode-linux-arm64.tar.gz \
  --engine-version 2.3.2
```

## Device checks

Use `-e host true` for host regressions: three background/launcher returns and a notification open must reuse the Activity, page, and runtime. An isolated test page checks two-finger scrolling, ordinary touch, and divider cursor restoration without inspecting user conversations.

Use `-e approvals true` on Android 10+ with authorized Shizuku, overlay, and notification permissions to check background approval, notification allow/deny, countdown, expired approval, stale actions, and mode-change cancellation. It executes harmless test commands and restores the original execution mode.

The custom instrumentation exercises the actual bundled ARM64 PRoot, Ubuntu, and OpenCode. It verifies the display name and adaptive icon, startup, API authentication, the web page, one-time pairing, Node/npm/Git, CA certificates, and files retained across a restart. It uses no model credentials or paid requests. Add `-e ui true` to check the native boot transition, full-page WebView, floating controls and drag/collapse, keyboard access, prompt editing, login shells, and unchanged root identity (UI automation requires Android 10+). Add `-e extended true` to check guest DNS/HTTPS and the current version's in-app update/restart workflow.

Add `-e terminal true` to check the real Ubuntu PTY, shared workspace, Unicode input/output, Tab, arrow history, Ctrl+C, shortcut routing, retained hidden sessions, and child-process cleanup on close.

Basic checks also verify that Android supplementary groups resolve to Ubuntu group names, interactive/login Bash starts without unnamed-group warnings, and restarting adds no duplicate entries. Startup adds local names only for missing group IDs, preserving existing groups and members without changing Android permissions.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r device-tests/build/outputs/apk/debug/device-tests-debug.apk
adb shell am instrument -w io.github.ouse.ousecode.test/io.github.ouse.ousecode.SmokeInstrumentation
adb shell am instrument -w -e ui true io.github.ouse.ousecode.test/io.github.ouse.ousecode.SmokeInstrumentation
```

The separate `device-tests` APK targets SDK 36. After authorizing Shizuku, add `-e android true` to check loopback MCP authentication, Off-mode rejection, policy reload, actual Shizuku UID, stdout/stderr, timeout, and the interactive Android PTY, including manual confirmation, cancellation, and session reuse (dialog checks require Android 10+). Smart checks cover query recognition, dangerous/compound command classification, the native approval dialog, no test file before approval or after denial, one-call consent, and cancellation on mode change. Dangerous examples are classified without executing them. Tests restore the original execution mode. They do not make paid model requests or establish actual model OpenCode permission-dialog behavior.

Version 0.2.3 passed the basic, settings UI, Ubuntu terminal, and Android access checks on a Lenovo TB378FC running Android 15 ARM64. Shizuku used UID 2000. Extended in-app update and DNS/HTTPS checks passed on the earlier build and were not repeated for this change. APK builds, bundled asset/native-library hash checks, and Android lint passed.

Subscription sign-in, third-party plugins, and extended background operation require separate device verification and are not established by the smoke checks.

## License and attribution

Application sources are licensed under AGPL-3.0. `TarExtractor.kt` is adapted from [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go), with the package renamed. Android PRoot libraries reuse that project's built Termux binaries. OpenCode is MIT-licensed; Ubuntu packages retain their individual licenses. See `LICENSE`, `app/src/main/assets/NOTICE.txt`, and package license files in the runtime.

The interactive terminal uses Termux v0.118.3 terminal-emulator and terminal-view. Sources, version pins, and attribution are under `third_party/termux`; the APK includes the full GPL-3.0 and Apache-2.0 license texts.

Android access uses Shizuku API/provider 13.1.5 (MIT); the APK includes its full license.

OuseCode is independent of the OpenCode, Canonical, and Termux teams.
