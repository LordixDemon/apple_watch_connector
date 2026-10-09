#!/system/bin/sh

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/sepolicy.rule" 0 0 0644
set_perm "$MODPATH/system/etc/sysconfig/apple-watch-bridge-hiddenapi-whitelist.xml" 0 0 0644
