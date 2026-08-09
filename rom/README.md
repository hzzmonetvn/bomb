# Bomb ROM overlay

This directory contains the ROM-owned half of Bomb. It is not a Magisk module
and does not expose a root shell.

Install/merge these artifacts into the matching partitions:

| Source | ROM destination |
| --- | --- |
| built `app-preview-*.apk` | `/system_ext/priv-app/Bomb/Bomb.apk` |
| built `rom/system_ext/bin/bombd` | `/system_ext/bin/bombd` |
| `permissions/privapp-permissions-com.hzzmonet.zkbomb.xml` | `/system_ext/etc/permissions/` |
| `rom/system_ext/etc/init/bomb.rc` | `/system_ext/etc/init/bomb.rc` |
| `rom/system_ext/etc/sysconfig/*.xml` | `/system_ext/etc/sysconfig/` |
| `rom/system_ext/etc/bomb/build.prop.fragment` | merge into `/system_ext/build.prop` |
| `rom/framework/framework-capabilities.prop.fragment` | merge into `/system_ext/build.prop` (markers remain `0` until patched) |
| `sepolicy/private/*` | merge into platform/system_ext policy sources, then compile |

Do not copy the policy text as an opaque allow-rule blob. The image builder must
compile the type declarations, property contexts and seapp assignment together;
if the target cannot compile policy, Bomb ROM integration fails rather than
shipping an APK that falsely reports capabilities.

The first executable vertical slice is Log Governor:

```text
app setLogLevel(enum)
  -> BombCoreService validates caller/arguments/capability
  -> typed local-socket command
  -> bombd verifies SELinux peer + UID/PID/process and writes fixed request prop
  -> init property trigger
  -> fixed service/property operations
  -> app reads declared + observed state
```

`OFF` requires no root mode on the integrated target ROM. A sideloaded APK cannot
set the request property and must report the feature unsupported.
