#!/system/bin/sh
#
# Puts back what was captured, then gets out of the way.
#
# Restoring the *captured* values matters more here than anywhere else: this
# runs when the user has decided they want the module gone, and leaving guessed
# values behind would mean uninstalling changed the device rather than
# un-changing it.

SNAPSHOT=/data/adb/bomb/snapshot.conf

if [ -f "$SNAPSHOT" ]; then
    while IFS= read -r line; do
        key="${line%%=*}"
        value="${line#*=}"
        [ -n "$key" ] || continue
        setprop "$key" "$value"
    done < "$SNAPSHOT"

    # The buffer size means nothing until logd re-reads it.
    setprop ctl.start logd-reinit
    setprop ctl.start traced
    setprop ctl.start traced_probes
    setprop ctl.start logcatd
fi

setprop persist.sys.bomb.log.level default

# Swappiness is not restored here on purpose. The ROM rewrites all five targets
# at every boot (init.rc:39-43), so a reboot restores them more reliably than
# this script could — and the module is being removed, so a reboot is coming.

# The log is left behind deliberately: if the module is being removed because
# something went wrong, its record of what it did is the one thing worth
# keeping.
rm -f /data/adb/bomb/config.conf
rm -f "$SNAPSHOT"

# If logd was stopped, only a reboot brings it back reliably — upstream log
# disablers say the same about their own uninstall.
if [ "$(getprop init.svc.logd)" != "running" ]; then
    setprop ctl.start logd
fi
