#!/system/bin/sh
#
# Bomb root backend — shared logic.
#
# This is the executing half of docs/BOMB_PLAN.md §4.13 and §4.14. The deciding
# half lives in :domain (LogTransitionPlanner, ZramConfigValidator) and is
# covered by JUnit tests; this file must mirror those decisions rather than
# invent its own. Where the two could drift, the domain test is the specification
# and this file is wrong.
#
# Three rules carried over from the research, each of which was expensive to
# establish:
#
#   1. persist.logd.size does nothing until `logd --reinit` runs. Setting it
#      alone is a silent no-op — the buffer keeps its old size and nothing says
#      so.
#   2. Restore means restoring *captured* values, not guessed defaults. There is
#      no safe guess for persist.traced.enable: 1 enables Perfetto on a device
#      that shipped with it off, "" leaves the init trigger unable to start
#      traced on the next boot.
#   3. Every action is verified by re-reading state. `setprop ctl.stop` returns
#      success whether or not the service stopped.

# Overridable so the tier state machine can be exercised off-device against a
# stubbed getprop/setprop. On a device nothing sets it and the default applies.
BOMB_DIR="${BOMB_DIR:-/data/adb/bomb}"
SNAPSHOT="$BOMB_DIR/snapshot.conf"
CONFIG="$BOMB_DIR/config.conf"
LOGFILE="$BOMB_DIR/bomb.log"

MARKER_PROP=persist.sys.bomb.log.level

# Every property this module writes, and therefore every property it must be
# able to put back. The Bomb marker is deliberately absent: it is our own state,
# not device state to preserve.
MANAGED_PROPS="persist.logd.kernel
persist.logd.statistics
persist.logd.size
persist.logd.limit
logd.logpersistd.enable
persist.traced.enable
persist.traced_perf.enable
persist.logd.audit.rate"

REDUCED_BUFFER_SIZE=64K

# ---------------------------------------------------------------- utilities

bomb_init_dirs() {
    mkdir -p "$BOMB_DIR" 2>/dev/null
    chmod 700 "$BOMB_DIR" 2>/dev/null
}

log_line() {
    bomb_init_dirs
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOGFILE"
}

say() {
    echo "$*"
    log_line "$*"
}

prop_get() { getprop "$1"; }

prop_set() { setprop "$1" "$2"; }

svc_state() { getprop "init.svc.$1"; }

svc_running() { [ "$(svc_state "$1")" = "running" ]; }

# Stops a service and waits for init to confirm it. Without the wait we would
# report success the instant the property was written, which says nothing about
# whether the service actually went away.
svc_stop() {
    _name="$1"
    setprop ctl.stop "$_name"
    _i=0
    while [ "$_i" -lt 20 ]; do
        svc_running "$_name" || return 0
        sleep 0.25
        _i=$((_i + 1))
    done
    return 1
}

svc_start() {
    _name="$1"
    setprop ctl.start "$_name"
    return 0
}

# ---------------------------------------------------------------- snapshot

# Captures the managed properties exactly once, before anything is changed.
# Re-capturing after a change would immortalise Bomb's own values as if they
# were the device's, and the original state would be gone for good.
snapshot_capture() {
    bomb_init_dirs
    [ -f "$SNAPSHOT" ] && return 0
    : > "$SNAPSHOT"
    chmod 600 "$SNAPSHOT"
    for _p in $MANAGED_PROPS; do
        echo "$_p=$(prop_get "$_p")" >> "$SNAPSHOT"
    done
    log_line "snapshot captured"
}

snapshot_value() {
    [ -f "$SNAPSHOT" ] || { echo ""; return; }
    # Only the first match, and only the part after the first '=', so a value
    # that itself contains '=' survives.
    grep "^$1=" "$SNAPSHOT" 2>/dev/null | head -n 1 | cut -d= -f2-
}

# ---------------------------------------------------------------- observation

# Mirrors ObservedLogState.effectiveLevel().
#
# A half-applied REDUCED — properties set but logcatd still running — resolves to
# `default` on purpose. That is the conservative direction: it under-claims
# suppression, and it makes reconcile re-apply the tier instead of concluding
# there is nothing to do.
effective_level() {
    if ! svc_running logd; then
        echo off
        return
    fi
    if [ "$(prop_get persist.logd.kernel)" = "false" ] &&
        [ "$(prop_get persist.logd.statistics)" = "false" ] &&
        [ "$(prop_get persist.traced.enable)" = "0" ] &&
        [ -n "$(prop_get persist.logd.size)" ] &&
        ! svc_running logcatd &&
        ! svc_running traced &&
        ! svc_running traced_probes; then
        echo reduced
        return
    fi
    echo default
}

declared_level() {
    _v="$(prop_get $MARKER_PROP)"
    case "$_v" in
        reduced|off) echo "$_v" ;;
        *) echo default ;;
    esac
}

# MIUI writes here regardless of tier. Nothing in this module touches it, so the
# status output has to say so — claiming "logging off" while ylog keeps writing
# would be false, and false in a way the user would only discover by finding
# their logs.
ylog_present() { [ -e /dev/ylog_buffer ]; }

# ---------------------------------------------------------------- log tiers

apply_reduced_props() {
    prop_set persist.logd.kernel false
    prop_set persist.logd.statistics false
    prop_set persist.logd.size "$REDUCED_BUFFER_SIZE"
    prop_set persist.logd.limit Off
    prop_set logd.logpersistd.enable false
    prop_set persist.traced.enable 0
    prop_set persist.traced_perf.enable 0
    _rate="$(config_get audit_rate)"
    if [ -n "$_rate" ]; then
        # logd.rc re-runs logd-auditctl on any change to this property, so the
        # new denial cap takes effect without a restart.
        prop_set persist.logd.audit.rate "$_rate"
    fi
}

