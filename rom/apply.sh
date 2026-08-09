#!/bin/sh
#
# Applies Bomb's ROM half to an extracted image tree.
#
#   apply.sh <unpacked-dir>
#
# Where <unpacked-dir> holds the extracted partitions (system_ext_a, system_a,
# odm_a, …) plus a config/ directory of fs_config and file_contexts files, as
# produced by extract.erofs.
#
# Idempotent: every step checks for its own marker first, so re-running after a
# partial failure does not append twice. That matters because the target files
# are appended to rather than replaced — a doubled seapp_contexts line is a boot
# failure that looks like a policy bug.
#
# What this does NOT do: recompile precompiled_sepolicy. On the target ROM all
# three sepolicy hash pairs already disagree, so init discards the precompiled
# policy and compiles the CIL on device. See docs/ROM_INTEGRATION.md §3.1.

set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
work=${1:-}
marker="# BOMB"

if [ -z "$work" ] || [ ! -d "$work" ]; then
    echo "usage: $0 <unpacked-dir>" >&2
    exit 2
fi

# Partition directories carry the slot suffix when extracted from a Virtual A/B
# super (system_ext_a), and do not when produced from an OTA payload
# (system_ext). Resolve rather than assume.
find_part() {
    for candidate in "$work/$1_a" "$work/$1"; do
        [ -d "$candidate" ] && { echo "$candidate"; return 0; }
    done
    return 1
}

system_ext=$(find_part system_ext) || { echo "no system_ext partition in $work" >&2; exit 1; }
echo "system_ext: $system_ext"

fail() { echo "  ! $1" >&2; exit 1; }

# ---------------------------------------------------------------- 1. binaries

apk=$here/app-preview/build/outputs/apk/release/app-preview-release.apk
[ -f "$apk" ] || fail "release APK not built: $apk"
[ -f "$here/rom/system_ext/bin/bombd" ] || fail "bombd not built — run the NDK build first"

mkdir -p "$system_ext/priv-app/Bomb" "$system_ext/bin" \
         "$system_ext/etc/init" "$system_ext/etc/permissions" "$system_ext/etc/sysconfig"

cp "$apk" "$system_ext/priv-app/Bomb/Bomb.apk"
cp "$here/rom/system_ext/bin/bombd" "$system_ext/bin/bombd"
cp "$here/rom/system_ext/etc/init/bomb.rc" "$system_ext/etc/init/bomb.rc"
cp "$here/permissions/privapp-permissions-com.hzzmonet.zkbomb.xml" \
   "$system_ext/etc/permissions/"
cp "$here/rom/system_ext/etc/sysconfig/"*.xml "$system_ext/etc/sysconfig/"
echo "  placed APK, bombd, bomb.rc, permissions, sysconfig"

# ---------------------------------------------------------------- 2. build.prop

prop=$system_ext/etc/build.prop
[ -f "$prop" ] || prop=$system_ext/build.prop
[ -f "$prop" ] || fail "no system_ext build.prop found"

if grep -q "^ro.bomb.integrated=" "$prop"; then
    echo "  build.prop already carries Bomb properties"
else
    {
        printf '\n%s\n' "$marker"
        grep -v '^#' "$here/rom/system_ext/etc/bomb/build.prop.fragment" | grep -v '^$'
        grep -v '^#' "$here/rom/framework/framework-capabilities.prop.fragment" | grep -v '^$'
    } >> "$prop"
    echo "  appended properties to $(basename "$prop")"
fi

# Upgrade images patched before the typed audit-rate property was introduced.
if ! grep -q '^persist.sys.bomb.log.audit_rate=' "$prop"; then
    echo 'persist.sys.bomb.log.audit_rate=5' >> "$prop"
    echo "  added Bomb audit-rate default"
fi

# ---------------------------------------------------------------- 3. sepolicy

sel=$system_ext/etc/selinux
[ -d "$sel" ] || fail "no $sel — this image does not carry system_ext policy"

# The comment character differs by file type and getting it wrong is not
# cosmetic: CIL comments start with ';' and a '#' there is parsed as a symbol,
# which fails the whole policy compile with "Symbol not inside parenthesis".
# seapp_contexts / property_contexts / file_contexts use '#'.
append_once() {
    target=$1
    source=$2
    label=$3
    comment=$4
    [ -f "$target" ] || fail "missing $target"
    if grep -q "bomb_app" "$target" 2>/dev/null; then
        echo "  $label already patched"
        return 0
    fi
    printf '\n%s %s\n' "$comment" "BOMB" >> "$target"
    cat "$source" >> "$target"
    echo "  patched $label"
}

append_once "$sel/system_ext_sepolicy.cil"    "$here/sepolicy/cil/bomb.cil"            "system_ext_sepolicy.cil" ";"
append_once "$sel/system_ext_seapp_contexts"  "$here/sepolicy/private/seapp_contexts"  "seapp_contexts"          "#"

