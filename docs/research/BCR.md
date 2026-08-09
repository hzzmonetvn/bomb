# Research — BCR (Basic Call Recorder)

```text
Reference:
https://github.com/chenxiaolong/BCR.git @ a4a8db86d406490f46200ab35764e55b316d16ac
(commit date 2026-08-07)

License: GPL-3.0-only (LICENSE; SPDX headers in sources) — research only,
no code reuse. Recorded in REFERENCES.md.

Relevant source:
app/src/main/AndroidManifest.xml
app/src/main/java/com/chiller3/bcr/RecorderInCallService.kt
app/src/main/java/com/chiller3/bcr/RecorderThread.kt
app/src/main/java/com/chiller3/bcr/format/AudioSource.kt
app/src/main/java/com/chiller3/bcr/standalone/RemoveHardRestrictions.kt
```

---

## 1. The two privileged permissions that make this possible

`AndroidManifest.xml:9-21`, with the project's own comments:

```xml
<!-- This system app permission is required to capture the call audio stream -->
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT"
    tools:ignore="ProtectedPermissions" />
<!--
     This system app permission is required to allow the telephony service to bind to this app's
     InCallService without this app being a wearable companion app or the default dialer. This
     method of monitoring incoming/outgoing calls is more reliable than listening for
     PHONE_STATE broadcasts.
-->
<uses-permission android:name="android.permission.CONTROL_INCALL_EXPERIENCE"
    tools:ignore="ProtectedPermissions" />
```

plus the ordinary runtime set: `RECORD_AUDIO`, `READ_CONTACTS`,
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`,
`READ_CALL_LOG`, `READ_PHONE_STATE`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`.

**Bomb implications:**

1. Both privileged permissions are `signature|privileged` — a ROM-integrated
   priv-app can hold them, which is exactly Bomb's ROM mode. They go into
   `privapp-permissions-com.hzzmonet.zkbomb.xml` **only if** call recording is
   actually shipped, each with this citation as justification. Master plan §40's
   ban on requesting privileged permissions "just in case" applies.
2. `CONTROL_INCALL_EXPERIENCE` is the reason `InCallService` works without being
   the default dialer. Without it, Bomb would be back to `PHONE_STATE` broadcasts,
   which the BCR author explicitly calls less reliable.
3. **No network permission at all.** BCR ships without `INTERNET`. Bomb's recorder
   subsystem should hold the same line — recorded audio never leaves the device,
   and the absence of the permission is the enforcement.

---

## 2. Call lifecycle — the state machine to copy

`RecorderInCallService.kt`:

```text
onCallAdded(call)                       :174
    registers a Call.Callback (unregistered in requestStopRecording)   :178
        |
onStateChanged(call, state)             :87
        |
   state == STATE_ACTIVE
   || (prefs.recordDialingState && state == STATE_DIALING)  -> startRecording(call)   :223-224
   state == STATE_DISCONNECTING || STATE_DISCONNECTED       -> requestStopRecording   :225-227
   state == STATE_HOLDING -> recorder.isHolding = true                                :230
        |
onCallRemoved(call)                     :195 -> requestStopRecording(call)            :201
```

The comment at :226 is the detail worth carrying over:

```kotlin
} else if (callState == Call.STATE_DISCONNECTING || callState == Call.STATE_DISCONNECTED) {
    // This is necessary because onCallRemoved() might not be called due to firmware bugs
    requestStopRecording(call)
}
```

**Bomb implications:**

1. Stop must be triggered from **both** the state transition and `onCallRemoved`,
   and `requestStopRecording` must be idempotent. A recorder that only stops on
   `onCallRemoved` leaks a recording thread and an open file on buggy firmware —
   and this ROM is exactly the kind of place such bugs live.
2. Hold is a state, not a stop. `isHolding` continues the recording while marking
   the segment.
3. Conference calls are real: `RecorderThread.evaluateRules` (:194-199) walks
   `parentCall.children` when `PROPERTY_CONFERENCE` is set. Per-number auto-record
   rules must consider every participant.
4. Recording start at `STATE_DIALING` is a **user preference**, not a default —
   the pre-answer segment is legally and ethically different from the conversation.

---

## 3. Capture: `VOICE_CALL` and the honest capability boundary

`format/AudioSource.kt:12-26`:

```kotlin
VOICE_CALL             -> arrayOf(MediaRecorder.AudioSource.VOICE_CALL)
VOICE_UPLINK_DOWNLINK  -> arrayOf(MediaRecorder.AudioSource.VOICE_UPLINK,
                                  MediaRecorder.AudioSource.VOICE_DOWNLINK)
VOICE_UPLINK           -> arrayOf(MediaRecorder.AudioSource.VOICE_UPLINK)
VOICE_DOWNLINK         -> arrayOf(MediaRecorder.AudioSource.VOICE_DOWNLINK)
```

`RecorderThread.recordUntilCancelled` (:582-621) constructs one `AudioRecord` per
source, interleaving channels for the stereo case, at
`THREAD_PRIORITY_URGENT_AUDIO`, with a buffer of `minBufSize * 6` — the comment
(:600-602) explains that `MediaCodec` has processing-time spikes and a larger
buffer reduces overrun.

The README states the boundary plainly, and it is the most important sentence in
this whole note:

> **Non-features** … Workarounds for devices that don't support the `VOICE_CALL`
> audio source (eg. using microphone + speakerphone)

