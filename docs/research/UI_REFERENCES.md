# Research — InstallerX-Revived (privileged-app architecture reference)

```text
Reference:
https://github.com/wxxsfxyzm/InstallerX-Revived.git @ ac0a6874688e6dc1d6a70253d3b3e3ccd3f02595
(commit date 2026-08-06)

License: GPL-3.0 (LICENSE) — research only, no code reuse.
Modules (settings.gradle.kts): :app, :app-process, :hidden-api, :baselineprofile
```

Master plan §31.12 frames this as a "secondary UI/architecture reference for
combining modern Android UI, MIUIX/M3E concepts, privileged/package-management
workflows, and HyperOS-like presentation", with the instruction: *do not copy its
feature scope into Bomb*. The most useful output turned out to be architectural,
not visual — including one clear anti-pattern.

---

## 1. Module split — a working three-layer privileged shape

```text
:app          UI + domain + data; the unprivileged process
:app-process  the privileged process host: AppProcess, NewProcess, ProcessManager,
              RemoteProcess/RemoteProcessImpl, BinderWrapper, ParcelableBinder
:hidden-api   compile-only stubs for framework internals
```

`:app` package structure (`app/src/main/java/com/rosan/installer/`):

```text
core/  data/  di/  domain/  framework/  ui/  util/
ui/{activity, animation, common, icons, library, navigation, page, theme, util}
```

**Bomb comparison:** Bomb's planned split (`:app`, `:core-api`, `:domain`,
`:system-service`) is the same idea with a stricter contract — Bomb's `:domain` is
a pure `java-library` with no Android imports, which InstallerX's `domain` package
is not (it lives inside `:app`). Keep Bomb's version; it is what makes
`FreezePolicyEngine` and the rule engine unit-testable without a device.

The `:hidden-api` compile-only module is a **technique worth adopting**: framework
internals referenced as compile-only stubs instead of reflection. For Bomb's
priv-app, most of what it needs is public-or-privileged API, but where a hidden
signature is unavoidable, a stub module is far better than `HiddenApiBypass`
reflection (which Hail uses — see [`HAIL.md`](./HAIL.md) §2). Stubs fail at
**compile** time when the platform changes; reflection fails at runtime, on the
user's device.

`registerDeathToken(IBinder token)` in `IAppProcessService.aidl` is also worth
carrying over: the privileged process watches the client's binder token and shuts
itself down when the UI dies. Bomb's `:core` process should not outlive its
purpose either.

---

## 2. The anti-pattern, stated plainly

`app/src/main/aidl/com/rosan/installer/IPrivilegedService.aidl`, in full:

```aidl
interface IPrivilegedService {
    void delete(in String[] paths);
    String execArr(in String[] command);
    void execArrWithCallback(in String[] command, ICommandOutputListener listener);
    Bundle parsePackageArchive(String path);
}
```

Three of the four methods are exactly what Bomb's `CLAUDE.md` and master plan §3.1
forbid:

- `execArr(String[])` — **arbitrary shell execution over Binder**;
- `execArrWithCallback(...)` — the same, streaming;
- `delete(String[] paths)` — **arbitrary file deletion by path**.

Any caller that can reach this interface has full privileged shell. For
InstallerX that is a deliberate trade — it is a general-purpose installer whose
job is running `pm install` variants. For Bomb it would dissolve the entire
security design: every typed API, every validator, every SELinux rule becomes
decorative the moment one generic escape hatch exists.

**Bomb's contrasting rule, restated with this as the concrete example:**

| InstallerX | Bomb equivalent |
| --- | --- |
| `execArr(["pm","install",...])` | typed `installPackage(in InstallRequest)` with a validated source and no free-form arguments |
| `delete(String[] paths)` | no path-taking delete exists; deletion is scoped to Bomb-owned artifacts by typed id (e.g. `deleteRecording(long id)`) |
| `parsePackageArchive(String path)` | typed `inspectPackage(in ParcelFileDescriptor)` — pass an fd the caller already holds, so the privileged side never resolves a caller-supplied path |

The third row generalises: **prefer passing an already-opened file descriptor over
passing a path.** A path is a request for the privileged process to open something
on the caller's behalf, which is where confused-deputy bugs live; an fd is a
capability the caller already had.

This is the single most valuable thing this repository taught, and it belongs in
Bomb's AIDL review checklist.

---

## 3. UI observations

- `ui/` is organised as `activity / navigation / page / common / library / icons /
  theme / animation` — pages under `page/`, reusable widgets under `common/` and
  `library/`. Bomb's planned `ui/design` + `ui/<feature>` split is equivalent and
  slightly clearer.
- Extensive localisation (`values-vi`, `values-zh-rCN`, `values-ja`, `values-ru`, …)
  plus API-gated resource buckets (`values-v29`, `values-v31`, `values-v34` and the
  matching `values-night-*`). Bomb should adopt the `values-night-*` and API-gated
  pattern from the start rather than retrofitting; the master plan's dark-mode-first
  requirement makes `values-night` mandatory anyway.
- `keepRules/` alongside `res/` — R8 keep rules kept with the module that needs
  them. Relevant to Bomb later: a privileged process using AIDL and reflection-free
  stubs still needs keep rules for `Parcelable` creators.
- `baselineprofile` module — same note as MIUIX: a Phase 17 concern.

---

## 4. What Bomb takes

| InstallerX-Revived | Bomb decision |
| --- | --- |
| Separate privileged process module (`:app-process`) | Adopt in spirit — Bomb's `:system-service` + `:core` process |
| `:hidden-api` compile-only stubs | **Adopt** — preferred over runtime reflection |
| `registerDeathToken` for client-death shutdown | Adopt |
| `execArr` / `execArrWithCallback` generic shell | **Reject — this is the anti-pattern Bomb exists to avoid** |
| `delete(String[] paths)` | **Reject** — no path-taking privileged file API |
| `parsePackageArchive(String path)` | Reject the shape; pass an fd instead |
| `domain` package inside `:app` | Reject — Bomb's `:domain` is a pure JVM module |
| Localisation + `values-night-*` + API-gated resources | Adopt from the start |
| Its feature scope (installer, package management workflows) | Out of scope, per master plan §31.12 |

---

## 5. Checklist this produced for Bomb's AIDL review

Every method added to `IBombService` must answer yes to all of these:

1. Is the operation named for **what it does**, not for the mechanism it uses?
2. Is every argument a bounded type — enum, id, validated package name, numeric
   range — rather than a free-form string that becomes a command or a path?
3. If a file is involved, does the caller pass an **fd it already holds** instead
   of a path the service must resolve?
4. Can the caller combine several legitimate calls to obtain an effect the API
   does not intend to grant?
5. Does it return a `BombResult` that distinguishes unsupported, denied, invalid
   and failed, instead of a bare boolean or void?
