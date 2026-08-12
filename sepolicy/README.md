# Bomb SELinux policy

SELinux stays **enforcing**. Nothing here relaxes it, and nothing here is
`audit2allow` output pasted in.

## Scope today

Deliberately small, because only a small amount is currently justified.

`BombCoreService` remains an app-bound service, but privileged ROM writes cross
the init-owned `bombd` socket. The package is assigned the dedicated `bomb_app`
domain; that domain has no property-service, sysfs-write or shell permission.

The first policy-backed write path is intentionally narrow: `bomb_app` may only
connect to `bombd`; `bombd` may set `bomb_control_prop`; init performs the fixed
service/sysfs operations. Both sides validate the fixed enum/range protocol.

Two things that would normally need policy, and do not:

- **The framework patches (M14/M15)** need no new rules at all. They run inside
  `system_server`, in `system_server`'s own domain. This is a real advantage of
  patching over hooking and is worth stating when those patches are reviewed.
- **`BombCoreService`** needs no service-manager rules: it is an app-bound
  service reached by `bindService`, not a registered system service.

## What is here

| File | Purpose |
| --- | --- |
| `private/bomb_app.te` | app domain and its single socket edge |
| `private/bombd.te` | daemon transition, peer socket, proc identity read, control props |
| `private/bomb_property.te` | separate command and read-only status types |
| `private/property_contexts` | exact names and enum/int/bool validation |
| `private/seapp_contexts` | puts only the Bomb priv-app into `bomb_app` |
| `private/file_contexts` | ROM artifact labels |

## What is deliberately not here

- Broad device-node access. Bomb has narrow read-only telemetry access to the
  target's battery/USB supply labels; current `bombd` writes only typed request
  properties and init owns every fixed sysfs write.
- **Any rule for the root-mode module.** In root mode the module runs in the root
  manager's own domain and does not use Bomb's types. Two delivery paths for the
  same policy is risk R13; the answer is to author once and generate both, not to
  hand-write a second copy here.
- **`sepolicy.rule`** — the root-mode form of the same rules. Generated from this
  directory when there is more than one rule to generate.

## Adding a rule

For every addition, name all five, in the commit message:

1. source domain
2. target type
3. object class
4. exact permission
5. the Bomb feature that requires it

If any of the five cannot be named, the rule is not ready. A denial that appears
during bring-up is information about what a feature actually does — it is not a
prompt to run `audit2allow`.

## Verifying

```sh
# Bomb landed in its own domain rather than plain priv_app
adb shell ps -Z | grep zkbomb

# Nothing was silently dropped
adb shell dmesg | grep -i 'avc.*zkbomb'
adb logcat -b events | grep -i 'avc.*zkbomb'
```

The second one matters more than it looks: `docs/research/LOG_CONTROL.md` §5
records that the Log Governor's `OFF` tier makes denials invisible. **Do not run
tier `OFF` while bringing up policy** — it removes the instrument this check
depends on.
