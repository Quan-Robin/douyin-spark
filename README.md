# 抖音自动续火花(douyin-spark)

每天在设定时间附近,自动给指定好友发一条消息,维持抖音「火花」不断。仓库里是**两个互相独立的实现**,按需选一个:

| 目录 | 形态 | 适合谁 | 依赖 |
|---|---|---|---|
| [douyin-spark-mobile](douyin-spark-mobile/) | **手机端**:Android 原生 APK(无障碍模式 + 实验性协议模式)、以及一份 AutoX.js 脚本 | 不想开电脑、希望完全在手机上跑 | 一台 Android 手机(Android 7.0+) |
| [douyin-spark-keeper](douyin-spark-keeper/) | **电脑端**:Python + Playwright 操作抖音网页版私信 | 有常开的电脑/NAS,想跑在服务器或计划任务里 | Python 3.10+、Chrome 或 Edge |

> 两种形态都只是**替你完成"打开会话 → 输入 → 发送"这几个点击**,不修改抖音客户端、不逆向通信协议。

---

## 快速开始

### 手机端(推荐)

1. 构建 APK(见下方[构建](#构建))或从 Releases 下载 `SparkKeeper.apk`
2. 安装后打开「续火花」,依次:① 开启无障碍服务 → ② 填好友名 → ③ 填消息池 → ④ 设每天发送时间与随机抖动 → ⑤ 保存并设置每天定时
3. 首次请勾选「测试模式」跑一遍,确认能自动打开抖音、进入会话、把消息写进输入框(**不会真发**),再取消勾选正式使用
4. 系统设置里给「续火花」和抖音**关闭电池优化 / 允许自启动**,否则定时会被系统杀掉

细节、常见问题、两种发送模式的区别见 [douyin-spark-mobile/README.md](douyin-spark-mobile/README.md)。

### 电脑端

```bash
cd douyin-spark-keeper
pip install -r requirements.txt
cp config.example.yaml config.yaml     # 填好友名与消息
python -m spark login                  # 扫码登录一次,登录态存在 user_data/
python -m spark send                   # 试跑一次
python -m spark daemon                 # 常驻:每天定点自动发送
```

细节见 [douyin-spark-keeper/README.md](douyin-spark-keeper/README.md)。

---

## 构建

### Android APK(不需要 Gradle)

只需要 JDK 17 + Android SDK build-tools 34 + Python(Pillow,用于生成图标):

```bash
cd douyin-spark-mobile/apk
ANDROID_SDK=/path/to/android-sdk JDK_HOME=/path/to/jdk-17 bash build.sh
# 产物:douyin-spark-mobile/SparkKeeper.apk
```

- Windows 下脚本默认使用 `D:/AI/ZCode/build-env/` 里的工具链,可用上面三个环境变量覆盖。
- 首次构建会自动生成 `debug.keystore`(仅供自用安装;正式发布请换成你自己的签名,并保留好密钥,否则无法覆盖升级)。
- 应用图标:把一张 1024×1024 的正方形 PNG 放到 `apk/icon-source.png`(**不要放进 `res/`**,否则会被 aapt2 当资源编译进包),重新构建即可;没有该文件时 `make_icons.py` 会画一个内置占位图。图标提示词见 [douyin-spark-mobile/README.md](douyin-spark-mobile/README.md#应用图标)。

### 电脑端

```bash
pip install -r douyin-spark-keeper/requirements.txt
playwright install chromium   # 仅当本机没有 Chrome/Edge 时需要
```

---

## 隐私与安全(务必阅读)

- **`douyin-spark-keeper/user_data/` 等同于你的账号凭据**:它是完整的浏览器 profile,含抖音登录 Cookie 与浏览历史。仓库的 `.gitignore` 已忽略它,但**发布/分享前请再确认一次**。
- 手机端协议模式的登录 Cookie 存在 App 私有目录,卸载即清除,不会上传到任何地方。
- 本工具不收集、不上报任何数据,也没有任何统计 SDK。
- 请遵守抖音用户协议:**只用于个人账号的日常互动**,不要配置大量目标或高频发送,以免触发风控。
- 账号风险、风控限制由使用者自行承担。

---

## 目录结构

```
.
├── douyin-spark-mobile/          # 手机端(Android APK + AutoX.js 脚本)
│   ├── apk/                      # 原生工程:Java 源码、资源、build.sh、make_icons.py
│   ├── spark.js                  # AutoX.js 脚本版(二选一)
│   └── README.md
├── douyin-spark-keeper/          # 电脑端(Python + Playwright)
│   ├── spark/                    # cli / config / browser / sender / scheduler / state
│   ├── config.example.yaml
│   └── README.md
├── docs/
│   ├── douyin-web-chat-selectors.md   # 网页版私信 DOM 实测选择器与开源实现对照
│   └── publishing-checklist.md        # 开源发布前检查清单
├── LICENSE                       # MIT
└── .gitignore
```

## 开源说明

- 许可证:[MIT](LICENSE)(如需换成 Apache-2.0 / GPL,替换 LICENSE 并同步更新本节即可)
- 发布前请对照 [docs/publishing-checklist.md](docs/publishing-checklist.md) 逐项确认(重点:登录态、好友名、日志)
- 抖音网页版的 DOM 会随灰度改版,选择器都在代码里集中维护,改版后按 docs 里的对照表校准
