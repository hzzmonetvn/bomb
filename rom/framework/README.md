# Framework integration contract

Bomb's two framework-only capabilities are fail-closed:

- `ro.bomb.framework.visibility=1`
- `ro.bomb.framework.settings=1`

The properties must be absent or `0` until the corresponding framework binary
has been patched and verified. The app reports the feature unsupported otherwise.
Never set a marker merely because a config file was copied.

## App visibility

The target framework patch must call one shared Bomb policy at every
caller-sensitive PackageManager surface:

- installed package/application enumeration;
- direct package/application lookup;
- intent/activity/service/receiver resolution;
- provider lookup and provider enumeration;
- UID-to-package and package-to-UID mapping;
- shared-UID and multi-user paths.

Inputs are `callingUid`, `callingPackage`, `targetPackage`, `userId` and operation
kind. Exempt system/root/installer/permission-controller callers explicitly. A
missing/corrupt policy file means **show normally** (fail open for package
availability), while a missing hook marker means Bomb reports the capability
unsupported. Cache keys must include caller UID and user ID.

## Settings virtualization

Patch SettingsProvider's read path after caller identity is resolved and before a
value is returned. The shared policy key is `(callingUid, callingPackage, userId,
namespace, settingName)`. It may substitute only allowlisted keys; writes always
continue to the real provider and global storage is never mutated to simulate a
per-app value.

The patch must cover single-key reads, bulk/query reads and cache paths. A missing
or invalid policy returns the real value. Set `ro.bomb.framework.settings=1` only
after all paths pass the on-device matrix.

## Why no binary patch is checked in yet

This repository does not contain the target ROM's `framework.jar`,
`services.jar`, SettingsProvider APK, deodexed smali, or a matching AOSP/HyperOS
source checkout. A source/smali diff produced without those exact artifacts would
be untestable and could hook the wrong overload. The ROM builder must supply those
artifacts; until then the capability markers stay off and the app cannot claim
either feature.

