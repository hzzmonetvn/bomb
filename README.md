# Bomb

Bomb is an Android system toolbox for HyperOS. The current repository contains
the Android Compose/MIUIX application shell and live device readers; privileged
services and the typed `bombd` backend are staged in the architecture plan.

The project is Android-only. The former WebAssembly/browser preview and the
unprivileged execution-mode option have been removed.

## Build

```sh
./gradlew --console=plain :app-preview:assembleDebug
```

Debug APK:

```text
app-preview/build/outputs/apk/debug/app-preview-debug.apk
```

The package is `com.hzzmonet.zkbomb`, minSdk 33, targetSdk 37. Release builds
currently use debug signing and must be switched to a stable key before ROM
integration.

## Android behavior

- Settings, appearance, monitor selection, freeze list, rules, bridge sources
  and VoIP selection persist across app restarts.
- Android Back pops Bomb's internal stack before the Activity can exit.
- Predictive-back gesture progress drives the in-app page animation; at a tab
  root Android owns the return-to-Home animation.
- Execution mode is either ROM-integrated or Root. Missing privilege is reported
  as a backend/capability error, not as a separate mode.

## System integration and patches

Read [`docs/SYSTEM_PATCHES.md`](docs/SYSTEM_PATCHES.md) first. It is the
feature-by-feature contract for:

- SELinux types, labels and narrow allow rules;
- framework/privileged-permission work;
- `init` lifecycle and safe restoration;
- ROM-native predictive-back restoration for HyperOS;
- post-flash verification.

Additional design documents:

- [`docs/ROM_INTEGRATION.md`](docs/ROM_INTEGRATION.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/BOMB_PLAN.md`](docs/BOMB_PLAN.md)

SELinux must remain enforcing. The UI must never receive unrestricted root,
arbitrary shell, arbitrary sysfs/file writes or generic property access.
