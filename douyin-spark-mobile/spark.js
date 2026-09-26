/**
 * 抖音自动续火花 —— 手机版(AutoX.js / Auto.js 无障碍自动化)
 *
 * 原理:通过无障碍服务操作手机上【已登录】的抖音 App:
 *   启动抖音 → 进入消息页 → 定位好友会话 → 输入消息 → 发送 → 校验
 * 登录态由抖音 App 自身常驻保持,脚本无需处理登录;
 * 定时由 AutoX 的「定时任务」触发,完全在手机本地运行,不依赖电脑。
 *
 * 首次使用:
 *   1. 修改下方【配置区】的好友名
 *   2. 把 TEST_ONLY 改为 true 运行一遍(走到发送一步但不真发),确认流程通畅
 *   3. 改回 false 正式使用;若某步卡住,把 /sdcard/Download/spark_debug/
 *      下的界面结构文件发出来即可适配
 */

// ============================ 配置区 ============================

// 续火花好友列表:name 必须与抖音消息列表/聊天顶部显示的名字一致
// messages 可为该好友单独配置消息池,null 则用全局 GLOBAL_MESSAGES
var FRIENDS = [
    { name: "好友昵称A", messages: null },
    { name: "好友昵称B", messages: null },
];

// 全局消息池:每次随机抽一条,支持 {{friend}} 占位符
var GLOBAL_MESSAGES = [
    "续火花啦 🔥",
    "今日份火花已续上 🔥",
    "打卡续火,不能断 🔥 {{friend}}",
];

var TEST_ONLY = false;        // true = 完整走流程但【不点发送】,调试用
var DEBUG = true;             // true = 悬浮控制台 + 界面结构 dump(排查适配问题)
var HANDLE_LOCKSCREEN = true;      // 锁屏处理:自动亮屏;滑动锁自动解锁;密码锁等待你解锁后补跑
var WAIT_UNLOCK_TIMEOUT_MIN = 480; // 密码锁屏时最长等待解锁的分钟数(超时放弃,等下次触发)
var LOCK_AFTER_DONE = true;        // 完成后自动回锁(仅当本次是脚本自己解锁的)
var UNLOCK_SWIPE = null;           // 解锁滑动手势 [x1, y1, x2, y2];null = 默认上滑
var RETRY_PER_FRIEND = 2;     // 每个好友失败后的重试次数
var MAX_SCROLL_ROUNDS = 6;    // 消息列表最多滚动轮数(找不到才走搜索)
var STEP_DELAY = [800, 2000]; // 操作间随机延时范围(毫秒),模拟人工节奏
var LAUNCH_WAIT = 6000;       // 启动抖音后的等待毫秒数(加载慢的手机调大)
var KEEP_STATE_DAYS = 7;      // 发送记录保留天数

// ========================== 以下无需修改 ==========================

var STATE_FILE = files.cwd() + "/spark_state.json";
var DEBUG_DIR = "/sdcard/Download/spark_debug";
var DOUYIN_PKG = "com.ss.android.ugc.aweme";

// ---------------------------------------------------------------- 日志
function pad(n) {
    return n < 10 ? "0" + n : "" + n;
}

function todayStr() {
    var d = new Date();
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
}

function nowTime() {
    var d = new Date();
    return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
}

function logStep(msg) {
    var line = nowTime() + " " + msg;
    console.log(line);
    try {
        var logFile = files.cwd() + "/logs/spark-" + todayStr() + ".log";
        files.createWithDirs(logFile);
        files.append(logFile, line + "\n");
    } catch (e) {
        // 日志失败不影响主流程
    }
}

// 把当前屏幕上所有可见控件的文本/描述保存下来,用于适配排查
function dumpScreen(tag) {
    if (!DEBUG) return;
    try {
        files.createWithDirs(DEBUG_DIR + "/.keep");
        var lines = ["time=" + nowTime(), "pkg=" + currentPackage(), "activity=" + currentActivity()];
        var classNames = [
            "android.widget.TextView",
            "android.widget.EditText",
            "android.widget.Button",
            "android.view.View",
            "android.widget.ImageView",
        ];
        for (var c = 0; c < classNames.length; c++) {
            var ws = className(classNames[c]).find();
            for (var i = 0; i < ws.length; i++) {
                var w = ws[i];
                var t = ((w.text() || "") + " | " + (w.desc() || "")).trim();
                if (t && t !== "|") {
                    lines.push(classNames[c] + "\t" + t.slice(0, 80) + "\t" + w.bounds());
                }
            }
        }
        var path = DEBUG_DIR + "/dump-" + tag + "-" + Date.now() + ".txt";
        files.write(path, lines.join("\n"));
        logStep("已保存界面结构: " + path);
    } catch (e) {
        logStep("dumpScreen 失败: " + e);
    }
}

