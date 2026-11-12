# Hchat 脚本子系统：8.0.80 两个 installer 的现状

记录两个长期报错的 installer（`DexInstallScheduler` 里 "多次安装失败，停止本轮重试" 的来源）在
微信 8.0.80 上的根因、已做的改动和剩余工作。设备：Android 16 / arm64-v8a / 微信 8.0.80（versionCode 3200）。

---

## 1. `shared:finder_feed_detail`（视频号详情解析）—— 已修复

### 根因

回调方法签名在 8.0.80 变了（`h/Hchat/hooks/items/shortvideo/FinderFeedDetailResolver.kt`）：

```
旧（<= 8.0.79）: void A(int, int, int, String, <resp>, byte[])                  // 6 参
新（8.0.80）  : void A(int, int, String, <resp>, com.tencent.mm.modelbase.n1)   // 5 参
```

`isCallbackMethod` 写死 6 参 + 末位 `byte[]`，于是 `locateCallbackMethod` 恒为 null →
`locate()` 返回 null → `FinderMediaDownloadSupport.install()` 恒 false → 重试耗尽后打 W。

请求构造器本身没问题（`/cgi-bin/micromsg-bin/findergetcommentdetail` 能定位到 `th2.g4`），
只是后面找回调失败。

### 修法

- `isCallbackMethod` 改成按结构识别：「两个前导 int + 一个 String + 紧随其后的响应对象」，兼容 5/6 参；
- hook 回调里以 String 入参为锚点反推 `errType` / `errCode` / 响应对象下标，不再写死 1/2/4/5；
- 新增 `responseBytes()`：老形态直接拿 `byte[]`，新形态从包装对象（`modelbase.n1`）里反射掏 `byte[]`。

### 验证（真机）

```
[WCX:HchatBootstrap]: Hchat 监听状态 ...                       ← 正常
视频号详情回调未定位 = 0 次
多次安装失败（视频号详情解析）= 0 次
```

---

## 2. `quick_mark_read:native_mark_read`（快捷已读原生已读入口）—— 根因已明确，改为按需捕获

### 根因

入口方法能定位：`com.tencent.mm.storage.m4#b0(String) -> boolean`，但**拿不到它的实例**。

`h/Hchat/hooks/api/runtime/WeChatDatabaseApi.storageObjectForMethod()` 的做法是从
`CoreStorage`（`zp0.b0`）出发做字段 BFS，找 `owner.isInstance()` 的对象。实测：

```
storage 定位失败: owner=com.tencent.mm.storage.m4 core=zp0.b0 遍历对象=10009
（字段图里不存在该类实例，需换访问路径）
```

10009 个对象里没有 `m4` 实例；`zp0.b0` 上也没有「返回类型正好是 `m4`」的无参 getter。
8.0.80 里会话存储已经不是 CoreStorage 的字段，而是挂在别处（或懒加载后由别处持有）。

### 已做的改动

- `storageObjectForMethod`：先试 getter（`findStorageViaGetter`，放宽到「返回值是目标类的实例即可」，
  范围是 CoreStorage 及其直接字段对象），失败再退回原 BFS；
- 失败时打一条一次性 W（`diagnoseStorageLookup`，含 owner / core / 遍历对象数）；
  详细 getter 列表只在 `debug.wcx.enable_all=1` 时输出，避免污染用户日志；
- `QuickMarkReadRuntime`：定位到入口后，挂 `m4` 的**全部实例方法**（实测 49 个）来捕获 `thisObject`，
  谁先被微信调用就把实例留下；捕获到即打 `已捕获原生已读 storage 实例`；
- `prepareNativeMarkRead`：storage 没捕获到不再算「安装失败」（入口已就绪、调用时还会再取一次），
  于是 "多次安装失败" 的 W 消失了。

### 现状 / 验证

```
[Hchat:QuickRead]: 已挂 com.tencent.mm.storage.m4 的 49 个实例方法用于捕获 storage
[Hchat:QuickRead]: 原生已读入口已就绪，storage 实例待捕获: public boolean com.tencent.mm.storage.m4.b0(java.lang.String)
多次安装失败 = 0
```

实例捕获是否成功取决于微信是否调用这些方法（读会话列表时应该会），需要在**用户实际打开会话列表 /
点「全部已读」**之后确认 `已捕获原生已读 storage 实例` 是否出现，以及一次全部已读是否真的生效。

### 真机结果（2026-11-12 22:5x）

用户打开「清空未读」设置后点击「全部已读」——**功能成功，会话未读正常清零**；
该进程内 E/WCX 与 W/WCX 均为 0 条，"多次安装失败" 0 条。

未观察到 "已捕获原生已读 storage 实例"，说明这一次的已读走的是主模块（WCX 自己的
WeDatabaseApi 路径）而不是 Hchat 的原生入口；Hchat 那条按需捕获路径仍未被动用到，
但不影响功能，也不再报错。

### 下一步（尚未完成）

1. 真机验证：亮屏进微信首页 → 打开「+」菜单 → 快捷已读「全部已读」，看
   `已捕获原生已读 storage 实例` + 会话未读是否清零；
2. 若仍未捕获：换根对象，从 `MMKernel`（`zp0.j1`）或插件服务注册表（`com.tencent.mm.kernel.*` 的
   service getter）出发找 `m4` 实例；
3. 兜底方案：SQL 清 `rconversation.unReadCount/unReadMuteCount/atCount/attrflag` +
   `WeChatConversationApi.notifyConversationChanged`，作为原生入口不可用时的降级路径
   （注意：微信内存里的未读计数可能与 DB 不一致，需要实测会不会「又变回未读」）。
