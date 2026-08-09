#!/bin/sh
#
# Off-device test for the log-tier state machine.
#
# `sh -n` proves a script parses. It says nothing about whether the tier
# transitions are right, and those are the part that can leave a phone with no
# logging and no obvious way back. This harness stubs getprop/setprop over a
# plain file, so every transition — including the ones that must refuse — runs in
# under a second on a laptop.
#
# What it does NOT cover: whether `ctl.stop logd` actually stops logd on a real
# device. That is a device question and the module answers it by re-reading
# init.svc.* rather than assuming.

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

export BOMB_DIR="$WORK/bomb"
PROPS="$WORK/props"
: > "$PROPS"

PASS=0
FAIL=0

# ---- stubs ---------------------------------------------------------------

getprop() {
    grep "^$1=" "$PROPS" 2>/dev/null | head -n 1 | cut -d= -f2-
}

setprop() {
    _k="$1"; _v="${2:-}"
    # ctl.start / ctl.stop are how init is asked to change a service; the stub
    # models the effect so the state machine sees what a device would show.
    case "$_k" in
        ctl.stop) setprop "init.svc.$_v" stopped; return ;;
        ctl.start) setprop "init.svc.$_v" running; return ;;
    esac
    if grep -q "^$_k=" "$PROPS" 2>/dev/null; then
        sed -i "s|^$_k=.*|$_k=$_v|" "$PROPS"
    else
        echo "$_k=$_v" >> "$PROPS"
    fi
}

sleep() { :; }          # the stop-wait loop must not actually wait
date() { echo "test"; }

. "$HERE/../common.sh"

# ---- helpers -------------------------------------------------------------

reset_device() {
    : > "$PROPS"
    rm -rf "$BOMB_DIR"
    # A device in its shipped state: everything running, nothing suppressed.
    setprop init.svc.logd running
    setprop init.svc.logcatd running
    setprop init.svc.traced running
    setprop init.svc.traced_probes running
    setprop init.svc.logd-auditctl running
    setprop persist.logd.size 1M
    setprop persist.traced.enable 1
}

check() {
    _label="$1"; _expected="$2"; _actual="$3"
    if [ "$_expected" = "$_actual" ]; then
        PASS=$((PASS + 1))
        echo "  ok    $_label"
    else
        FAIL=$((FAIL + 1))
        echo "  FAIL  $_label"
        echo "          expected: $_expected"
        echo "          actual  : $_actual"
    fi
}

# ---- tests ---------------------------------------------------------------

echo "log tier state machine"

reset_device
check "fresh device reads as default" default "$(effective_level)"

reset_device
set_log_level reduced >/dev/null
check "reduced is reached" reduced "$(effective_level)"
check "reduced keeps logd alive" running "$(getprop init.svc.logd)"
check "reduced stops logcatd" stopped "$(getprop init.svc.logcatd)"
check "reduced shrinks the buffer" 64K "$(getprop persist.logd.size)"
check "reduced re-inits logd so the size takes effect" running "$(getprop init.svc.logd-reinit)"
check "reduced sets the marker" reduced "$(getprop persist.sys.bomb.log.level)"

reset_device
set_log_level reduced >/dev/null
check "snapshot captured the original buffer size" 1M "$(snapshot_value persist.logd.size)"
check "snapshot captured the original traced state" 1 "$(snapshot_value persist.traced.enable)"

set_log_level default >/dev/null
check "restore returns to default" default "$(effective_level)"
check "restore puts back the captured size, not a guess" 1M "$(getprop persist.logd.size)"
check "restore puts back the captured traced flag" 1 "$(getprop persist.traced.enable)"
check "restore restarts logcatd" running "$(getprop init.svc.logcatd)"

reset_device
set_log_level off >/dev/null
check "off stops logd" stopped "$(getprop init.svc.logd)"
check "off reads as off" off "$(effective_level)"
check "off stops logd-auditctl too" stopped "$(getprop init.svc.logd-auditctl)"
check "off keeps the reduced buffer size" 64K "$(getprop persist.logd.size)"

# Leaving off must refuse and record intent, not silently half-apply.
set_log_level default >/dev/null
check "leaving off does not restart logd live" stopped "$(getprop init.svc.logd)"
check "leaving off records the intent for next boot" default "$(getprop persist.sys.bomb.log.level)"

reset_device
set_log_level reduced >/dev/null
set_log_level reduced >/dev/null
check "re-applying the same tier is stable" reduced "$(effective_level)"

# A crash between the property writes and the service stops is the state
# reconcile exists to finish. It must read as default, not as reduced.
reset_device
setprop persist.logd.kernel false
setprop persist.logd.statistics false
setprop persist.traced.enable 0
setprop persist.logd.size 64K
check "half-applied reduced reads as default" default "$(effective_level)"

setprop persist.sys.bomb.log.level reduced
check "declared and effective disagree after a crash" reduced "$(declared_level)"
set_log_level "$(declared_level)" >/dev/null
check "reconcile finishes the interrupted transition" reduced "$(effective_level)"

reset_device
check "an unknown marker value falls back to default" default "$(declared_level)"
setprop persist.sys.bomb.log.level cryogenic
check "a marker this build does not know reads as default" default "$(declared_level)"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
