package com.spark.keeper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验性「协议直发」模式:离屏 WebView 打开抖音网页版聊天页。
 * 交互流程与选择器参考实战项目 douyin-auto-spark(GPL-3.0):
 *   搜索框 input.semi-input[placeholder="搜索"] → 搜索结果 .SearchPanelitembox(精确匹配)
 *   → 结果内点「发消息/发私信」 → 编辑器 .messageEditorimChatEditorContainer
 *     [data-slate-editor="true"][contenteditable="true"] → execCommand insertText → Enter 发送。
 * 通信经 SparkBridge 主动回传,不依赖 evaluateJavascript 返回值。
 */
public class ProtocolService {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    private static final String CHAT_URL = "https://www.douyin.com/chat";
    /** 备用地址:不同灰度下私信页的路由不一样(有实现用的是 /messages)。 */
    private static final String CHAT_URL_ALT = "https://www.douyin.com/messages";

    // ---------------------------------------------------------------- 选择器降级链
    // 抖音网页版对不同账号灰度推送不同结构、类名还带哈希后缀,任何"单条选择器"都会
    // 在改版后直接失效(协议模式之前就只有一个 .SearchPanelitembox,所以一旦不匹配
    // 就永远搜不到结果)。这里全部改成候选链,由页面内的 __spkFirst 依次尝试。
    // 下列候选链取自多个在用的开源实现(unmev/douyin-auto-fire、2061360308/DouYinSparkFlow、
    // bling-yshs/douyin-auto-spark、Yuriz132/douyin-cloud-streak)里实际使用的选择器,
    // 按"精确 → 前缀 → 兜底"排列。类名是 BEM 风格且随灰度变化,所以必须给足候选。
    private static final String[] SEL_SEARCH_INPUT = {
            "input.semi-input[placeholder='搜索']",
            "input[placeholder='搜索']",
            "input[placeholder*='搜索']",
            "[role='textbox'][placeholder*='搜索']",
            "input[aria-label*='搜索']",
            "[role='textbox'][aria-label*='搜索']",
    };
    private static final String[] SEL_CONVERSATION = {
            "[data-e2e='conversation-item']",
            ".conversationConversationItemwrapper",
            "[class*='conversationConversationItem']",
            "[class*='ConversationItem']",
            "[class*='messageMessageListlist'] [data-index]",
    };
    private static final String[] SEL_CONVERSATION_TITLE = {
            ".conversationConversationItemtitle",
            "[class*='conversationConversationItemtitle']",
            "[class*='ConversationItemtitle']",
            "[class*='Itemtitle']",
    };
    private static final String[] SEL_RESULT_ITEM = {
            ".SearchPanelitembox",
            "[class*='SearchPanelitembox']",
            "[class*='SearchPanelitem-box']",
            "[class*='SearchPanelitem_box']",
            "[class*='SearchPanel'] [class*='item']",
    };
    private static final String[] SEL_RESULT_ACTION = {
            "[class*='SearchPanelitemchat_btn']",
            "[class*='itemchat_btn']",
            "button[class*='SearchPanel']",
    };
    private static final String[] SEL_EDITOR = {
            ".messageEditorimChatEditorContainer [data-slate-editor='true'][contenteditable='true']",
            "[data-slate-editor='true'][contenteditable='true']",
            "[class*='messageEditor'] [contenteditable='true']",
            ".DraftEditor-editor [contenteditable='true']",
            "[contenteditable='true'][data-placeholder*='发送消息']",
            "[contenteditable='true'][aria-label*='消息']",
            "div[contenteditable='true'][role='textbox']",
            "div[contenteditable='true']",
    };
    private static final String[] SEL_SEND_BUTTON = {
            "[class*='messageMsgInputpublishBtn']",
            "[class*='e2e-send-msg-bt']",
            "button[aria-label*='发送']",
            "[role='button'][aria-label*='发送']",
            "[data-e2e='send-msg-btn']",
    };
    /**
     * 已发出的消息气泡(用于校验真的发出去了)。
     * 全部限定在消息列表容器内 —— 会话列表里也有"最近一条消息"的预览文本,不加限定会误判。
     */
    private static final String[] SEL_MSG_BUBBLE = {
            "[data-e2e='msg-item-content']",
            "[class*='messageMessageBoxcontentBox']",
            "[class*='messageMessageListlist'] [class*='messageBox']",
            "[class*='messageMessageListlist'] [data-index]",
            "[class*='MessageList'] [class*='messageBox']",
            "[class*='messageContent'] [class*='messageBox']",
    };
    /** 发送失败的标记(出现即判定失败,比"编辑器是否清空"可靠)。 */
    private static final String[] SEL_SEND_FAILED = {
            "[class*='ContentSideSendStatusretry']",
            "[class*='SendStatusretry']",
            "[aria-label*='重试']",
            "[title*='重试']",
    };

    /**
     * 每个脚本都自带的小工具函数。
     * 不能依赖"注入一次全局变量"的做法:SPA 路由切换/刷新后 window 上的东西就没了。
     */
    private static final String JS_LIB =
            "function __spkVis(e){if(!e)return false;try{var r=e.getBoundingClientRect();"
                    + "return r.width>2&&r.height>2;}catch(x){return false;}}"
                    // 部分灰度把私信 UI 放进 wujie 的 shadow root,这时 document.querySelector 一个都找不到
                    + "function __spkRoots(){var r=[document];try{"
                    + "var h=document.querySelectorAll('wujie-app,[data-wujie],#wujie-app');"
                    + "for(var i=0;i<h.length;i++){if(h[i].shadowRoot)r.push(h[i].shadowRoot);}}catch(x){}"
                    + "return r;}"
                    + "function __spkDeep(sel){var rs=__spkRoots();for(var i=0;i<rs.length;i++){"
                    + "try{var e=rs[i].querySelector(sel);if(e)return e;}catch(x){}}return null;}"
                    + "function __spkDeepAll(sel){var rs=__spkRoots();for(var i=0;i<rs.length;i++){"
                    + "try{var l=rs[i].querySelectorAll(sel);if(l&&l.length)return l;}catch(x){}}return null;}"
                    + "function __spkFirst(sels,vis){for(var i=0;i<sels.length;i++){"
                    + "var e=__spkDeep(sels[i]);if(e&&(!vis||__spkVis(e)))return e;}return null;}"
                    + "function __spkAll(sels){for(var i=0;i<sels.length;i++){"
                    + "var l=__spkDeepAll(sels[i]);"
                    + "if(l&&l.length){var o=[];for(var j=0;j<l.length;j++){o.push(l[j]);}return o;}}"
                    + "return [];}"
                    // 页面上有【两个】搜索框:顶部全局搜索框一旦被填入就会跳出 /chat。
                    // 只能认左侧会话列表里那一个(横向位置在页面左侧 40% 以内)。
                    + "function __spkSearchBox(sels){var w=(window.innerWidth||1000)*0.4;var fb=null;"
                    + "for(var i=0;i<sels.length;i++){var l=__spkDeepAll(sels[i]);if(!l)continue;"
                    + "for(var j=0;j<l.length;j++){var e=l[j];if(!__spkVis(e))continue;"
                    + "var r=e.getBoundingClientRect();if(r.left<w)return e;if(!fb)fb=e;}}return fb;}"
                    + "function __spkClick(e){if(!e)return false;try{e.scrollIntoView({block:'center'});}catch(x){}"
                    + "var r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;"
                    + "['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){"
                    + "try{e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,"
                    + "clientX:x,clientY:y,view:window}));}catch(x){}});return true;}";