// ---------------------------------------------------------------- 通用
function randomDelay() {
    return Math.floor(STEP_DELAY[0] + Math.random() * (STEP_DELAY[1] - STEP_DELAY[0]));
}

function safeClick(w) {
    if (!w) return false;
    try {
        var b = w.bounds();
        if (b.width() <= 0 || b.height() <= 0) return false;
        if (w.clickable() && w.click()) return true;
        // 文本节点本身通常不可点击,点击其中心坐标
        return click(b.centerX(), b.centerY());
    } catch (e) {
        return false;
    }
}

// 依序尝试多个选择器工厂,任一命中即返回(自动分配超时预算)
function findOne(factories, timeout) {
    var per = Math.max(300, Math.floor((timeout || 3000) / factories.length));
    for (var i = 0; i < factories.length; i++) {
        try {
            var w = factories[i]().findOne(per);
            if (w) return w;
        } catch (e) {
            // 单个选择器异常继续下一个
        }
    }
    return null;
}

// 关闭常见弹窗(青少年模式/更新/开屏广告等)
function dismissPopups() {
    var labels = ["我知道了", "以后再说", "稍后再说", "跳过", "关闭"];
    for (var round = 0; round < 3; round++) {
        var hit = null;
        for (var i = 0; i < labels.length; i++) {
            hit = findOne(
                [
                    function (l) {
                        return function () {
                            return text(l).boundsInside(0, 0, device.width, device.height);
                        };
                    }(labels[i]),
                    function (l) {
                        return function () {
                            return desc(l).boundsInside(0, 0, device.width, device.height);
                        };
                    }(labels[i]),
                ],
                400
            );
            if (hit) break;
        }
        if (!hit) return;
        logStep("关闭弹窗: " + labels[i]);
        safeClick(hit);
        sleep(800);
    }
}

// ---------------------------------------------------------------- 锁屏处理
var SELF_UNLOCKED = false; // 本次运行是否由脚本自行解锁(决定完成后是否回锁)

// 当前是否处于锁屏(Keyguard)。查询系统服务,不依赖屏幕内容
function isKeyguardLocked() {
    try {
        var km = context.getSystemService("keyguard");
        if (km.isKeyguardLocked) return km.isKeyguardLocked();
        return km.inKeyguardRestrictedInputMode();
    } catch (e) {
        return false; // 拿不到锁屏状态就当未锁,按原流程执行
    }
}

function swipeUnlock() {
    var w = device.width,
        h = device.height;
    if (UNLOCK_SWIPE) {
        swipe(UNLOCK_SWIPE[0], UNLOCK_SWIPE[1], UNLOCK_SWIPE[2], UNLOCK_SWIPE[3], 400);
        return;
    }
    swipe(Math.floor(w / 2), Math.floor(h * 0.82), Math.floor(w / 2), Math.floor(h * 0.30), 400);
}

// 亮屏并尽量解锁。返回 {ok: 是否可以继续执行, selfUnlocked: 是否脚本自己解的锁}
// 滑动锁/无锁:自动解锁;密码/指纹/人脸锁:系统不允许 app 越过,转为等待用户下次解锁
function prepareScreen() {
    try {
        device.wakeUpIfNeeded();
    } catch (e) {}
    sleep(1000);
    if (!isKeyguardLocked()) {
        return { ok: true, selfUnlocked: false };
    }
    for (var i = 0; i < 3 && isKeyguardLocked(); i++) {
        logStep("锁屏中,尝试上滑解锁(" + (i + 1) + "/3)");
        swipeUnlock();
        sleep(1200);
    }
    if (!isKeyguardLocked()) {
        logStep("已自动解锁");
        return { ok: true, selfUnlocked: true };
    }
    logStep(
        "检测到密码/指纹锁屏(脚本不能越过安全锁),等待你下次解锁后自动继续," +
            "最长 " + WAIT_UNLOCK_TIMEOUT_MIN + " 分钟"
    );
    var deadline = Date.now() + WAIT_UNLOCK_TIMEOUT_MIN * 60 * 1000;
    while (Date.now() < deadline) {
        sleep(45000);
        if (!isKeyguardLocked()) {
            logStep("检测到已解锁,继续执行续火");
            try {
                device.wakeUpIfNeeded();
            } catch (e) {}
            return { ok: true, selfUnlocked: true };
        }
    }
    return { ok: false, selfUnlocked: false };
}