apply_default() {
    for _p in $MANAGED_PROPS; do
        prop_set "$_p" "$(snapshot_value "$_p")"
    done
    # Without this the restored size is a silent no-op and the buffer stays at
    # whatever REDUCED shrank it to.
    svc_start logd-reinit
    svc_start traced
    svc_start traced_probes
    svc_start logcatd
    prop_set $MARKER_PROP default
}

apply_reduced() {
    apply_reduced_props
    svc_stop logcatd || log_line "warn: logcatd did not stop"
    svc_stop traced || log_line "warn: traced did not stop"
    svc_stop traced_probes || log_line "warn: traced_probes did not stop"
    svc_start logd-reinit
    prop_set $MARKER_PROP reduced
}

apply_off() {
    # The reduced property set is kept deliberately: if logd is ever restarted —
    # by a vendor path this analysis missed, or by a ROM update — it comes back
    # small and quiet rather than at full volume.
    apply_reduced_props
    svc_stop logcatd || log_line "warn: logcatd did not stop"
    svc_stop traced || log_line "warn: traced did not stop"
    svc_stop traced_probes || log_line "warn: traced_probes did not stop"
    svc_stop logd-auditctl || log_line "warn: logd-auditctl did not stop"
    # logd last, after everything that feeds or reads it.
    if svc_stop logd; then
        prop_set $MARKER_PROP off
        return 0
    fi
    log_line "error: logd is still running after ctl.stop"
    return 1
}

# Applies a tier, honouring the transitions that are not live.
set_log_level() {
    _target="$1"
    case "$_target" in
        default|reduced|off) ;;
        *) say "invalid level: $_target (expected default, reduced or off)"; return 2 ;;
    esac

    _current="$(effective_level)"

    # Restarting a killed logd cleanly at runtime is unreliable. Saying so beats
    # attempting it and reporting a success that cannot be stood behind.
    if [ "$_current" = "off" ] && [ "$_target" != "off" ]; then
        prop_set $MARKER_PROP "$_target"
        say "leaving 'off' needs a reboot. Marker set to '$_target'; it will apply on next boot."
        return 3
    fi

    if [ "$_current" = "$_target" ] && [ "$(declared_level)" = "$_target" ]; then
        say "already at '$_target'"
        return 0
    fi

    snapshot_capture

    case "$_target" in
        default) apply_default ;;
        reduced) apply_reduced ;;
        off) apply_off || { say "failed to reach 'off'"; return 1; } ;;
    esac

    # Verified, not assumed. Every setter above can succeed while the state does
    # not change.
    _now="$(effective_level)"
    if [ "$_now" = "$_target" ]; then
        say "log level: $_target"
        return 0
    fi
    say "requested '$_target' but device reports '$_now' — see $LOGFILE"
    return 1
}

# ---------------------------------------------------------------- swappiness

# The five knobs this ROM actually writes at boot (init.rc:39-43).
#
# sys_critical is deliberately absent. It sits at swappiness 0 and pins ueventd,
# vold, netd, surfaceflinger and servicemanager out of swap; raising it would let
# the processes that keep the device usable be swapped out under pressure.
SWAPPINESS_TARGETS="/proc/sys/vm/swappiness
/dev/memcg/memory.swappiness
/dev/memcg/freeze-app/memory.swappiness
/dev/memcg/apps/memory.swappiness
/dev/memcg/system/memory.swappiness"

set_swappiness() {
    _value="$1"
    case "$_value" in
        ''|*[!0-9]*) say "swappiness must be a number 0-200"; return 2 ;;
    esac
    # 200, not 100: this ROM's own swappiness_on_launcher can push 200, so a
    # 0-100 bound would reject a value the device sets for itself.
    if [ "$_value" -gt 200 ]; then
        say "swappiness must be 0-200"
        return 2
    fi

    _applied=0
    for _t in $SWAPPINESS_TARGETS; do
        [ -w "$_t" ] || continue
        echo "$_value" > "$_t" 2>/dev/null || continue
        [ "$(cat "$_t" 2>/dev/null)" = "$_value" ] && _applied=$((_applied + 1))
    done
    if [ "$_applied" -eq 0 ]; then
        say "no swappiness target was writable"
        return 1
    fi
    say "swappiness $_value applied to $_applied target(s)"
}

set_page_cluster() {
    _value="$1"
    case "$_value" in
        ''|*[!0-9]*) say "page-cluster must be a number 0-6"; return 2 ;;
    esac
    if [ "$_value" -gt 6 ]; then
        say "page-cluster must be 0-6 (it is a power-of-two exponent)"
        return 2
    fi
    [ -w /proc/sys/vm/page-cluster ] || { say "page-cluster is not writable"; return 1; }
    echo "$_value" > /proc/sys/vm/page-cluster
    say "page-cluster $(cat /proc/sys/vm/page-cluster)"
}

# ---------------------------------------------------------------- config

config_get() {
    [ -f "$CONFIG" ] || { echo ""; return; }
    grep "^$1=" "$CONFIG" 2>/dev/null | head -n 1 | cut -d= -f2-
}

config_set() {
    bomb_init_dirs
    touch "$CONFIG"
    if grep -q "^$1=" "$CONFIG" 2>/dev/null; then
        sed -i "s|^$1=.*|$1=$2|" "$CONFIG"
    else
        echo "$1=$2" >> "$CONFIG"
    fi
}