and:

> Supports stereo recording (separate uplink and downlink channels) — NOTE: This is
> only known to work on Pixel devices running newer versions of Android. Other
> devices may have unexpected behavior, such as lack of separation between uplink
> and downlink or even no audio at all. Try recording test calls before relying on
> this feature.

**Bomb implications:**

1. `PhoneRecording` capability = "the device's audio HAL grants a working
   `VOICE_CALL` (or uplink/downlink) capture to a `CAPTURE_AUDIO_OUTPUT` holder".
   This **cannot be determined from a permission check** — the permission can be
   held while the HAL returns silence. The probe must attempt a short capture and
   verify non-silent frames, or the capability must be reported as *unverified*.
   Anything else fabricates support, which master plan §24 and CLAUDE.md forbid.
2. Stereo uplink/downlink separation is a **separate** capability from mono
   `VOICE_CALL`, device-specific, and must be probed independently.
3. Mic + speakerphone as a substitute is explicitly refused here. Bomb refuses it
   too: it is a different (and much worse) thing wearing the same label.
4. **`VoipRecording` is not addressed by BCR at all.** Master plan §24 says
   third-party VoIP recording is allowed "only when the integrated platform/vendor
   audio path explicitly supports it". Nothing in BCR provides that, so Bomb's
   `VoipRecording` capability starts as `Unsupported` and stays there until a
   vendor path is demonstrated on the actual ROM. There is no reference
   implementation to lean on.

---

## 4. What BCR does that Bomb must *not* do

`standalone/RemoveHardRestrictions.kt` is a root-executed tool that reaches
`android.os.ServiceManager` by reflection (`ServiceManagerProxy`, :34-38) to strip
hard restrictions from permissions such as `READ_CALL_LOG` — run from the Magisk
module at boot. There is a sibling `ClearPackageManagerCaches.kt`.

**Bomb stance:** Bomb does not manipulate the permission-restriction state of the
platform to grant itself access. In ROM mode Bomb is *in* the image — the correct
mechanism is a reviewed `privapp-permissions` entry, not a boot-time patch of
`PermissionManagerService` state. This is the difference between a system component
and a module fighting the system, and it is the boundary CLAUDE.md draws.

---

## 5. Design notes for Bomb's recorder

Confirmed by this research, mapped onto master plan §24:

| Master plan requirement | Grounding |
| --- | --- |
| capability-first | §3 above — probe capture, not just permission |
| modes `OFF` / `ASK` / `AUTOMATIC` | BCR has auto-record rules per number + a QS tile; `ASK` is an addition Bomb must build |
| visible recording indication | BCR posts a foreground notification only while recording, and holds `FOREGROUND_SERVICE_MICROPHONE` — Android's mic indicator is therefore always shown. Bomb keeps both |
| configurable format | BCR: OGG/Opus, M4A/AAC, FLAC, WAV/PCM, AMR-WB/NB with per-format sample-rate info. Bomb needs at most two (Opus default, FLAC lossless) — more formats is scope, not value |
| configurable retention | BCR does not implement retention; Bomb's `RetentionWorker` is new work |
| safe failure when capture unavailable | `recordUntilCancelled` throws on `getMinBufferSize < 0` (:585-588) and releases every `AudioRecord` on any construction failure (:614-616) |
| no unsupported compatibility claims | the README's "Non-features" section is the model |
| never log audio content | BCR logs source, buffer size and format — never samples. Bomb does the same, and additionally never logs phone numbers |

Additional practical points:

- **Direct boot.** BCR is direct-boot aware (`DirectBootMigrationService`) so calls
  before first unlock are recorded. That implies writing to device-encrypted
  storage and migrating later. Bomb should decide this explicitly; the simpler
  choice is *not* to record before first unlock, and to say so.
- **Storage Access Framework.** BCR writes through SAF so the user picks the
  output tree. For Bomb, recordings are sensitive: default to app-private storage
  under `bomb_data_file` with an explicit export action, rather than a user-chosen
  world-visible tree.
- **Threading.** A dedicated recorder thread at urgent-audio priority, with the
  encoder fed from it. Never on a coroutine dispatcher shared with anything else.

---

## 6. Legal/consent note

Call recording legality varies by jurisdiction and is a user-facing concern, not a
technical one. Bomb ships the feature disabled by default, requires an explicit
opt-in per mode, and keeps the in-call indication non-dismissible. No
"stealth"/"hidden" recording option is offered — that is a product decision, and
it is also the reason master plan §24 requires "visible recording indication".

---

## 7. Testing strategy

`:domain`, pure JVM:

- state machine: `ADDED → DIALING → ACTIVE → HOLDING → ACTIVE → DISCONNECTING →
  DISCONNECTED → REMOVED` produces exactly one recording session;
- `DISCONNECTED` without a following `onCallRemoved` still stops (firmware-bug
  case), and a later `onCallRemoved` is a no-op;
- conference call with three children evaluates rules against all numbers;
- auto-record rule matching, including the default when no rule matches;
- retention policy: age-based and count-based deletion, with an in-progress
  recording never deleted.

Device (Phase 16 gate), reported honestly including failures:

- capture probe on the real ROM: does `VOICE_CALL` yield non-silent audio?
- uplink/downlink separation: probe independently; expect it to fail on non-Pixel;
- capability reports `Unsupported` cleanly when the probe fails — no partial file,
  no notification claiming a recording exists.
