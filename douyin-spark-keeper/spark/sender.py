"""核心发送逻辑:定位会话 -> 注入消息 -> 发送 -> 验证。

抖音网页版私信界面的 DOM 因账号灰度(A/B 实验)存在多版本结构,
因此所有定位都采用"多选择器降级链",并附带 probe 探查模式用于校准。
"""
from __future__ import annotations

import json
import logging
import random
import time
from datetime import datetime
from pathlib import Path
from typing import List, Optional

from playwright.sync_api import Locator, Page, sync_playwright

from .browser import NotLoggedInError, first_visible, login_panel_visible, open_context, take_page
from .config import AppConfig, Target
from .state import mark_sent, sent_today

log = logging.getLogger("spark")

WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

# ---------------------------------------------------------------- 选择器降级链
# 会话列表条目(左侧列表中的每一项)
SESSION_SELECTORS = (
    '[data-e2e="conversation-item"]',
    '[class*="messageMessageListlist"] [data-index]',
    "div[data-index]",
)

# 聊天输入框(Draft.js contenteditable)
INPUT_SELECTORS = (
    '[class*="chatInput"] [contenteditable="true"]',
    '[class*="ChatInput"] [contenteditable="true"]',
    '[contenteditable="true"]',
)

# 发送按钮(两套命名体系)
SEND_SELECTORS = (
    '[class*="e2e-send-msg-btn"]',
    'button:has-text("发送")',
    'span:has-text("发送")',
    'div[class*="messageMsgInputinputAction"] > *:nth-child(3)',
)

# 聊天记录里的单条消息
MSG_ITEM_SELECTOR = '[data-e2e="msg-item-content"]'


class TargetNotFoundError(RuntimeError):
    pass


class SendError(RuntimeError):
    pass


# ---------------------------------------------------------------- 页面打开与登录检测
def open_chat(page: Page, cfg: AppConfig, timeout_s: int = 90) -> None:
    """打开聊天页并等待就绪。未登录抛 NotLoggedInError。"""
    log.info("打开聊天页 %s", cfg.chat_url)
    page.goto(cfg.chat_url, wait_until="domcontentloaded")
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if login_panel_visible(page):
            raise NotLoggedInError("抖音未登录:请先执行 `python -m spark login` 扫码登录")
        if find_inputs(page) or session_items(page):
            log.info("聊天页已就绪")
            return
        time.sleep(1)
    raise TimeoutError("聊天页加载超时(会话列表与输入框均未出现)")


# ---------------------------------------------------------------- 定位:会话列表
def session_items(page: Page) -> List[Locator]:
    for sel in SESSION_SELECTORS:
        items = first_visible(page.locator(sel))
        if items:
            return items
    return []


def _scroll_session_list(page: Page, items: List[Locator]) -> None:
    """在会话列表容器内滚动,寻找更多会话。"""
    try:
        box = items[0].bounding_box()
        if not box:
            return
        cx, cy = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2
        page.mouse.move(cx, cy)
        page.mouse.wheel(0, 600)
    except Exception:  # noqa: BLE001
        pass


def find_target(page: Page, name: str, scroll_rounds: int = 4) -> Optional[Locator]:
    """在会话列表中定位会话条目:先按首行精确匹配,再按子串匹配。"""
    for attempt in range(scroll_rounds + 1):
        items = session_items(page)
        for el in items:
            try:
                text = el.inner_text(timeout=1500)
            except Exception:  # noqa: BLE001
                continue
            first_line = text.split("\n")[0].strip()
            if first_line == name:
                return el
        for el in items:
            try:
                text = el.inner_text(timeout=1500)
            except Exception:  # noqa: BLE001
                continue
            if name in text:
                return el
        if attempt < scroll_rounds and items:
            _scroll_session_list(page, items)
            time.sleep(1.5)
    return None