    /** 把候选选择器数组拼成 JS 数组字面量。 */
    private static String jsSelArray(String[] sels) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sels.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jqStr(sels[i]));
        }
        return sb.append(']').toString();
    }

    private static volatile boolean PROTO_RUNNING = false;
    private static WebView webView;
    private static WindowManager wm;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // SparkBridge.state 推送
    /** 覆盖窗口的参数与屏幕监听(用于旋转后重新贴回屏外)。 */
    private static volatile WindowManager.LayoutParams overlayLp;
    private static android.hardware.display.DisplayManager displayManager;
    private static android.hardware.display.DisplayManager.DisplayListener displayListener;

    private static volatile String webState;
    private static volatile long webStateTs;
    // SparkBridge.step 回传
    private static volatile String stepResult;
    private static final Object stepLock = new Object();
    private static final Object jsLock = new Object();
    private static Context appCtxRef;

    private ProtocolService() {
    }

    // ================================================================ 入口
    public static String runOnceStandalone(Context ctx, Prefs p) {
        if (PROTO_RUNNING) {
            return "协议模式已在运行中,请勿重复触发";
        }
        PROTO_RUNNING = true;
        // ABORT 由"停止运行"按钮置位,但只有无障碍路径的 runOnce() 会复位它;
        // 协议模式以前只读不复位 —— 用户点过一次停止之后,之后每一轮都会在第一个好友前
        // 直接 break 并报告"已手动停止",等于协议模式永久失效。这里必须自己复位。
        SparkService.ABORT = false;
        synchronized (stepLock) {
            stepResult = null;
        }
        webState = null;
        Context app = ctx.getApplicationContext();
        appCtxRef = app;
        log(app, "=== 协议模式启动" + (p.testOnly() ? " (TEST_ONLY)" : "") + " ===");
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spark:proto");
        wl.acquire(10 * 60 * 1000L);
        try {
            return runOnceInner(app, p);
        } catch (Throwable t) {
            log(app, "协议模式异常: " + t);
            return "协议模式异常: " + t;
        } finally {
            try {
                wl.release();
            } catch (Exception ignored) {
            }
            shutdown(app);
            // 关键:必须复位,否则这个进程里协议模式只会成功运行一次
            // (KeepAliveService 还特意保活进程,等于永远不再发送)
            PROTO_RUNNING = false;
        }
    }

    private static String runOnceInner(Context app, Prefs p) {
        if (!ensureWebView(app)) {
            return "协议模式 WebView 初始化失败(需授予「显示在其他应用上层」权限)";
        }

        String loginState = ensureLogin(app);
        if (!"ok".equals(loginState)) {
            return "网页未登录或页面异常:请打开 App 点「网页登录」(" + loginState + ")";
        }

        List<Prefs.Friend> pending = p.pendingFriends(System.currentTimeMillis());
        if (pending.isEmpty()) {
            log(app, "1 小时窗口内均已发送,本次跳过");
            return "1 小时窗口内均已发送,本次跳过";
        }

        List<String> failed = new ArrayList<>();
        List<String> okList = new ArrayList<>();
        for (Prefs.Friend f : pending) {
            if (SparkService.ABORT) {
                log(app, "已手动停止");
                break;
            }
            boolean sent = false;
            // 协议模式最多重试 1 次(共 2 次)。网页侧"点了发送但没有可确认的证据"时,
            // 消息其实很可能已经送达 —— 之前就是因为误判失败重试 3 次,导致好友收到重复消息。
            // 宁可少一次重试(下一小时的槽位还会补发),也不要刷屏。
            int maxAttempt = Math.min(Math.max(p.retry(), 0), 1);
            for (int attempt = 0; attempt <= maxAttempt; attempt++) {
                log(app, "处理「" + f.name + "」(第 " + (attempt + 1) + "/" + (maxAttempt + 1) + " 次)");
                sent = sendToFriend(app, f.name, p.pickMessage(f), p.testOnly());
                if (sent) {
                    break;
                }
                sleep(3000);
            }
            if (SparkService.ABORT) {
                log(app, "已手动停止");
                break;
            }
            if (!p.testOnly()) {
                Prefs.markSentStatic(app, f.name, sent);
            }
            if (sent) {
                okList.add(f.name);
            } else {
                failed.add(f.name);
            }
            sleep(rnd(3000, 8000));
        }

        String summary;
        if (p.testOnly()) {
            summary = "【测试模式】未真正发送。走通: " + join(okList)
                    + (failed.isEmpty() ? "" : ";未走通: " + join(failed));
        } else if (failed.isEmpty()) {
            summary = "🎉 全部成功: " + join(okList);
        } else if (okList.isEmpty()) {
            summary = "❌ 全部失败: " + join(failed);
        } else {
            summary = "成功 " + okList.size() + " 人(" + join(okList) + ");失败 "
                    + failed.size() + " 人(" + join(failed) + ")";
        }
        log(app, summary);
        return summary;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    // ================================================================ 单好友:搜索 → 打开 → 输入 → 发送
    private static boolean sendToFriend(Context app, String name, String message, boolean testOnly) {
        // 1) 先在左侧会话列表里找(真机日志里这条路径稳定可用),找不到再走搜索
        boolean opened = openChatFromList(app, name);
        if (!opened) {
            log(app, "会话列表里没找到「" + name + "」,改用搜索");
            opened = searchConversation(app, name) && openImFromResult(app, name);
        }
        if (!opened) {
            log(app, "打不开会话: " + name);
            return false;
        }

        // 2) 等编辑器出现
        if (!waitForEditor(app, 15000)) {
            log(app, "聊天编辑器未出现");
            dumpPage(app);
            return false;
        }

        // 3) 写入消息。insertTextToEditor 内部会"全选 + 粘贴覆盖",所以不会和上次残留的草稿
        //    拼在一起。(上一版在这里先重载页面再输入,是错的:重载后页面回到会话列表,
        //    根本没有聊天编辑器,必然失败 —— 真机日志里那一串"消息写入编辑器失败"就是这么来的)
        String how = insertTextToEditor(message);
        if (how == null) {
            log(app, "消息写入编辑器失败,重载页面并重新打开会话后重试一次");
            reloadChat(app);
            if (waitReady(app, 30000) && reopenChat(app, name)) {
                how = insertTextToEditor(message);
            }
        }
        if (how == null) {
            log(app, "消息写入编辑器失败(重载重试后仍失败)");
            dumpPage(app);
            return false;
        }
        log(app, "消息已写入编辑器(方式=" + how + ")");
        sleep(rnd(600, 1400));

        if (testOnly) {
            log(app, "【TEST_ONLY】不点发送");
            return true;
        }

        // 5) 发送并校验:先点发送按钮,没生效再回车;成功判据 = 聊天记录里真的出现了这条消息
        String state = sendAndVerify(app, message);
        boolean ok = state.startsWith("sent");
        if (ok) {
            log(app, "✅ 已向「" + name + "」发送(" + state + ")");
        } else {
            log(app, "发送未确认(" + state + ")");
        }
        return ok;
    }

    // ================================================================ 搜索会话(带重试)
    /**
     * 在搜索框输入好友名并等结果面板出现。
     *
     * 两处关键改动:
     * ① 搜到的元素存进 window.__spkSearch,后续填充/清空都复用它 —— 以前"就绪检测"
     *    接受任意 input,真正填充时却只认 input.semi-input[placeholder="搜索"] 这一条,
     *    一旦改版就永远填不进去;
     * ② 等待结果改成 Java 侧轮询(每次注入一个短探针)。以前在页面里挂 5 秒的
     *    setTimeout 循环,页面只要发生 SPA 跳转,旧文档连同定时器一起消失,
     *    SparkBridge.step 永远不回调,只能干等到超时。
     */
    private static boolean searchConversation(Context app, String name) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String fill = "(function(){" + JS_LIB
                    + "var t=" + jqStr(name) + ";"
                    + "var e=window.__spkSearch;"
                    + "if(!e||!document.contains(e)){e=__spkSearchBox(" + jsSelArray(SEL_SEARCH_INPUT) + ");}"
                    + "if(!e){SparkBridge.step('nosearchbox');return;}"
                    + "window.__spkSearch=e;"
                    + "try{e.focus();}catch(x){}"
                    + "try{var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');"
                    + "d.set.call(e,'');d.set.call(e,t);}catch(x){e.value=t;}"
                    + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                    // 兜底:某些输入框不是原生 input(value 赋值不生效),用 execCommand 真插一次
                    + "if((((e.value||'')+'').length)===0){try{document.execCommand('insertText',false,t);}catch(x){}}"
                    + "var ph=(e.getAttribute?e.getAttribute('placeholder'):'')||'';"
                    + "if(ph.length>12)ph=ph.slice(0,12);"
                    + "SparkBridge.step('filled:len='+(((e.value||'')+'')).length+':ph='+ph);"
                    + "})()";
            String fr = runStep(fill, 6000);
            if (fr == null || !fr.startsWith("filled")) {
                log(app, "搜索框填充失败: " + fr + "(选择器需要按 probe/dump 校准)");
                return false;
            }
            log(app, "搜索框已填入(" + fr + ")");
            // 部分版本的搜索面板要点回车才出结果
            runStep(jsEnterOnSearchBox(), 5000);
            sleep(1200);
            String probe = "(function(){" + JS_LIB
                    + "var name=" + jqStr(name) + ";"
                    + "var sels=" + jsSelArray(SEL_RESULT_ITEM) + ";"
                    + "var counts=[],items=[];"
                    + "for(var si=0;si<sels.length;si++){var l=__spkDeepAll(sels[si]);"
                    + "counts.push(l?l.length:0);"
                    + "if(!items.length&&l&&l.length){for(var q=0;q<l.length;q++){items.push(l[q]);}}}"
                    // empty 时把每个候选选择器各命中多少个节点带回来,便于判断是"面板没出来"
                    // 还是"面板出来了但类名不对"
                    + "if(!items.length){SparkBridge.step('empty:'+counts.join('/'));return;}"
                    + "for(var i=0;i<items.length;i++){"
                    + "var subs=items[i].querySelectorAll('div,span,p');"
                    + "for(var j=0;j<subs.length;j++){"
                    + "if((subs[j].textContent||'').trim()===name&&subs[j].childElementCount===0){"
                    + "SparkBridge.step('found:'+i);return;}}}"
                    + "for(var k=0;k<items.length;k++){"
                    + "if((items[k].textContent||'').indexOf(name)>=0){SparkBridge.step('found:'+k);return;}}"
                    + "SparkBridge.step('nomatch:'+items.length);"
                    + "})()";
            long deadline = System.currentTimeMillis() + 8000;
            String last = null;
            while (System.currentTimeMillis() < deadline) {
                last = runStep(probe, 5000);
                if (last != null && last.startsWith("found")) {
                    log(app, "搜索命中(" + last + ",第 " + attempt + " 次尝试)");
                    return true;
                }
                sleep(600);
            }
            log(app, "第 " + attempt + " 次搜索未命中: " + last);
            sleep(1500);
        }
        return false;
    }

    /** 点开搜索结果里的目标:优先点「发消息/发私信」按钮,否则点整个结果项。 */
    private static boolean openImFromResult(Context app, String name) {
        String script = "(function(){" + JS_LIB
                + "var name=" + jqStr(name) + ";"
                + "var items=__spkAll(" + jsSelArray(SEL_RESULT_ITEM) + ");"
                + "var item=null;"
                + "for(var i=0;i<items.length&&!item;i++){"
                + "if((items[i].textContent||'').indexOf(name)>=0){item=items[i];}}"
                + "if(!item){SparkBridge.step('noitem');return;}"
                + "var els=item.querySelectorAll('button,div,span,a');"
                + "for(var j=0;j<els.length;j++){var t=(els[j].textContent||'').trim();"
                + "if(t==='发消息'||t==='发私信'){__spkClick(els[j]);SparkBridge.step('opened');return;}}"
                + "__spkClick(item);SparkBridge.step('clicked-item');"
                + "})()";
        String r = runStep(script, 8000);
        return "opened".equals(r) || "clicked-item".equals(r);
    }

    // ================================================================ 编辑器交互
    private static boolean waitForEditor(Context app, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String probe = "(function(){" + JS_LIB
                + "var e=__spkFirst(" + jsSelArray(SEL_EDITOR) + ",true)||__spkFirst("
                + jsSelArray(SEL_EDITOR) + ",false);"
                + "if(e){window.__spkEditor=e;}"
                + "SparkBridge.step(e?'found':'none');})()";
        while (System.currentTimeMillis() < deadline) {
            String r = runStep(probe, 5000);
            if (r != null && r.startsWith("found")) {
                return true;
            }
            sleep(800);
        }
        return false;
    }

    /** JS 片段:定位编辑器并放进 window.__spkEditor(变量名为 e)。 */
    private static String jsFindEditor() {
        return "var e=window.__spkEditor;"
                + "if(!e||!document.contains(e)){e=__spkFirst(" + jsSelArray(SEL_EDITOR)
                + ",true)||__spkFirst(" + jsSelArray(SEL_EDITOR) + ",false);}"
                + "if(e){window.__spkEditor=e;}";
    }

    /**
     * 写入消息:依次尝试 execCommand → 粘贴事件 → beforeinput/InputEvent,每次回读校验。
     * 抖音网页版聊天编辑器是 Slate(灰度里也有 Draft.js),都属于受控组件:
     * 直接改 textContent 不会更新它的内部状态,所以优先走"像真人输入"的事件路径。
     * 返回实际生效的方式;全部失败返回 null。
     */
    private static String insertTextToEditor(String message) {
        String script = "(function(){" + JS_LIB
                + "var t=" + jqStr(message) + ";"
                + jsFindEditor()
                + "if(!e){SparkBridge.step('noeditor');return;}"
                // 记录焦点状态:没有窗口焦点就没有可用 selection,受控编辑器拿不到插入点
                + "var f0=document.hasFocus()?1:0;"
                + "try{window.focus();}catch(x){}"
                + "try{e.focus();}catch(x){}"
                // 全选编辑区内容:后面的粘贴会【覆盖】选区,顺便把上次残留的草稿替换掉
                + "try{var sel=window.getSelection();var r=document.createRange();"
                + "r.selectNodeContents(e);sel.removeAllRanges();sel.addRange(r);"
                + "document.dispatchEvent(new Event('selectionchange'));}catch(x){}"
                + "var f1=document.hasFocus()?1:0;"
                + "var before=(e.innerHTML||'').length;"
                + "function got(){return ((e.textContent||e.value||'')+'').trim();}"
                + "function moved(){return got().indexOf(t)>=0&&(e.innerHTML||'').length!==before;}"
                + "function tag(k){return k+':f'+f0+f1;}"
                // 顺序是关键:Slate/Draft 都是【受控编辑器】,只有它们自己的 onPaste / onBeforeInput
                // 才会把文本写进内部状态。直接 execCommand 只会改 DOM,编辑器内部仍认为自己是空的,
                // 于是回车无效、发送按钮保持 disabled、编辑器永远不清空(真机日志正是这个表现)。
                + "try{var dt=new DataTransfer();dt.setData('text/plain',t);"
                + "e.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));}"
                + "catch(x){}"
                + "if(moved()){SparkBridge.step(tag('typed:paste'));return;}"
                + "try{e.dispatchEvent(new InputEvent('beforeinput',"
                + "{inputType:'insertText',data:t,bubbles:true,cancelable:true}));}catch(x){}"
                + "try{e.dispatchEvent(new InputEvent('input',{inputType:'insertText',data:t,bubbles:true}));}"
                + "catch(x){}"
                + "if(moved()){SparkBridge.step(tag('typed:beforeinput'));return;}"
                + "try{document.execCommand('insertText',false,t);}catch(x){}"
                + "if(moved()){SparkBridge.step(tag('typed:execCommand'));return;}"
                + "if(!e.isContentEditable){try{e.value=t;}catch(x){}}"
                + "SparkBridge.step(got().indexOf(t)>=0?tag('typed:dom'):tag('writefail'));"
                + "})()";
        String r = runStep(script, 10000);
        if (r != null && r.startsWith("typed:")) {
            return r.substring(6);
        }
        return null;
    }

    /**
     * 发送并校验。
     * ① 先点发送按钮(真机实测这条路径确实把消息发出去了;先按回车的话 Slate 只会插入一个换行);
     * ② 没生效再按回车;
     * ③ 判据是"发送前后指纹对比"—— 聊天记录里含这条文本的气泡变多,或该文本第一次出现在正文里。
     *    绝不看"编辑器是否清空":这个版本发送成功后编辑器里仍留有残渣,用它判会误报失败并触发重复发送。
     */
    private static String sendAndVerify(Context app, String message) {
        // 真机结论:消息其实【发出去了】,但这个版本的编辑器发送后仍留着一点内容
        // (实测 textContent 剩 1 个字符,trim 之后还在),所以"编辑器是否清空"根本不能当判据;
        // 而 [data-e2e=msg-item-content] 这类气泡类名在该版本也不匹配。
        // 现在改成"发送前后对比"的指纹判定:看聊天记录里【含这条文本的气泡】有没有变多,
        // 或这段文本是不是第一次出现在正文里(排除编辑器自身)。
        int[] before = parseFp(runStep(jsFingerprint(message), 6000));
        String btn = runStep(jsSendButtonState(), 6000);
        log(app, "发送按钮:" + btn + " | 发送前指纹(bubbles/match/bodyHas)=" + fmtFp(before));
        if (btn != null && btn.startsWith("disabled")) {
            log(app, "⚠️ 发送按钮处于禁用状态:文本可能没进编辑器内部状态");
        }

        // ① 先回车:第三次真机日志确认这条路径有效(点击那个按钮容器反而没反应),
        //    开源实现(douyin-auto-fire / douyin-auto-spark)也都是以回车为主路径
        runStep(jsEnterOnEditor(), 6000);
        if (waitSent(app, message, before, 8000)) {
            return "sent(回车后聊天记录出现该消息)";
        }

        // ② 回车没生效再点发送按钮
        log(app, "回车后未见新消息,改点发送按钮");
        log(app, "点击发送按钮: " + runStep(jsClickSendButton(), 6000));
        if (waitSent(app, message, before, 8000)) {
            return "sent(点按钮后聊天记录出现该消息)";
        }

        // ③ 都没证据:看看是不是出现了重试/发送失败标记
        String verdict = runStep(jsSendVerdict(message), 6000);
        if ("failed".equals(verdict)) {
            return "failed(出现重试/发送失败标记)";
        }
        return "unknown(指纹=" + fmtFp(parseFp(runStep(jsFingerprint(message), 6000)))
                + ",编辑器=" + verdict + ")";
    }

    /** 发送前后指纹:气泡总数 / 含目标文本的气泡数 / 正文里是否已出现该文本(排除编辑器)。 */
    private static String jsFingerprint(final String message) {
        return "(function(){" + JS_LIB
                + "var t=" + jqStr(message) + ";"
                + "var head=t.length>8?t.substring(0,8):t;"
                + "var bub=__spkAll(" + jsSelArray(SEL_MSG_BUBBLE) + ");"
                + "var m=0;"
                + "for(var i=0;i<bub.length;i++){"
                + "var s=((bub[i].textContent||'')+'').trim();"
                + "if(head&&s&&s.indexOf(head)>=0)m++;}"
                + "var b='';try{b=document.body?document.body.textContent:'';}catch(x){}"
                + "var ed=window.__spkEditor;"
                + "if(ed&&ed.textContent){try{b=b.split(ed.textContent+'').join('');}catch(x){}}"
                + "var has=(head&&b.indexOf(head)>=0)?1:0;"
                + "SparkBridge.step('fp:'+bub.length+':'+m+':'+has);"
                + "})()";
    }

    private static int[] parseFp(String fp) {
        if (fp == null || !fp.startsWith("fp:")) {
            return null;
        }
        String[] p = fp.split(":");
        if (p.length < 4) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmtFp(int[] fp) {
        return fp == null ? "?" : (fp[0] + "/" + fp[1] + "/" + fp[2]);
    }

    /** 指纹是否证明"发出去了":含该文本的气泡变多,或该文本第一次出现在正文里。 */
    private static boolean fpImproved(int[] before, int[] after) {
        if (after == null) {
            return false;
        }
        if (before == null) {
            return after[1] > 0 || after[2] == 1;
        }
        return after[1] > before[1] || (after[2] == 1 && before[2] == 0);
    }

    /** 轮询指纹,直到出现"确实发出去了"的证据。 */
    private static boolean waitSent(Context app, String message, int[] before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int[] now = parseFp(runStep(jsFingerprint(message), 6000));
            if (fpImproved(before, now)) {
                log(app, "指纹变化确认发送:" + fmtFp(before) + " -> " + fmtFp(now));
                return true;
            }
            sleep(1200);
        }
        return false;
    }

    /**
     * JS 片段:定位发送按钮(变量名 b)。
     * 真机日志里命中过 svg —— 里面那层图标没有 disabled、点了也不算按钮点击,
     * 所以这里统一往上找真正的 button/[role=button] 再操作。
     */
    private static String jsSendButtonExpr() {
        return "var ed=window.__spkEditor;"
                + "if(!ed||!document.contains(ed)){ed=__spkFirst(" + jsSelArray(SEL_EDITOR)
                + ",true)||__spkFirst(" + jsSelArray(SEL_EDITOR) + ",false);}"
                + "var b=__spkFirst(" + jsSelArray(SEL_SEND_BUTTON) + ",true);"
                + "if(!b&&ed){var p=ed.parentElement;"
                + "for(var up=0;up<5&&p&&!b;up++){"
                + "var c=p.querySelectorAll('button,span,div');"
                + "for(var j=0;j<c.length;j++){"
                + "if(((c[j].textContent||'').trim())==='发送'){b=c[j];break;}}"
                + "p=p.parentElement;}}"
                // 只认真正的按钮;命中的若是按钮内部的 svg/图标,退一层看它的容器
                + "if(b&&b.tagName){var tn=(b.tagName+'').toLowerCase();"
                + "if(tn==='svg'||tn==='path'||tn==='use'){b=b.parentElement||b;}}"
                + "if(b&&b.closest){try{b=b.closest('button,[role=button]')||b;}catch(x){}}";
    }

    /** 点击发送按钮(部分版本 Enter 只换行)。 */
    private static String jsClickSendButton() {
        return "(function(){" + JS_LIB + jsSendButtonExpr()
                + "if(!b){SparkBridge.step('nobtn');return;}"
                + "__spkClick(b);SparkBridge.step('clicked');"
                + "})()";
    }

    /** 发送按钮状态:ok:标签.类名 / disabled:标签.类名 / none。 */
    private static String jsSendButtonState() {
        return "(function(){" + JS_LIB + jsSendButtonExpr()
                + "if(!b){SparkBridge.step('none');return;}"
                + "var cls=((b.className||'')+'');if(cls.length>40)cls=cls.substring(0,40);"
                + "var dis=(b.disabled===true)"
                + "||((b.getAttribute&&b.getAttribute('aria-disabled')==='true')||false)"
                + "||(cls.indexOf('disabled')>=0);"
                + "SparkBridge.step((dis?'disabled:':'ok:')+(b.tagName||'?')+'.'+cls);"
                + "})()";
    }

    /** 在搜索框上补一次回车:部分版本的搜索结果面板要点回车才出现。 */
    private static String jsEnterOnSearchBox() {
        return "(function(){" + JS_LIB
                + "var e=window.__spkSearch;"
                + "if(!e||!document.contains(e)){e=__spkSearchBox(" + jsSelArray(SEL_SEARCH_INPUT) + ");}"
                + "if(!e){SparkBridge.step('nosearchbox');return;}"
                + "var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
                + "try{e.dispatchEvent(new KeyboardEvent('keydown',o));}catch(x){}"
                + "try{e.dispatchEvent(new KeyboardEvent('keypress',o));}catch(x){}"
                + "try{e.dispatchEvent(new KeyboardEvent('keyup',o));}catch(x){}"
                + "SparkBridge.step('enter');"
                + "})()";
    }

    /** 在会话列表里按标题找目标(配合滚动逐轮查找)。 */
    private static String jsFindInList(String name) {
        return "(function(){" + JS_LIB
                + "var name=" + jqStr(name) + ";"
                + "var titles=__spkAll(" + jsSelArray(SEL_CONVERSATION_TITLE) + ");"
                + "var n=titles.length;"
                + "if(!n){titles=__spkAll(" + jsSelArray(SEL_CONVERSATION) + ");n=-titles.length;}"
                + "var hit=null;"
                + "for(var i=0;i<titles.length&&!hit;i++){"
                + "if(((titles[i].textContent||'').trim())===name){hit=titles[i];}}"
                + "if(!hit){for(var j=0;j<titles.length&&!hit;j++){"
                + "if(((titles[j].textContent||'').indexOf(name)>=0)){hit=titles[j];}}}"
                + "var firstTitle=(titles.length&&titles[0].textContent)?(titles[0].textContent+'').trim().slice(0,10):'';"
                + "if(!hit){SparkBridge.step('nolist:'+n+':'+firstTitle);return;}"
                + "__spkClick(hit);SparkBridge.step('clicked');"
                + "})()";
    }

    /** 会话列表向下滚动一段(React 虚拟列表监听 scroll 事件,直接改 scrollTop 也能触发)。 */
    private static String jsScrollList() {
        return "(function(){" + JS_LIB
                + "var c=__spkFirst(['[class*=conversationConversationList]','[class*=ConversationList]',"
                + "'[class*=messageMessageListlist]','[class*=conversation]'],false);"
                + "if(!c){SparkBridge.step('nolist');return;}"
                + "try{c.scrollTop=c.scrollTop+400;}catch(x){}"
                + "SparkBridge.step('scrolled');"
                + "})()";
    }

    /** 重新加载聊天页(用于清掉编辑器里残留的文本/状态)。 */
    private static void reloadChat(final Context app) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.loadUrl(CHAT_URL);
                }
            }
        });
        sleep(2000);
    }

    /** 等页面重新就绪(复用页面状态探针)。 */
    private static boolean waitReady(Context app, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String s = runStep(jsPageState(), 6000);
            if (s != null && s.startsWith("ready")) {
                sleep(2500);
                return true;
            }
            sleep(1200);
        }
        return false;
    }

    /** 发送结果判定:failed / bubble / empty / notclear:N(编辑器里还剩几个字)。 */
    private static String jsSendVerdict(final String message) {
        return "(function(){" + JS_LIB
                + "var t=" + jqStr(message) + ";"
                + "var f=__spkFirst(" + jsSelArray(SEL_SEND_FAILED) + ",false);"
                + "if(f){SparkBridge.step('failed');return;}"
                + "try{var body=document.body?document.body.textContent:'';"
                + "if(body.indexOf('发送失败')>=0){SparkBridge.step('failed');return;}}catch(x){}"
                + "var bub=__spkAll(" + jsSelArray(SEL_MSG_BUBBLE) + ");"
                + "var head=t.length>8?t.substring(0,8):t;"
                + "for(var i=bub.length-1;i>=0&&i>=bub.length-8;i--){"
                + "var s=(bub[i].textContent||'').trim();"
                + "if(head&&s&&s.indexOf(head)>=0){SparkBridge.step('bubble');return;}}"
                + "var ed=window.__spkEditor;"
                + "if(!ed||!document.contains(ed)){ed=__spkFirst(" + jsSelArray(SEL_EDITOR) + ",false);}"
                + "var v=ed?((ed.textContent||ed.value||'')+'').trim():'';"
                // 不要用全局 .semi-spin 判断"发送中":抖音全站都在用 semi 组件,
                // 页面上任何一个转圈都会命中,会把"其实没发出去"误报成还在发送
                + "SparkBridge.step(v.length===0?'empty':('notclear:'+v.length));"
                + "})()";
    }

    /** 重新打开会话(页面重载后用):列表优先、搜索兜底,最后等编辑器出现。 */
    private static boolean reopenChat(Context app, String name) {
        boolean ok = openChatFromList(app, name);
        if (!ok) {
            ok = searchConversation(app, name) && openImFromResult(app, name);
        }
        return ok && waitForEditor(app, 15000);
    }

    /** 在左侧会话列表里按标题找目标;找不到就向下滚动逐轮再找(列表滚不动就提前收手)。 */
    private static boolean openChatFromList(Context app, String name) {
        String lastFirst = null;
        for (int round = 0; round <= 4; round++) {
            String r = runStep(jsFindInList(name), 8000);
            if ("clicked".equals(r)) {
                log(app, "会话列表中找到「" + name + "」" + (round > 0 ? "(滚动第 " + round + " 轮)" : ""));
                return true;
            }
            String first = (r != null && r.startsWith("nolist:")) ? r : "";
            if (round > 0 && first.equals(lastFirst)) {
                log(app, "会话列表已滚动到底,停止查找: " + (r == null ? "timeout" : r));
                return false;
            }
            lastFirst = first;
            if (round < 4) {
                runStep(jsScrollList(), 6000);
                sleep(1200);
            }
        }
        log(app, "会话列表滚动查找未命中: " + name);
        return false;
    }

    private static String jsEditorCleared() {
        return "(function(){" + JS_LIB + jsFindEditor()
                + "if(!e){SparkBridge.step('noeditor');return;}"
                + "var v=((e.textContent||e.value||'')+'');"
                + "SparkBridge.step(v.trim().length===0?'cleared':'notclear');"
                + "})()";
    }

    private static String jsEnterOnEditor() {
        return "(function(){" + JS_LIB + jsFindEditor()
                + "if(!e){SparkBridge.step('noeditor');return;}"
                + "try{e.focus();}catch(x){}"
                + "var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
                + "try{e.dispatchEvent(new KeyboardEvent('keydown',o));}catch(x){}"
                + "try{e.dispatchEvent(new KeyboardEvent('keypress',o));}catch(x){}"
                + "try{e.dispatchEvent(new KeyboardEvent('keyup',o));}catch(x){}"
                + "SparkBridge.step('enter-ok');"
                + "})()";
    }

    /** 轮询等待选择器出现(Java 侧重试 + 短探针,不依赖页面内的长定时器)。 */
    private static boolean waitForSelector(String sel, long timeoutMs) {
        String probe = "(function(){" + JS_LIB
                + "var e=null;try{e=document.querySelector(" + jqStr(sel) + ");}catch(x){e=null;}"
                + "SparkBridge.step(e?'found':'none');})()";
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String r = runStep(probe, 5000);
            if (r != null && r.startsWith("found")) {
                return true;
            }
            sleep(700);
        }
        return false;
    }

    // ================================================================ 登录检测
    private static String ensureLogin(final Context app) {
        String ck = CookieManager.getInstance().getCookie("https://www.douyin.com");
        boolean hasSid = ck != null && ck.contains("sessionid=");
        log(app, "Cookie 检查: sessionid " + (hasSid ? "存在" : "缺失"));
        if (!hasSid) {
            return "need_login";
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.loadUrl(CHAT_URL);
                }
            }
        });
        // Java 侧轮询页面状态:每轮注入一个"看一眼就走"的短探针。
        // 以前是在页面里挂一个 25 秒的 setTimeout 轮询 —— 只要页面发生 SPA 跳转或重载,
        // 旧文档连同定时器一起被丢弃,SparkBridge.step 就再也不会回调,
        // "等搜索框"必然走到超时,协议模式直接失败。
        long deadline = System.currentTimeMillis() + 60000;
        boolean triedAlt = false;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            String s = runStep(jsPageState(), 6000);
            if (s != null && s.startsWith("ready")) {
                log(app, "聊天页就绪(" + s + ")");
                sleep(3000); // 等头像/最近会话拉取完,提高搜索命中率
                return "ok";
            }
            if (s != null && s.startsWith("login")) {
                log(app, "Cookie 失效,需要重新网页登录");
                return "need_login";
            }
            // 30 秒还没就绪:换一次备用路由(不同灰度下私信页可能是 /chat 或 /messages)
            if (!triedAlt && System.currentTimeMillis() > deadline - 30000) {
                triedAlt = true;
                log(app, "聊天页 30 秒仍未就绪(" + s + "),改试备用地址 " + CHAT_URL_ALT);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.loadUrl(CHAT_URL_ALT);
                        }
                    }
                });
            }
            last = s;
            sleep(1200);
        }
        log(app, "聊天页加载超时,最后状态: " + last + "(结构见 dump 日志)");
        dumpPage(app);
        return "page_timeout";
    }

    /** 单次页面状态探针:ready:searchbox|editor|list / login / loading:... */
    private static String jsPageState() {
        return "(function(){" + JS_LIB
                + "try{var b=document.body?document.body.textContent:'';"
                + "if(b.indexOf('扫码登录')>=0||b.indexOf('验证码登录')>=0){SparkBridge.step('login');return;}"
                + "var s=__spkSearchBox(" + jsSelArray(SEL_SEARCH_INPUT) + ");"
                + "if(s){SparkBridge.step('ready:searchbox:focus='+(document.hasFocus()?1:0));return;}"
                + "var ed=__spkFirst(" + jsSelArray(SEL_EDITOR) + ",true);"
                + "if(ed){SparkBridge.step('ready:editor');return;}"
                + "var c=__spkFirst(" + jsSelArray(SEL_CONVERSATION) + ",true);"
                + "if(c){SparkBridge.step('ready:list');return;}"
                + "SparkBridge.step('loading:'+(document.readyState||'?')+':'+(b?b.length:0)"
                + "+':focus='+(document.hasFocus()?1:0));"
                + "}catch(x){SparkBridge.step('err:'+x);}})()";
    }

    /** 失败诊断:回传页面结构与输入控件清单。 */
    private static void dumpPage(Context app) {
        String script = "(function(){try{"
                + "var out='title='+document.title;"
                + "var ins=document.querySelectorAll('input');"
                + "out+=' inputs='+ins.length;"
                + "for(var i=0;i<Math.min(ins.length,5);i++){"
                + "var e=ins[i];out+=' I'+i+':'+(e.type||'?')+' ph='+((e.getAttribute('placeholder')||'')+'').substring(0,20);}"
                + "SparkBridge.log(out.substring(0,400));"
                + "var h=(document.body?document.body.innerHTML:'');"
                + "var step=700;"
                + "for(var i=0;i<Math.min(h.length,3500);i+=step){"
                + "SparkBridge.log('BHTML['+i+']: '+h.substring(i,i+step));}"
                + "}catch(e){SparkBridge.log('dump-err:'+e);}})()";
        fireJs(script);
    }
    /** fire-and-forget 执行 JS(不需要结果)。 */
    private static void fireJs(final String script) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        webView.evaluateJavascript(script, null);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    // ================================================================ WebView(主线程)
    private static synchronized boolean ensureWebView(final Context ctx) {
        if (webView != null) {
            return true;
        }
        final boolean[] ok = {false};
        final Object latch = new Object();
        MAIN.post(new Runnable() {
            @SuppressLint("SetJavaScriptEnabled")
            @Override
            public void run() {
                try {
                    CookieManager.getInstance().setAcceptCookie(true);
                    webView = new WebView(ctx);
                    // 登录页(ProtocolLoginActivity)开了第三方 Cookie,离屏 WebView 也必须开:
                    // 抖音网页版的登录态/安全 SDK 会用到 iframe,不统一会导致"登录了但页面认为没登录"
                    try {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
                    } catch (Exception ignored) {
                    }
                    WebSettings s = webView.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setJavaScriptCanOpenWindowsAutomatically(true);
                    s.setUserAgentString(DESKTOP_UA);
                    s.setBlockNetworkImage(true);
                    s.setLoadsImagesAutomatically(false);
                    s.setCacheMode(WebSettings.LOAD_DEFAULT);
                    webView.setBackgroundColor(Color.TRANSPARENT);
                    webView.addJavascriptInterface(new JsBridge(ctx), "SparkBridge");
                    webView.setWebViewClient(new android.webkit.WebViewClient() {
                        @Override
                        public void onPageFinished(WebView v, String url) {
                            CookieManager.getInstance().flush();
                            log(ctx, "页面加载完成: " + url);
                            injectMonitor(v);
                        }
                    });
                    wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    try {
                        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                        int sw = dm.widthPixels, sh = dm.heightPixels;
                        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                                sw, sh,
                                Build.VERSION.SDK_INT >= 26
                                        ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                        : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                                // 注意:这里【不能】带 FLAG_NOT_FOCUSABLE。
                                // 带上的话页面 document.hasFocus() 恒为 false,浏览器就不会给它可用的
                                // selection,Slate 这类受控编辑器拿不到插入点 —— 表现就是"文字能塞进 DOM,
                                // 但编辑器内部状态一直是空的",于是回车无效、发送按钮保持 disabled。
                                // 仍然保留 FLAG_NOT_TOUCHABLE:不抢触摸,不会影响用户操作。
                                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.y = 0;
                        // 离屏位移必须按【真实屏幕】算:以前用的是本应用窗口宽度(sw),
                        // 横屏/分屏时真实屏幕宽得多,窗口就会露在屏幕左/右边缘 ——
                        // 用户反馈的"打开横屏应用时屏幕左/右出现抖音画面"就是这个原因。
                        // 这里按真实屏幕长边 ×2 + 240px 留余量,旋转之后依然在屏幕外。
                        lp.x = -offscreenOffset(ctx, wm);
                        // 不弹出输入法(我们靠事件注入文本,不需要键盘)
                        lp.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
                        overlayLp = lp;
                        wm.addView(webView, lp);
                        // 旋转/分屏切换后系统会重新摆放覆盖窗口,这里跟着重新贴回屏外
                        registerDisplayListener(ctx);
                        try {
                            webView.setFocusable(true);
                            webView.setFocusableInTouchMode(true);
                            webView.requestFocus();
                        } catch (Exception ignored) {
                        }
                    } catch (Exception e) {
                        log(ctx, "悬浮附着失败(将离屏运行): " + e);
                    }
                    CookieManager.getInstance().flush();
                    ok[0] = true;
                } catch (Throwable t) {
                    log(ctx, "WebView 初始化失败: " + t);
                }
                synchronized (latch) {
                    latch.notifyAll();
                }
            }
        });
        synchronized (latch) {
            long deadline = System.currentTimeMillis() + 20000;
            while (!ok[0] && System.currentTimeMillis() < deadline) {
                try {
                    latch.wait(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return ok[0];
    }

    private static class JsBridge {
        private final Context ctx;

        JsBridge(Context c) {
            ctx = c;
        }

        @JavascriptInterface
        public void log(String s) {
            Prefs.appendLog(ctx, now() + " [web] " + s);
        }

        /** 页面状态上报(登录检测)。 */
        @JavascriptInterface
        public void state(String s) {
            synchronized (jsLock) {
                webState = s;
                webStateTs = System.currentTimeMillis();
                jsLock.notifyAll();
            }
        }

        /** 自动化步骤结果回传。 */
        @JavascriptInterface
        public void step(String s) {
            synchronized (stepLock) {
                stepResult = s;
                stepLock.notifyAll();
            }
        }
    }

    // ================================================================ JS 注入
    /** 页面加载完成后注入:常驻状态上报(每 3 秒 SparkBridge.state)。 */
    private static void injectMonitor(final WebView v) {
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    v.evaluateJavascript(
                            "(function(){if(window.__sparkInj)return;window.__sparkInj=1;"
                                    + "setInterval(function(){try{var b=document.body;"
                                    + "SparkBridge.state((document.readyState||'')+'|'+(b?b.innerText.length:0)"
                                    + "+'|'+((b&&b.textContent.indexOf('扫码登录')>=0)?'1':'0')+'|'+document.querySelectorAll('input').length+'|'+(document.querySelector('input[placeholder*=搜索]')?'1':'0')+'|'+location.href);"
                                    + "}catch(e){}},3000);})();",
                            null);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 执行异步动作脚本,结果由页面通过 SparkBridge.step 回传(不依赖 evaluate 返回值)。 */
    private static String runStep(final String script, final long timeoutMs) {
        synchronized (stepLock) {
            stepResult = null;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        webView.evaluateJavascript(script, null);
                    } catch (Exception e) {
                        synchronized (stepLock) {
                            stepResult = "jsthrow:" + e;
                            stepLock.notifyAll();
                        }
                    }
                }
            });
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (stepResult == null && System.currentTimeMillis() < deadline) {
                try {
                    stepLock.wait(500);
                } catch (InterruptedException ignored) {
                }
            }
            return stepResult == null ? "timeout" : stepResult;
        }
    }

    /**
     * 覆盖窗口的离屏位移(像素)。
     * 必须用 getRealMetrics(真实屏幕),不能用 getResources().getDisplayMetrics()
     * —— 后者在分屏/自由窗口下返回的是"本应用窗口"尺寸,横屏时能差出一倍以上。
     */
    private static int offscreenOffset(Context ctx, WindowManager wm) {
        int w = 0, h = 0;
        try {
            android.util.DisplayMetrics real = new android.util.DisplayMetrics();
            android.view.Display d = (wm != null) ? wm.getDefaultDisplay() : null;
            if (d != null) {
                d.getRealMetrics(real);
                w = real.widthPixels;
                h = real.heightPixels;
            }
        } catch (Exception ignored) {
        }
        if (w <= 0 || h <= 0) {
            try {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = dm.heightPixels;
            } catch (Exception ignored) {
            }
        }
        int longSide = Math.max(Math.max(w, h), 1080);
        return longSide * 2 + 240;
    }

    /** 注册屏幕变化监听:旋转/分屏切换后重新把窗口挪到屏外。 */
    private static void registerDisplayListener(final Context ctx) {
        try {
            if (displayListener != null) {
                return;
            }
            final android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    reassertOffscreen(ctx);
                }
            };
            dm.registerDisplayListener(displayListener, MAIN);
            displayManager = dm;
        } catch (Exception ignored) {
        }
    }

    /** 重新把覆盖窗口贴到屏幕外(屏幕方向/尺寸变化后调用)。 */
    private static void reassertOffscreen(final Context ctx) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView != null && wm != null && overlayLp != null) {
                        int x = -offscreenOffset(ctx, wm);
                        if (overlayLp.x != x) {
                            overlayLp.x = x;
                            overlayLp.y = 0;
                            wm.updateViewLayout(webView, overlayLp);
                            log(ctx, "屏幕方向/尺寸变化:已把离屏窗口重新移出屏幕(x=" + x + ")");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static void unregisterDisplayListener() {
        try {
            if (displayManager != null && displayListener != null) {
                displayManager.unregisterDisplayListener(displayListener);
            }
        } catch (Exception ignored) {
        }
        displayManager = null;
        displayListener = null;
    }

    static void shutdown(Context ctx) {
        synchronized (stepLock) {
            stepResult = null;
        }
        webState = null;
        unregisterDisplayListener();
        overlayLp = null;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                // removeView 与 destroy 必须分开兜底:wm.addView 失败过的场景
                // (日志里的"悬浮附着失败")会让 removeView 抛异常,
                // 以前两句在同一个 try 里,destroy/null 会被一起跳过,WebView 与 Context 泄漏
                WebView v = webView;
                webView = null;
                if (v == null) {
                    return;
                }
                try {
                    if (wm != null) {
                        wm.removeView(v);
                    }
                } catch (Exception ignored) {
                }
                try {
                    v.destroy();
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ================================================================ 工具
    private static String jqStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    static void log(Context c, String msg) {
        Prefs.appendLog(c, now() + " " + msg);
    }

    private static String now() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static int rnd(int lo, int hi) {
        return lo + new java.util.Random().nextInt(Math.max(1, hi - lo));
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}

