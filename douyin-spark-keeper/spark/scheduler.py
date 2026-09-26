"""守护模式:每天在配置的时间附近自动发送一次。"""
from __future__ import annotations

import logging
import random
import time
from datetime import datetime, timedelta

from .config import AppConfig
from .sender import run_send
from .state import sent_today

log = logging.getLogger("spark")


def _parse_hhmm(s: str) -> tuple[int, int]:
    h, m = s.split(":")
    return int(h), int(m)


def _today_target(cfg: AppConfig, now: datetime) -> datetime:
    h, m = _parse_hhmm(cfg.send_time)
    jitter = random.randint(-cfg.jitter_minutes, cfg.jitter_minutes) if cfg.jitter_minutes else 0
    return now.replace(hour=h, minute=m, second=0, microsecond=0) + timedelta(minutes=jitter)


def run_daemon(cfg: AppConfig) -> None:
    log.info("守护进程启动:每天 %s ±%d 分钟自动续火,目标 %s", cfg.send_time, cfg.jitter_minutes, [t.name for t in cfg.targets])
    log.info("按 Ctrl+C 退出")
    plan_date = None
    target: datetime | None = None
    while True:
        try:
            now = datetime.now()
            # 每天只在第一次进入循环时摇一次抖动。
            # 以前每轮循环都重算 _today_target,而循环只在"新摇出的时间 <= 现在"时才发送,
            # 等于把实际发送时间一步步推向窗口末端(最晚 send_time + jitter),和文档不符。
            if target is None or plan_date != now.date():
                plan_date = now.date()
                target = _today_target(cfg, now)
                log.info("今日计划发送时间: %s", target.strftime("%H:%M"))
            done = sent_today(cfg.state_path)
            all_ok = all(done.get(t.name, {}).get("ok") for t in cfg.targets)

            if now >= target and not all_ok:
                log.info("到达计划时间(%s),开始执行今日续火", target.strftime("%H:%M"))
                code = run_send(cfg)
                if code == 0:
                    log.info("今日续火完成")
                elif code == 1:
                    log.error("登录态失效,守护进程暂停 30 分钟,请执行 python -m spark login 重新登录")
                    time_sleep = 30 * 60
                    _sleep(time_sleep)
                else:
                    log.warning("存在失败目标,1 小时后重试(成功的目标不会重发)")
                    _sleep(60 * 60)
                continue

            if all_ok:
                next_run = (now + timedelta(days=1)).replace(
                    hour=_parse_hhmm(cfg.send_time)[0],
                    minute=_parse_hhmm(cfg.send_time)[1],
                    second=0,
                    microsecond=0,
                )
            elif now < target:
                next_run = target
            else:
                next_run = now + timedelta(minutes=10)

            wait = (next_run - datetime.now()).total_seconds()
            wait = max(30, min(wait, 3600))
            _sleep(wait)
        except KeyboardInterrupt:
            log.info("守护进程已退出")
            return
        except Exception as e:  # noqa: BLE001
            log.exception("守护进程出现未预期错误,10 分钟后重试: %s", e)
            _sleep(600)


def _sleep(seconds: float) -> None:
    """分段休眠,便于 Ctrl+C 及时响应。"""
    end = datetime.now().timestamp() + seconds
    while True:
        remain = end - datetime.now().timestamp()
        if remain <= 0:
            return
        time.sleep(min(30, remain))