# ---------------------------------------------------------------- 定位:输入框与发送按钮
def find_inputs(page: Page) -> List[Locator]:
    """定位聊天输入框。多个候选时按 x+y 坐标取最靠右下的(Draft.js 输入框一般在右下角)。"""
    for sel in INPUT_SELECTORS:
        cands = first_visible(page.locator(sel))
        if cands:
            if len(cands) > 1:
                def score(el: Locator) -> float:
                    try:
                        b = el.bounding_box() or {}
                        return b.get("x", 0) + b.get("y", 0)
                    except Exception:  # noqa: BLE001
                        return 0.0

                cands.sort(key=score, reverse=True)
            return cands
    return []


def find_send_button(page: Page) -> Optional[Locator]:
    for sel in SEND_SELECTORS:
        cands = first_visible(page.locator(sel), limit=10)
        if cands:
            return cands[0]
    return None


# ---------------------------------------------------------------- 输入与发送
def _inject_paste(el: Locator, text: str) -> None:
    """通过 ClipboardEvent('paste') 向 Draft.js 输入框注入文本。

    抖音聊天输入框基于 Draft.js,常规 fill()/type() 不触发其内部状态更新,
    必须模拟粘贴事件。
    """
    el.evaluate(
        """(node, t) => {
            node.focus();
            const dt = new DataTransfer();
            dt.setData('text/plain', t);
            const ev = new ClipboardEvent('paste', {
                clipboardData: dt,
                bubbles: true,
                cancelable: true,
            });
            node.dispatchEvent(ev);
        }""",
        text,
    )


def _clear_input(page: Page, el: Locator) -> None:
    """清空输入框(重试前清理上次残留,避免消息重复拼接)。"""
    try:
        el.click()
        page.keyboard.press("Control+a")
        page.keyboard.press("Delete")
        time.sleep(0.3)
    except Exception:  # noqa: BLE001
        pass


def _input_text(page: Page, el: Locator, text: str) -> bool:
    """向输入框写入文本,返回是否成功(以输入框内容非空为准)。"""
    strategies = (
        ("paste", lambda: _inject_paste(el, text)),
        ("insertText", lambda: (el.click(), page.keyboard.insert_text(text))),
        ("type", lambda: (el.click(), el.type(text, delay=random.randint(60, 140)))),
    )
    for name, fn in strategies:
        try:
            _clear_input(page, el)
            fn()
            time.sleep(0.8)
            content = (el.inner_text() or "").strip()
            if content:
                log.info("消息已输入 (方式=%s, %d 字)", name, len(content))
                return True
        except Exception as e:  # noqa: BLE001
            log.debug("输入方式 %s 失败: %s", name, e)
    return False


def _last_messages(page: Page, k: int = 5) -> List[str]:
    out: List[str] = []
    try:
        loc = page.locator(MSG_ITEM_SELECTOR)
        n = loc.count()
    except Exception:  # noqa: BLE001
        return out
    for i in range(max(0, n - k), n):
        try:
            out.append(loc.nth(i).inner_text(timeout=1500))
        except Exception:  # noqa: BLE001
            continue
    return out


def _verify_sent(page: Page, el: Locator, snippet: str) -> bool:
    """验证发送成功:聊天记录末尾出现刚发的内容,或输入框被清空。

    顺序很重要:先看"消息气泡真的出现了"这个强证据。以前先判断输入框是否为空,
    输入框一旦从页面上消失(改版/页面被关掉)就会落到 "snippet in joined",
    而 snippet 是空串时 "" in 任何字符串 恒为 True —— 没发出去也会被算成成功。
    """
    joined = "".join(_last_messages(page))
    if snippet and snippet in joined:
        return True
    try:
        return not (el.inner_text() or "").strip()
    except Exception:  # noqa: BLE001
        # 输入框读不到时不能再默认成功,交给上层重试
        return False


