#!/system/bin/sh
#
# Runs at late_start. Converges the device onto whatever tier is recorded, then
# exits.
#
# There is deliberately **no polling loop**. A background `while true` watching a
# property would burn wakeups forever to catch a change the user makes once a
# month, and `CLAUDE.md` rules out unmanaged infinite loops. Changes made through
# `bombctl` apply immediately; this script exists only for boot.

MODDIR=${0%/*}
. "$MODDIR/common.sh"

# late_start does not by itself mean the log services have settled. Wait for
# boot_completed, bounded — an unbounded wait would hang a boot that never
# completes, which is exactly when a user most needs the device to come up.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 120 ]; do
    sleep 1
    i=$((i + 1))
done

log_line "boot: sys.boot_completed=$(getprop sys.boot_completed) after ${i}s"

# The tier the user last chose. Reconcile to it rather than to whatever the
# device happens to be in — a crash midway through a transition leaves the two
# disagreeing, and the recorded intent is the one that survives.
level="$(config_get level)"
[ -n "$level" ] || level="$(declared_level)"

if [ "$level" != "default" ] || [ "$(effective_level)" != "default" ]; then
    log_line "boot: reconciling to '$level' (device is at '$(effective_level)')"
    set_log_level "$level" >/dev/null 2>&1
fi

# Swap knobs are live and are re-applied every boot, because the ROM writes its
# own values at boot (init.rc:39-43 sets swappiness 100 globally and 60 for the
# freeze-app cgroup) and would otherwise overwrite the user's choice.
swappiness="$(config_get swappiness)"
[ -n "$swappiness" ] && set_swappiness "$swappiness" >/dev/null 2>&1

page_cluster="$(config_get page_cluster)"
[ -n "$page_cluster" ] && set_page_cluster "$page_cluster" >/dev/null 2>&1

log_line "boot: done — tier now '$(effective_level)'"
