#!/system/bin/sh
# Zygisk - WCX 卸载脚本：清理运行时产物（模块目录本身由 Root 管理器删除）
#
# 说明：本模块不安装 APK，payload 副本、解包的原生库、抽出的城市库都落在微信自己的
# data 目录里。不清掉会留下几十 MB 垃圾（也不影响微信运行）。

WECHAT_DATA=/data/user/0/com.tencent.mm
EXTERNAL_DATA=/data/media/0/Android/data/com.tencent.mm/WCX

# payload APK 副本 + 解包出来的原生库
rm -f "$WECHAT_DATA"/files/mmkv/wcx-payload-*.apk 2>/dev/null
rm -rf "$WECHAT_DATA"/.wcx-native 2>/dev/null
rm -rf "$WECHAT_DATA"/files/home_side_panel 2>/dev/null
rm -f "$WECHAT_DATA"/files/mmkv/wcx-payload-*.apk 2>/dev/null

# 自愈计数 / 崩溃日志 / 主题素材等外部数据（保留用户的日志？一并清理更干净）
rm -rf "$EXTERNAL_DATA/selfheal" 2>/dev/null

exit 0