def send_to_one(page: Page, cfg: AppConfig, target: Target, message: str) -> None:
    """对单个好友完成一次发送。失败抛 SendError / TargetNotFoundError。"""
    name = target.name
    log.info("定位会话: %s", name)
    item = find_target(page, name)
    if item is None:
        raise TargetNotFoundError(f"会话列表中找不到「{name}」(确认名字与页面显示一致,且对方在你的私信列表中)")

    item.click()
    time.sleep(1.5)

    snippet = message[:10]
    last_err: Exception | None = None
    for attempt in range(cfg.retry + 1):
        inputs = find_inputs(page)
        if not inputs:
            time.sleep(2)
            inputs = find_inputs(page)
        if not inputs:
            last_err = SendError("找不到聊天输入框(可执行 python -m spark probe 采集页面结构)")
        else:
            box = inputs[0]
            try:
                box.click()
            except Exception:  # noqa: BLE001
                pass
            if not _input_text(page, box, message):
                last_err = SendError("无法向输入框写入文本")
            else:
                time.sleep(random.uniform(0.6, 1.4))
                btn = find_send_button(page)
                try:
                    if btn is not None:
                        btn.click()
                        log.info("已点击发送按钮")
                    else:
                        page.keyboard.press("Enter")
                        log.info("未找到发送按钮,已按 Enter 发送")
                except Exception as e:  # noqa: BLE001
                    last_err = SendError(f"点击发送失败: {e}")

                time.sleep(2)
                if _verify_sent(page, box, snippet):
                    log.info("✅ 已向「%s」发送: %s", name, message)
                    return
                last_err = SendError("发送后未验证到消息(输入框未清空且聊天记录未见新消息)")

        log.warning("第 %d/%d 次尝试失败: %s", attempt + 1, cfg.retry + 1, last_err)
        time.sleep(2)
        # 重试前重新定位会话(界面可能已变化)
        item = find_target(page, name)
        if item is not None:
            item.click()
            time.sleep(1.5)

    raise SendError(str(last_err))


# ---------------------------------------------------------------- 对外入口
def render_message(template: str, friend: str, now: Optional[datetime] = None) -> str:
    now = now or datetime.now()
    return (
        template.replace("{{friend}}", friend)
        .replace("{{date}}", now.strftime("%Y-%m-%d"))
        .replace("{{time}}", now.strftime("%H:%M"))
        .replace("{{weekday}}", WEEKDAYS[now.weekday()])
    )


def _save_failure_shot(cfg: AppConfig, page: Page, tag: str) -> None:
    try:
        cfg.logs_dir.mkdir(parents=True, exist_ok=True)
        path = cfg.logs_dir / f"fail-{tag}-{datetime.now().strftime('%Y%m%d-%H%M%S')}.png"
        page.screenshot(path=str(path))
        log.info("失败截图已保存: %s", path)
    except Exception:  # noqa: BLE001
        pass


def run_send(cfg: AppConfig, force: bool = False, only: Optional[str] = None) -> int:
    """执行一次全部目标的发送。返回进程退出码(0 成功 / 1 未登录 / 2 有失败或参数错误)。"""
    if only is not None and not any(t.name == only for t in cfg.targets):
        # 以前这里会落到"所有目标今天都已成功发送"并返回 0:名字写错反而报告成功
        log.error(
            "配置的 targets 里没有「%s」,当前配置的好友:%s",
            only,
            "、".join(t.name for t in cfg.targets) or "(空)",
        )
        return 2
    done = sent_today(cfg.state_path)
    pending = [
        t
        for t in cfg.targets
        if (only is None or t.name == only) and (force or not done.get(t.name, {}).get("ok"))
    ]
    if not pending:
        if only is not None:
            log.info("「%s」今天已经发送成功过(用 --force 可强制重发)", only)
        else:
            log.info("所有目标今天都已成功发送(用 --force 可强制重发)")
        return 0

    with open_context_playwright(cfg) as (pw, ctx):
        page = take_page(ctx)
        try:
            open_chat(page, cfg)
        except NotLoggedInError as e:
            log.error("%s", e)
            return 1

        lo, hi = cfg.interval_seconds or (8, 20)
        failed: List[str] = []
        for i, t in enumerate(pending):
            template = random.choice(t.messages or cfg.messages)
            message = render_message(template, t.name)
            log.info("(%d/%d) 准备发送给「%s」: %s", i + 1, len(pending), t.name, message)
            try:
                send_to_one(page, cfg, t, message)
                try:
                    mark_sent(cfg.state_path, t.name, True)
                except Exception as mark_err:  # noqa: BLE001
                    # 状态写入失败不能反过来把"已经发出去"判成失败
                    log.warning("已发送但状态写入失败(忽略): %s", mark_err)
            except Exception as e:  # noqa: BLE001
                log.error("❌ 向「%s」发送失败: %s", t.name, e)
                failed.append(t.name)
                try:
                    mark_sent(cfg.state_path, t.name, False)
                except Exception as mark_err:  # noqa: BLE001
                    # 以前这里的异常会直接从 except 分支冒出去,终止整轮发送
                    log.warning("写入失败状态时出错(忽略): %s", mark_err)
                _save_failure_shot(cfg, page, t.name)
            if i < len(pending) - 1:
                pause = random.uniform(min(lo, hi), max(lo, hi))
                log.info("等待 %.1f 秒后处理下一个目标", pause)
                time.sleep(pause)

    if failed:
        log.error("本次有 %d 个目标失败: %s", len(failed), ", ".join(failed))
        return 2
    log.info("全部目标发送完成 ✅")
    return 0