# Upgrade an image previously patched by an older Bomb overlay. append_once
# correctly avoids duplicate type declarations, but that also means newly added
# permissions need their own marker-safe delta.
if ! grep -q '(type bomb_memory_proc)' "$sel/system_ext_sepolicy.cil"; then
    cat >> "$sel/system_ext_sepolicy.cil" <<'BOMB_MEMORY_READ_POLICY'

; BOMB MEMORY READ POLICY
(type bomb_memory_proc)
(roletype object_r bomb_memory_proc)
(typeattributeset fs_type (bomb_memory_proc))
(typeattributeset proc_type (bomb_memory_proc))
(genfscon proc "/sys/vm/swappiness" (u object_r bomb_memory_proc ((s0) (s0))))
(allow bomb_app bomb_memory_proc (file (read getattr open)))
(allow bomb_app proc_page_cluster (file (read getattr open)))
(allow bomb_app proc (dir (search)))
(allow bomb_app sysfs (dir (search)))
(allow bomb_app sysfs_zram (dir (read getattr open search)))
(allow bomb_app sysfs_zram (file (read getattr open)))
(allow bomb_app cgroup (dir (read getattr open search)))
(allow bomb_app cgroup (file (read getattr open)))
(allow init bomb_memory_proc (file (write lock append map open)))
(allow vendor_init bomb_memory_proc (file (write lock append map open)))
BOMB_MEMORY_READ_POLICY
    echo "  upgraded Bomb memory telemetry policy"
fi

# Upgrade images patched before aggregate CPU telemetry was implemented.
if ! grep -q '(allow bomb_app proc_stat (file (read getattr open)))' \
    "$sel/system_ext_sepolicy.cil"; then
    cat >> "$sel/system_ext_sepolicy.cil" <<'BOMB_CPU_READ_POLICY'

; BOMB CPU READ POLICY
(allow bomb_app proc_stat (file (read getattr open)))
BOMB_CPU_READ_POLICY
    echo "  upgraded Bomb aggregate CPU telemetry policy"
fi

# property_contexts and file_contexts carry comments the ROM parser will not
# accept, so strip them rather than copying the authoring file verbatim.
if grep -q "bomb" "$sel/system_ext_property_contexts" 2>/dev/null; then
    echo "  property_contexts already patched"
else
    grep -v '^#' "$here/sepolicy/private/property_contexts" | grep -v '^$' \
        >> "$sel/system_ext_property_contexts"
    echo "  patched property_contexts"
fi

if grep -q "bombd" "$sel/system_ext_file_contexts" 2>/dev/null; then
    echo "  file_contexts already patched"
else
    grep -v '^#' "$here/sepolicy/private/file_contexts" | grep -v '^$' \
        >> "$sel/system_ext_file_contexts"
    echo "  patched file_contexts"
fi

# ---------------------------------------------------------------- 4. fs_config / file_contexts for repack
#
# extract.erofs writes one fs_config and one file_contexts per partition; the
# repack reads them back. New files that are not listed there get default
# ownership and the wrong label, so bombd would not be able to serve as a domain
# entrypoint and the APK would not be readable.

config=$work/config
if [ -d "$config" ]; then
    part=$(basename "$system_ext")           # system_ext_a on a Virtual A/B super
    fsc=$config/${part}_fs_config
    fct=$config/${part}_file_contexts

    # These files key on the *staging directory name*, slot suffix included —
    # `system_ext_a/bin/...` in fs_config and `/system_ext_a/bin/...` in
    # file_contexts — not on the mount point. Using the mount point silently
    # produces entries that match nothing, and mkfs then fails on the first file
    # it cannot find a config for.
    if [ -f "$fsc" ] && ! grep -q "bombd" "$fsc"; then
        {
            echo "$part/bin/bombd 0 2000 0755"
            echo "$part/etc/init/bomb.rc 0 0 0644"
            echo "$part/priv-app/Bomb 0 0 0755"
            echo "$part/priv-app/Bomb/Bomb.apk 0 0 0644"
            echo "$part/etc/permissions/privapp-permissions-com.hzzmonet.zkbomb.xml 0 0 0644"
        } >> "$fsc"
        echo "  added fs_config entries (bombd 0755 root:shell)"
    fi

    # Only bombd needs an explicit label. Everything else takes the partition
    # default (system_file), which is what it should have. bombd must be
    # bombd_exec or it cannot serve as the domain entrypoint and init starts it
    # in the wrong domain — or not at all.
    if [ -f "$fct" ] && ! grep -q "bombd" "$fct"; then
        echo "/$part/bin/bombd u:object_r:bombd_exec:s0" >> "$fct"
        echo "  added file_contexts entry (bombd_exec)"
    fi
else
    echo "  ! no config/ directory — repack will lose ownership and labels"
fi

echo
echo "Bomb applied to $system_ext"
echo "Next: repack the partition, then rebuild super."
