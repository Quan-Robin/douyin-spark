# douyin-spark-keeper 🔥 抖音自动续火花

基于**抖音网页版**私信(`https://www.douyin.com/chat`)的自动续火花工具:每天定时自动打开与指定好友的聊天窗口、发送一条消息,维持火花不断裂。

## 工作原理

```
扫码登录(一次) → 持久化浏览器配置(user_data/)保存登录态
        ↓
每天定时启动 → 打开网页版聊天页 → 在会话列表定位目标好友
        ↓
输入框注入消息(Draft.js 粘贴事件) → 点击发送 → 校验发送成功
        ↓
写状态记录(state.json,当日防重发) → 等待明天
```

关键技术点:

- **登录态**:使用 Playwright 持久化浏览器配置文件,扫码登录一次后长期有效(无需导出 Cookie)。
- **输入框**:抖音网页版聊天输入框基于 Draft.js,常规 `fill()/type()` 无法触发其内部状态更新,本工具通过注入 `ClipboardEvent('paste')` 粘贴事件写入文本(附 `insertText`、逐字输入两级降级)。
- **多版本 DOM**:抖音对不同账号灰度推送不同的私信页面结构,本工具对会话条目 / 输入框 / 发送按钮均采用**多选择器降级链**,并提供 `probe` 探查命令在改版后快速校准。

## 环境要求

- Windows / macOS / Linux
- Python 3.10+
- 本机装有 Chrome 或 Edge(推荐,指纹更真实);都没有时会自动下载 Playwright Chromium

## 安装

```bash
cd douyin-spark-keeper
pip install -r requirements.txt
# 可选:仅当本机没有 Chrome/Edge 时需要(约 120MB)
playwright install chromium
```

## 快速开始(3 步)

### 1. 配置

```bash
cp config.example.yaml config.yaml
```

编辑 `config.yaml`,至少修改 `targets`(好友显示名):

```yaml
send_time: "21:30"       # 每天发送时间
jitter_minutes: 30       # 时间随机抖动(分钟),行为更自然
targets:
  - name: "好友昵称或备注A"   # 必须与网页版会话列表显示的名字一致
  - name: "好友昵称或备注B"
messages:
  - "续火花啦 🔥 {{friend}}"
  - "今日份火花已续上 🔥"
```

消息支持占位符:`{{friend}}` 好友名、`{{date}}` 日期、`{{time}}` 时间、`{{weekday}}` 星期几。

> ⚠️ **`send_time` 一定要带引号**。写成 `send_time: 21:30`(不带引号)时 YAML 会按「六十进制」把它解析成整数 `1290`,以前这会让守护模式每 10 分钟记一条异常、永远不发消息;现在会在启动时直接报错并提示正确写法。`interval_seconds` 写成单个数字 `10` 也是允许的(等价于 `[10, 10]`)。

### 2. 登录

```bash
python -m spark login
```

会弹出浏览器窗口并打开抖音聊天页,用**抖音 App 扫码**登录(或验证码登录)。看到"登录成功"提示后即可关闭,登录态已保存到 `user_data/` 目录,以后不需要重复登录。

### 3. 测试发送

```bash
python -m spark send
```

立即执行一次完整的续火流程,观察日志确认每个好友发送成功。

## 日常使用

### 守护模式(推荐)

```bash
python -m spark daemon
```

常驻运行,每天在 `send_time ± jitter_minutes` 内自动发送一次;发送失败自动重试,登录失效时给出提示。`Ctrl+C` 退出。

### Windows 任务计划程序(开机免登录后台跑)

如果不想开黑窗口常驻,可以在"任务计划程序"里建一个每天触发的任务:

- 程序:`python.exe`
- 参数:`-m spark send`(绝对路径下,例如 `D:\AI\ZCode\douyin-spark-keeper`)
- 起始于:项目目录

每天到点跑一次就退出,`state.json` 保证同一天不会重复发送。

## 命令一览

