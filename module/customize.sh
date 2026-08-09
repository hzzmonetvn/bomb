#!/system/bin/sh
#
# Install-time checks. Refuses rather than installing something that cannot work
# and failing later in a way that looks like a device fault.

SKIPUNZIP=0

ui_print ""
ui_print "  Bomb root backend"
ui_print "  ----------------------------------------"

# --- device report, so the log shows what it was installed onto ---
ui_print "  device : $(getprop ro.product.device) ($(getprop ro.board.platform))"
ui_print "  android: $(getprop ro.build.version.release)"

# --- hard requirement: init service control ---
if [ ! -e /system/bin/getprop ]; then
    abort "  ! getprop missing — cannot read init state. Aborting."
fi

if [ -z "$(getprop init.svc.logd)" ]; then
    ui_print "  ! init.svc.logd is empty."
    ui_print "    This module drives logd through init service properties."
    abort "    Without them nothing here can work. Aborting."
fi
ui_print "  logd   : $(getprop init.svc.logd)"

# --- the finding that made tier OFF viable on this ROM, re-checked here ---
# A vendor .rc that restarts logd on a repeating trigger would make 'off' a
# restart loop rather than a tier. Checked at install time because it is cheap,
# and because being wrong about it is expensive.
restarter=0
for rc in /vendor/etc/init/hw/*.rc /vendor/etc/init/*.rc /system/etc/init/*.rc; do
    [ -f "$rc" ] || continue
    if grep -qE '^on property:.*' "$rc" 2>/dev/null && grep -qE '^\s*start logd\s*$' "$rc" 2>/dev/null; then
        ui_print "  ! $rc may restart logd on a property trigger."
        restarter=1
    fi
done
if [ "$restarter" = "1" ]; then
    ui_print "  Tier 'off' may not hold on this ROM. 'reduced' is unaffected."
fi

# --- MIUI's own sink, which no tier here touches ---
if [ -e /dev/ylog_buffer ]; then
    ui_print "  ! /dev/ylog_buffer present — MIUI logs there at every tier."
    ui_print "    'off' will NOT stop it. Bomb does not claim otherwise."
fi

# --- zram, read-only for this module ---
if [ -d /sys/block/zram0 ]; then
    ui_print "  zram   : present ($(cat /sys/block/zram0/comp_algorithm 2>/dev/null))"
else
    ui_print "  zram   : absent — swappiness controls will have less effect"
fi

# --- priv-app installation ---
ui_print "  ----------------------------------------"
ui_print "  Bomb app"

if [ -f "$MODPATH/system/priv-app/Bomb/Bomb.apk" ]; then
    ui_print "  apk    : bundled ($(stat -c%s "$MODPATH/system/priv-app/Bomb/Bomb.apk" 2>/dev/null) bytes)"

    # A sideloaded copy in /data/app shadows the system one completely: the
    # package stays at its /data path, and privileged permissions are never
    # granted. Nothing about the symptom points at this cause, so it is worth
    # catching at install time rather than leaving to be puzzled over.
    existing="$(pm path com.hzzmonet.zkbomb 2>/dev/null | head -n 1 | cut -d: -f2-)"
    case "$existing" in
        /data/app/*)
            ui_print "  !! A sideloaded Bomb is installed at:"
            ui_print "     $existing"
            ui_print "     It will shadow this one and privileged permissions"
            ui_print "     will NOT be granted. After rebooting, run:"
            ui_print "       pm uninstall --user 0 com.hzzmonet.zkbomb"
            ui_print "     then reboot again."
            ;;
        "") ui_print "  state  : not currently installed" ;;
        *)  ui_print "  state  : already at $existing" ;;
    esac
else
    ui_print "  apk    : not bundled — module installs the backend only"
fi

# --- whether the allowlist will actually be consulted ---
enforce="$(getprop ro.control_privapp_permissions)"
if [ -z "$enforce" ]; then
    ui_print "  privapp: ro.control_privapp_permissions is EMPTY"
    ui_print "           The allowlist is not enforced on this ROM, so a"
    ui_print "           priv-app gets what its manifest requests. The XML"
    ui_print "           still ships as the record of intent."
else
    ui_print "  privapp: enforcement = $enforce"
fi

mkdir -p /data/adb/bomb
chmod 700 /data/adb/bomb

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/bombctl" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0644

ui_print "  ----------------------------------------"
ui_print "  Installed. Nothing is applied until you ask:"
ui_print ""
ui_print "    bombctl status"
ui_print "    bombctl log reduced"
ui_print ""
ui_print "  Default tier is unchanged, so a reboot now"
ui_print "  changes nothing about this device."
ui_print ""