// ---------------------------------------------------------------- 导航
// 启动抖音并等待就绪
function launchDouyin() {
    var ok = false;
    try {
        ok = app.launchPackage(DOUYIN_PKG);
    } catch (e) {
        ok = false;
    }
    if (!ok) ok = app.launchApp("抖音");
    if (!ok) {
        logStep("无法启动抖音(请确认已安装且名称未被改动)");
        return false;
    }
    logStep("抖音已启动,等待加载…");
    sleep(LAUNCH_WAIT);
    dismissPopups();
    return true;
}

// 屏幕底部导航区域的上边界
function bottomTop() {
    return device.height - Math.floor(device.height * 0.12);
}

// 在底部导航栏找指定 tab
function findBottomTab(label) {
    var w = device.width,
        h = device.height,
        top = bottomTop();
    var sels = [
        function () {
            return desc(label).boundsInside(0, top, w, h);
        },
        function () {
            return text(label).boundsInside(0, top, w, h);
        },
        function () {
            return descContains(label).boundsInside(0, top, w, h);
        },
        function () {
            return textContains(label).boundsInside(0, top, w, h);
        },
    ];
    for (var i = 0; i < sels.length; i++) {
        try {
            var hit = sels[i]().findOne(1200);
            if (hit) return hit;
        } catch (e) {}
    }
    return null;
}

// 回到消息页(含私信筛选)。任何页面状态都能通过"找不到 tab 就 back"收敛
function openMessagesPage() {
    for (var attempt = 0; attempt < 4; attempt++) {
        dismissPopups();
        var tab = findBottomTab("消息");
        if (tab) {
            safeClick(tab);
            sleep(randomDelay());
            dismissPopups();
            // 部分版本消息页有「私信/互动」筛选页签
            var pm = findOne(
                [
                    function () {
                        return text("私信").findOne(500);
                    },
                    function () {
                        return desc("私信").findOne(400);
                    },
                ],
                1000
            );
            if (pm) {
                safeClick(pm);
                sleep(600);
            }
            return true;
        }
        logStep("不在主页面,按返回键回退(" + (attempt + 1) + "/4)");
        back();
        sleep(1200);
    }
    logStep("无法回到消息页");
    dumpScreen("msg-page-fail");
    return false;
}

// 聊天输入框:必须位于屏幕下半部分。
// 消息列表页顶部的搜索框同样是 EditText,不限定位置就会把它当成聊天输入框
// (后果:消息被写进搜索框、发送校验还会误判成功)。
function chatInput() {
    var h = device.height,
        top = Math.floor(h * 0.45);
    return className("android.widget.EditText")
        .boundsInside(0, top, device.width, h)
        .findOne(1200);
}

// 当前是否已处于聊天页(以"下半部分的输入框"为标志,兼容非 EditText 输入框的版本)
function inChatPage() {
    if (chatInput()) return true;
    var top = Math.floor(device.height * 0.45);
    return !!findOne(
        [
            function () {
                return descContains("发消息").boundsInside(0, top, device.width, device.height).findOne(500);
            },
            function () {
                return textContains("按住说话").boundsInside(0, top, device.width, device.height).findOne(500);
            },
        ],
        1100
    );
}

// 在消息列表滚动一轮
function scrollList() {
    var w = device.width,
        h = device.height;
    swipe(Math.floor(w / 2), Math.floor(h * 0.72), Math.floor(w / 2), Math.floor(h * 0.35), 500);
    sleep(randomDelay());
}