class open_context_playwright:
    """组合 sync_playwright 与持久化上下文的生命周期,用于 with 语句。"""

    def __init__(self, cfg: AppConfig, headless: Optional[bool] = None):
        self.cfg = cfg
        self.headless = headless
        self._pw = None
        self._ctx = None

    def __enter__(self):
        self._pw = sync_playwright().start()
        self._ctx = open_context(self._pw, self.cfg, headless=self.headless)
        return self._pw, self._ctx

    def __exit__(self, *exc):
        try:
            if self._ctx:
                self._ctx.close()
        finally:
            if self._pw:
                self._pw.stop()
        return False


# ---------------------------------------------------------------- probe 探查模式
def run_probe(cfg: AppConfig) -> int:
    """打开聊天页并导出关键 DOM 结构信息,用于在抖音灰度改版后校准选择器。"""
    out_dir: Path = cfg.logs_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")

    with open_context_playwright(cfg) as (pw, ctx):
        page = take_page(ctx)
        page.goto(cfg.chat_url, wait_until="domcontentloaded")
        time.sleep(12)

        info = {"url": page.url, "title": page.title(), "logged_in": not login_panel_visible(page)}

        info["session_selector_counts"] = {sel: page.locator(sel).count() for sel in SESSION_SELECTORS}
        items = session_items(page)
        texts = []
        for el in items[:10]:
            try:
                texts.append(el.inner_text(timeout=1500)[:120].replace("\n", " | "))
            except Exception:  # noqa: BLE001
                continue
        info["session_items_text"] = texts

        inputs = []
        loc = page.locator('[contenteditable="true"]')
        for el in first_visible(loc, limit=10):
            try:
                box = el.bounding_box() or {}
                inputs.append({"box": box, "html": el.evaluate("e => e.outerHTML.slice(0, 500)")})
            except Exception:  # noqa: BLE001
                continue
        info["contenteditable_inputs"] = inputs

        send_cands = {sel: page.locator(sel).count() for sel in SEND_SELECTORS}
        info["send_selector_counts"] = send_cands

        msg_counts = page.locator(MSG_ITEM_SELECTOR).count()
        info["msg_item_count"] = msg_counts

        json_path = out_dir / f"probe-{stamp}.json"
        with open(json_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(info, f, ensure_ascii=False, indent=2)
        shot = out_dir / f"probe-{stamp}.png"
        try:
            page.screenshot(path=str(shot), full_page=False)
        except Exception:  # noqa: BLE001
            pass

    log.info("探查结果已保存: %s (截图 %s)", json_path, shot)
    log.info("把这两个文件发给助手即可校准选择器")
    return 0
