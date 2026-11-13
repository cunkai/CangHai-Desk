#!/system/bin/sh
export CLASSPATH=/data/local/tmp/rbprobe.dex
exec app_process /system/bin com.aosen.shizukudex.RootBridgeProbe