// 搜索兜底:通过消息页搜索进入聊天
function searchOpenChat(name) {
    var entry = findOne(
        [
            function () {
                return desc("搜索").findOne(1500);
            },
            function () {
                return descContains("搜索").boundsInside(0, 0, device.width, Math.floor(device.height * 0.25)).findOne(1000);
            },
            function () {
                return text("搜索").boundsInside(0, 0, device.width, Math.floor(device.height * 0.25)).findOne(1000);
            },
            function () {
                return idContains("search").boundsInside(0, 0, device.width, Math.floor(device.height * 0.25)).findOne(1000);
            },
        ],
        3500
    );
    if (!entry) {
        logStep("未找到搜索入口");
        return false;
    }
    safeClick(entry);
    sleep(1200);
    var input = className("android.widget.EditText").findOne(4000);
    if (!input) {
        logStep("搜索页未找到输入框");
        back();
        sleep(800);
        return false;
    }
    input.setText(name);
    sleep(2500);
    // 结果条目必须排除顶部输入区:脚本刚把名字填进搜索框,
    // 不排除的话第一个 text(name) 命中的就是搜索框自己,点它等于原地打转
    var top = Math.floor(device.height * 0.15);
    var hit = findOne(
        [
            function () {
                return text(name).boundsInside(0, top, device.width, device.height).findOne(1500);
            },
            function () {
                return textContains(name).boundsInside(0, top, device.width, device.height).findOne(1200);
            },
        ],
        3000
    );
    if (!hit) {
        logStep("搜索结果中未找到: " + name);
        back();
        sleep(800);
        return false;
    }
    safeClick(hit);
    sleep(1500);
    // 可能进入的是个人主页而不是聊天页:找「发消息」
    if (!inChatPage()) {
        var topZone = Math.floor(device.height * 0.15);
        var send = findOne(
            [
                function () {
                    return text("发消息").boundsInside(0, topZone, device.width, device.height).findOne(1500);
                },
                function () {
                    return desc("发消息").boundsInside(0, topZone, device.width, device.height).findOne(1200);
                },
                function () {
                    return textContains("发消息").boundsInside(0, topZone, device.width, device.height).findOne(1200);
                },
            ],
            3000
        );
        if (!send) {
            logStep("搜索后既不在聊天页也无「发消息」按钮");
            dumpScreen("search-no-chat");
            return false;
        }
        safeClick(send);
        sleep(1500);
    }
    return inChatPage();
}

// 打开与指定好友的聊天页(列表直找 → 滚动 → 搜索)
function openChat(name) {
    // 匹配区域排除屏幕顶部 15%:搜索框、筛选栏、"新朋友/限时日常"横幅都在那里,
    // 聊天预览文本里也可能出现好友名,不排除会点错人(README v0.1.2 就是这么承诺的)
    var listTop = Math.floor(device.height * 0.15);
    for (var round = 0; round <= MAX_SCROLL_ROUNDS; round++) {
        var hit = findOne(
            [
                function () {
                    return text(name).boundsInside(0, listTop, device.width, device.height).findOne(1200);
                },
                function () {
                    return textContains(name).boundsInside(0, listTop, device.width, device.height).findOne(900);
                },
                function () {
                    return descContains(name).boundsInside(0, listTop, device.width, device.height).findOne(900);
                },
            ],
            2800
        );
        if (hit) {
            logStep("找到「" + name + "」" + (round > 0 ? "(滚动第 " + round + " 轮)" : ""));
            safeClick(hit);
            sleep(1500);
            if (inChatPage()) return true;
            // 点到的是列表项但没进聊天页(可能误触其他同名文本),回消息页重来
            logStep("点击后未进入聊天页,重新定位");
            if (!openMessagesPage()) return false;
            continue;
        }
        scrollList();
    }
    logStep("列表滚动 " + MAX_SCROLL_ROUNDS + " 轮未找到,尝试搜索: " + name);
    return searchOpenChat(name);
}

// ---------------------------------------------------------------- 发送
function pickMessage(friend) {
    var pool = friend.messages && friend.messages.length ? friend.messages : GLOBAL_MESSAGES;
    var tpl = pool[Math.floor(Math.random() * pool.length)];
    return tpl.replace(/\{\{friend\}\}/g, friend.name);
}

// 在当前聊天页输入并发送消息,返回是否校验成功
function sendMessageInChat(msg) {
    var input = chatInput();
    if (!input) {
        logStep("聊天页未找到输入框(输入框应位于屏幕下半部分)");
        dumpScreen("no-input");
        return false;
    }
    safeClick(input);
    sleep(600);
    input = chatInput() || input;

    var typed = false;
    try {
        typed = input.setText(msg);
    } catch (e) {
        typed = false;
    }
    if (!typed) {
        // 兜底:剪贴板粘贴
        try {
            setClip(msg);
            typed = input.paste();
        } catch (e) {
            typed = false;
        }
    }
    if (!typed) {
        logStep("无法写入输入框(控件非标准 EditText,需适配)");
        dumpScreen("input-fail");
        return false;
    }
    logStep("已输入 " + msg.length + " 个字");
    sleep(randomDelay());

    var btn = findOne(
        [
            function () {
                return text("发送").findOne(2000);
            },
            function () {
                return desc("发送").findOne(1200);
            },
            function () {
                return textContains("发送").findOne(1200);
            },
        ],
        3500
    );
    if (!btn) {
        logStep("未找到发送按钮(输入可能未生效)");
        dumpScreen("no-send-btn");
        return false;
    }
    if (TEST_ONLY) {
        logStep("【TEST_ONLY】已走到发送一步,按配置不真发");
        back();
        sleep(800);
        return true;
    }
    safeClick(btn);
    sleep(1500);

    // 校验:消息气泡出现(强证据)或输入框被清空。
    // 以前输入框找不到时 cleared 直接为 true,没发出去也会被记成成功
    var input2 = chatInput();
    var snippet = msg.slice(0, Math.min(8, msg.length));
    var bubble = snippet ? textContains(snippet).findOne(1200) : null;
    var cleared = input2 ? (input2.text() || "").length === 0 : false;
    var ok = !!bubble || cleared;
    if (ok) {
        logStep("✅ 发送成功: " + msg);
        back(); // 回到消息列表,便于处理下一个好友
        sleep(1000);
    } else {
        logStep("发送后未校验到成功迹象");
        dumpScreen("send-verify-fail");
    }
    return ok;
}