| 命令 | 说明 |
|---|---|
| `python -m spark login` | 打开浏览器扫码登录,保存登录态 |
| `python -m spark send` | 立即执行一次发送(`--force` 强制重发、`--only 名字` 只发某人;名字写错时报错并返回 2) |
| `python -m spark daemon` | 守护模式,每天定时自动发送 |
| `python -m spark probe` | 导出聊天页 DOM 结构与截图(改版排障用) |
| `python -m spark send -v` | 输出调试日志 |

## 常见问题

**提示"会话列表中找不到「xxx」"**
确认 `name` 与网页版聊天页左侧列表里显示的名字**完全一致**(含表情、空格);对方必须在你的私信会话列表里(双方互相关注或有过聊天)。较久未聊天的会话可能被挤到列表深处,工具会自动滚动查找。

**提示"找不到聊天输入框" / 发送总失败**
抖音在灰度改版。运行 `python -m spark probe`,把生成的 `logs/probe-*.json` 和 `probe-*.png` 发给助手即可校准选择器。

**发送的消息对方没收到,但显示成功**
失败重试和成功校验都有截图与日志(`logs/` 目录),可对照排查;也可能触发过风控提示,减少目标数量与发送频率即可。

**关于火花机制**
抖音火花基于双方连续互动:工具负责**你这一侧**每天准时发消息;如果火花规则要求对方当天也回复,建议和好友约定对方随手回一句。给对方发送的第一条消息建议自己手动发,避免突兀。

**风控与安全**
工具模拟正常浏览器操作并带随机延迟,但请勿配置大量目标或高频发送;仅供个人账号日常使用,请遵守抖音用户协议。

## 目录结构

```
douyin-spark-keeper/
├── config.example.yaml   # 配置模板
├── config.yaml           # 你的配置(gitignore,含隐私)
├── spark/
│   ├── cli.py            # 命令行入口
│   ├── config.py         # 配置加载
│   ├── browser.py        # 浏览器上下文与登录检测
│   ├── sender.py         # 核心:会话定位 / 消息注入 / 发送验证
│   ├── scheduler.py      # 定时守护
│   └── state.py          # 当日防重发状态
├── user_data/            # 浏览器登录态(自动生成,勿外传)
├── logs/                 # 运行日志 / 失败截图 / probe 结果
└── state.json            # 发送记录(自动生成)
```

## 隐私提示

`user_data/` 保存了你的抖音登录态,`logs/` 可能包含聊天截图,请勿提交到仓库或分享给他人(`.gitignore` 已默认忽略)。

## 修复记录(0.1.1)

- **守护模式的实际发送时间被推向窗口末端**:`_today_target()` 原来在每轮循环都重摇一次抖动,而循环只在新摇出的时间 ≤ 现在时才发送,越晚命中概率越高。现在每天只在首次进入循环时摇一次,日志会打印「今日计划发送时间」。
- **`--only` 名字写错会报告成功**:以前会落到「所有目标今天都已成功发送」并返回 0,现在会列出配置里的好友名并返回 2。
- **状态文件结构异常会崩**:`state.json` 里当天条目缺少 `sent` 键时 `mark_sent` 抛 `KeyError`、条目不是字典时 `sent_today` 抛 `AttributeError`;两者现在都会自动重建,损坏的 JSON 也按空状态处理。发送成功之后写状态失败不再反过来把这一条判成失败。
- **发送校验可能说谎**:改成「先看消息气泡,再看输入框是否清空」,输入框读不到时不再默认成功(修掉 `snippet` 为空串时 `\"\" in joined` 恒为 True 的问题)。
- **配置校验前置**:`send_time` 校验并规整(含上面的引号陷阱)、`jitter_minutes` 不为负、`interval_seconds` 支持单个数字、非法 YAML 变成可读的报错。
- **`send -v` 无法使用**:README 里写的 `python -m spark send -v` 以前会报 `unrecognized arguments`;`-c/-v` 现在放在子命令前后都可以。
- **日志目录不一致**:控制台/文件日志固定写 `PROJECT_DIR/logs`,而 probe 结果与失败截图写配置文件同级目录;现在统一到 `-c` 指定的配置文件同级。
- **`browser_channel: chromium` 白跑一次**:该值不是 Playwright 的合法 channel(内置浏览器必须不传 channel),现在直接按「内置 Chromium → Chrome → Edge」顺序尝试。
