# v0.4.1 — 新增 Shizuku 通道(免 root 的 shell 身份)

## 🔓 没有 root,也能用 shell 身份执行命令

在 v0.4.0 的"无障碍输 PIN"之外,新增一条**真正的特权通道**:通过 Shizuku 以
**shell(uid 2000)** 身份执行命令,因此 `settings put secure lockscreen.disabled 1` /
`wm dismiss-keyguard` / `input text <PIN>` 都能照跑 —— 等于把 root 那套解锁流程复制了一份。

解锁优先级:滑动 → Root → **Shizuku** → 无障碍输 PIN → 等你解锁后补跑。

## 怎么用

1. 安装并启动 **Shizuku**(包名 `moe.shizuku.manager`;Android 11+ 可用无线调试免电脑启动);
2. 本应用 → 基本设置 → 「请求 Shizuku 授权」→ 允许;
3. 点「检测提权通道(Root / Shizuku)」确认显示 `已授权`。

## ⚠️ 两个代价(已在应用内与文档里写明)

1. 用的是 Shizuku **未公开的内部 AIDL**(`dev.rikka.shizuku:aidl`,官方有发布编译好的 stub)。
   Shizuku 升级若改动 AIDL 事务号就会失效 —— 因此调用失败**只写日志并回落**到无障碍通道,绝不卡死;
2. Shizuku **重启手机后需重新启动**,在那之前该通道不可用。

## 技术说明

- 引入三个官方 jar(共 56KB,Apache-2.0):`shizuku-api` / `shizuku-provider` / `shizuku-aidl`;
- 清单新增 Shizuku Provider(`rikka.shizuku.ShizukuProvider`,authorities `com.spark.keeper.shizuku`)
  与权限 `moe.shizuku.manager.permission.API_V23`;
- R8 规则整体保留 `rikka.shizuku.**` 与 `moe.shizuku.**`(provider 由系统实例化、AIDL 走 binder);
- 通道选择集中在 `rootUsable()`(带缓存,避免每条命令都弹 Magisk 授权框)+ `shizukuOut()`,
  顺序**先 root 后 Shizuku**(反了会让 root 检查拿到 shell 的 uid=2000 而误判);

| | |
|---|---|
| 版本 | v0.4.1(versionCode 19) |
| 体积 | 121 KB(引入 Shizuku 后 +16KB) |
| SHA256 | `807EAB4FA00460EBE31F3808C619B9C53A333774A58E8D71B85715F099B8B916` |