// ---------------------------------------------------------------- 状态
function loadState() {
    try {
        if (files.exists(STATE_FILE)) {
            var data = JSON.parse(files.read(STATE_FILE));
            if (data && typeof data === "object") return data;
        }
    } catch (e) {}
    return {};
}

function saveState(state) {
    try {
        var keys = Object.keys(state).sort();
        while (keys.length > KEEP_STATE_DAYS) {
            delete state[keys.shift()];
        }
        files.createWithDirs(STATE_FILE);
        files.write(STATE_FILE, JSON.stringify(state, null, 2));
    } catch (e) {
        logStep("保存状态失败: " + e);
    }
}

// ---------------------------------------------------------------- 主流程
function main() {
    logStep("=== 抖音自动续火花启动" + (TEST_ONLY ? "(TEST_ONLY 调试模式)" : "") + " ===");
    var today = todayStr();
    var state = loadState();
    if (!state[today]) state[today] = {};

    var pending = [];
    for (var i = 0; i < FRIENDS.length; i++) {
        if (!state[today][FRIENDS[i].name]) pending.push(FRIENDS[i]);
    }
    if (pending.length === 0) {
        logStep("今日所有好友均已续过火花,无需重复发送");
        finish(true);
        return;
    }

    // 锁屏处理:先确认屏幕可用(已完成时不会打扰屏幕)
    if (HANDLE_LOCKSCREEN) {
        var screen = prepareScreen();
        if (!screen.ok) {
            logStep("等待解锁超时,本次放弃(等下次定时触发或手动运行)");
            finish(false);
            return;
        }
        SELF_UNLOCKED = screen.selfUnlocked;
    }

    if (!launchDouyin()) {
        finish(false);
        return;
    }

    var failed = [];
    for (var f = 0; f < pending.length; f++) {
        var friend = pending[f];
        var ok = false;
        for (var attempt = 0; attempt <= RETRY_PER_FRIEND; attempt++) {
            logStep(
                "处理「" + friend.name + "」(第 " + (attempt + 1) + "/" + (RETRY_PER_FRIEND + 1) + " 次)"
            );
            if (!openMessagesPage()) {
                logStep("页面导航受阻,重新启动抖音再试");
                launchDouyin();
                continue;
            }
            if (!openChat(friend.name)) {
                logStep("打不开会话: " + friend.name);
                continue;
            }
            var msg = pickMessage(friend);
            logStep("准备发送: " + msg);
            ok = sendMessageInChat(msg);
            if (ok) break;
            sleep(2000);
        }
        // TEST_ONLY 的成功不写入记录,避免正式运行被“今日已完成”拦截
        if (!TEST_ONLY) {
            state[today][friend.name] = ok;
            saveState(state);
        }
        if (!ok) failed.push(friend.name);
        sleep(randomDelay());
    }

    if (failed.length === 0) {
        logStep("🎉 全部目标续火完成");
        finish(true);
    } else {
        logStep("⚠️ 有失败目标: " + failed.join(", ") + "(日志与界面结构见上方路径)");
        finish(false);
    }
}

function finish(ok) {
    if (ok && SELF_UNLOCKED && LOCK_AFTER_DONE) {
        try {
            device.lockScreen();
            logStep("已自动回锁屏幕");
        } catch (e) {
            logStep("自动回锁不可用(系统将在超时后自行锁屏),可忽略");
        }
    }
    logStep("=== 运行结束 ===");
    if (DEBUG) sleep(2500); // 留出查看控制台的时间
    exit(ok ? 0 : 1);
}

// ---------------------------------------------------------------- 入口
auto.waitFor(); // 无障碍服务未开启时会引导去系统设置开启
if (DEBUG) console.show();
main();
