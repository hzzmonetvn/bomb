# Bomb Agent Instructions

## Project

- App name: Bomb
- Package: `com.hzzmonet.zkbomb`
- Bomb is a privileged/system-level Android toolbox.
- Primary design language: MIUIX / HyperOS with selective Material 3 Expressive patterns.
- SELinux must remain enforcing.

## Architecture

Keep privileged functionality separated into:

1. UI application
2. domain/data layer
3. Bomb system/core service
4. AIDL/Binder API
5. minimal native daemon `bombd` where required
6. device/vendor backends

The UI must never be given unrestricted root access.

Do not expose generic privileged APIs such as:

- arbitrary shell execution
- arbitrary sysfs writes
- arbitrary file writes
- arbitrary property writes

Use typed APIs with validation.

## Explicitly Out of Scope

Do NOT implement unless directly requested:

- Bomb Guard
- snapshot/rollback/safe mode
- HyperOS Tweaks
- SystemUI modifications
- launcher modifications
- status-bar modifications
- animation modifications
- Diagnostics
- crash center
- ANR viewer
- tombstone viewer
- Perfetto-lite

## Main Features

Current product scope:

- Task Manager
- Bomb Stats
- Stats Overlay
- Freeze Engine
- App Control
- Log Governor
- Bomb Bridge
- Performance Profiles
- Battery Lab
- Wakelock / Job / Alarm observation
- Network Control
- Call Recording
- Bomb Rules

## Code Quality

- Write complete production-quality code.
- Do not introduce fake data.
- Do not leave placeholder implementations presented as complete.
- Handle null/error/unsupported states.
- Validate all privileged inputs.
- Never block the Android main thread with I/O.
- Prefer coroutines and Flow for asynchronous Android work.
- Avoid unnecessary dependencies.
- Do not refactor unrelated code.
- Respect existing repository conventions.
- Keep device-specific code behind backend interfaces.

## System Security

- SELinux remains enforcing.
- Never add broad SELinux allow rules.
- Never blindly apply `audit2allow` output.
- Grant only the access required for a specific operation.
- Validate Binder callers.
- Use signature/internal permissions where appropriate.
- Never log secrets or recorded audio content.

## Telemetry

- Do not perform expensive polling while there are no active consumers.
- All monitor intervals must be configurable.
- Expensive collectors must be lifecycle/subscriber aware.
- Never fabricate unsupported hardware metrics.

## Device Compatibility

All hardware-dependent features require explicit capability detection.

Examples:

- GPU telemetry
- GPU control
- thermal sensors
- charge control
- deep freeze backend
- HyperOS bridge
- call/VoIP recording

Unsupported functionality should produce an explicit unsupported state.

## Testing

For each non-trivial feature, add appropriate unit tests.

Priority test targets:

- FreezePolicyEngine
- Rule Engine
- capability detection
- telemetry calculations
- profile restoration
- validation logic

Before completing a task:

1. run relevant tests
2. run lint/build checks available in the repository
3. report what was actually run
4. report failures honestly
