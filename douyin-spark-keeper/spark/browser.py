"""浏览器上下文与登录相关辅助。"""
from __future__ import annotations

import logging
from typing import List, Optional

from playwright.sync_api import BrowserContext, Locator, Page, sync_playwright

from .config import AppConfig

log = logging.getLogger("spark")


class NotLoggedInError(RuntimeError):
    pass


def open_context(pw: sync_playwright, cfg: AppConfig, headless: Optional[bool] = None) -> BrowserContext:
    """打开持久化浏览器上下文。channel 按配置依次降级:chrome -> msedge -> 内置 chromium。"""
    cfg.user_data_dir.mkdir(parents=True, exist_ok=True)
    channels = _channel_candidates(cfg.browser_channel)

    last_err: Exception | None = None
    for ch in channels:
        try:
            kwargs = dict(
                user_data_dir=str(cfg.user_data_dir),
                headless=cfg.headless if headless is None else headless,
                locale=cfg.locale,
                timezone_id=cfg.timezone_id,
            )
            if cfg.headless if headless is None else headless:
                kwargs["viewport"] = {"width": 1280, "height": 900}
            else:
                kwargs["no_viewport"] = True
            if ch:
                kwargs["channel"] = ch
            ctx = pw.chromium.launch_persistent_context(**kwargs)
            log.info("浏览器已启动 (channel=%s, headless=%s)", ch or "chromium", kwargs["headless"])
            return ctx
        except Exception as e:  # noqa: BLE001 - 逐个通道尝试
            last_err = e
            log.warning("启动浏览器失败 (channel=%s): %s", ch or "chromium", e)
    raise RuntimeError(f"无法启动浏览器,请确认已安装 Chrome/Edge 或执行 playwright install chromium: {last_err}")


# Playwright 的 channel 只认这些取值;内置 chromium 必须"不传 channel",不能写成 "chromium"
_KNOWN_CHANNELS = ("chrome", "chrome-beta", "msedge", "msedge-beta", "msedge-dev")
_BUNDLED_ALIASES = ("", "chromium", "default", "bundled", "playwright")


def _channel_candidates(preferred: str) -> List[Optional[str]]:
    """把配置里的通道名规整成可用的降级链。

    以前直接把配置值塞进 launch_persistent_context:配置写 "chromium"(内置浏览器,
    文档里说支持)时会先抛一次 "Unsupported channel" 再降级,白跑一次启动。
    """
    out: List[Optional[str]] = []
    name = (preferred or "").strip().lower()
    if name in _BUNDLED_ALIASES:
        out.append(None)
    elif name in _KNOWN_CHANNELS:
        out.append(name)
    else:
        log.warning("未知的 browser_channel=%r,将按默认顺序尝试", preferred)
    for fallback in ("chrome", "msedge", None):
        if fallback not in out:
            out.append(fallback)
    return out


def take_page(page: BrowserContext) -> Page:
    """取一个可用页面(复用第一个标签页,避免多开)。"""
    if page.pages:
        return page.pages[0]
    return page.new_page()


_LOGIN_MARKERS = ("扫码登录", "请输入手机号", "验证码登录", "打开「抖音APP」")


def login_panel_visible(page: Page) -> bool:
    """检测登录面板是否可见(未登录标志)。"""
    for marker in _LOGIN_MARKERS:
        try:
            loc = page.get_by_text(marker)
            if loc.count() > 0 and loc.first.is_visible():
                return True
        except Exception:  # noqa: BLE001
            continue
    return False


def first_visible(loc: Locator, limit: int = 40) -> List[Locator]:
    """返回 locator 命中的可见元素列表(最多 limit 个)。"""
    out: List[Locator] = []
    try:
        n = loc.count()
    except Exception:  # noqa: BLE001
        return out
    for i in range(min(n, limit)):
        el = loc.nth(i)
        try:
            if el.is_visible():
                out.append(el)
        except Exception:  # noqa: BLE001
            continue
    return out
