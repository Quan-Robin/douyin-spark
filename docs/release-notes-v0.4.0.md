# v0.4.0 — 设了密码锁也能免 root 自动解锁

## 🔓 新增:无障碍直接输 PIN(免 root)

填好锁屏 PIN → 运行时用**无障碍服务点锁屏数字键盘**输入。不需要 root,不需要装任何东西。

解锁优先级(逐级回落,任一级失败都不会卡住):

| 顺序 | 通道 | 条件 |
|---|---|---|
| 1 | 滑动解锁 | 无密码锁,或 Smart Lock 判定为可信环境 |
| 2 | Root 输密码 | 勾了 Root 且已授权(保留旧方案) |
| 3 | **无障碍点键盘输密码**(本次新增) | 填了 PIN 即可 |
| 4 | 等你解锁后补跑 | 兜底,最长等 N 分钟 |

> 部分 ROM 不让无障碍访问锁屏(锁屏内容不进无障碍树),第 3 级会直接失败并**写日志**,
> 自动落到第 4 级,不会静默失败。

## 💡 两个零成本替代方案(不填 PIN 也行)

1. **Smart Lock**:设置 → 安全 → Smart Lock → 信任地点/信任设备 → 把家设为信任地点。
   之后在家锁屏会变成"滑动解锁",通道 1 直接生效。
2. **充电时不锁屏**:开发者选项 → 「不锁定屏幕(充电时)」。发送时手机在充电就一直不会锁。

## ❌ 为什么没做 Shizuku / 设备管理员(实测结论)

- **设备管理员没有解锁能力**:普通设备管理员只能强制锁屏、设密码策略、擦除、禁摄像头,
  无法解锁、无法驱散 keyguard、无法输密码。只有**设备所有者**能 `setKeyguardDisabled(true)`,
  而那是**把锁屏整个废掉**,还需 ADB 或恢复出厂来配置 —— 代价大于收益。
- **Shizuku 公开 API 不能跑 shell 命令**:javap 实测 `rikka.shizuku.Shizuku`,
  `private static ShizukuRemoteProcess newProcess(String[], String[], String)` —— 是 **private**;
  真正执行命令的 `moe.shizuku.server.IShizukuService` **不在公开 artifact 里**
  (`api-13.1.5.aar` 内只有 `rikka/shizuku/*` 与 `rikka/sui/*`)。
  所以 `input` / `settings` / `wm dismiss-keyguard` 无法照搬。加上重启后 Shizuku 需重新激活,
  对"定时任务"这种场景收益很低,故本次未采用。

> 如果你确实要 Shizuku 通道:唯一可行做法是**内嵌它未公开的 AIDL**(能跑,但会随 Shizuku 升级失效),
> 需要你确认后我再做。

## 安装

| | |
|---|---|
| 版本 | v0.4.0(versionCode 18) |
| 支持 | Android 7.0+ |
