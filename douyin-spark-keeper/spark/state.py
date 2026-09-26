"""运行状态记录:防止同一天对同一好友重复发送。"""
from __future__ import annotations

import json
import threading
from datetime import date
from pathlib import Path

_LOCK = threading.Lock()
MAX_DAYS = 30


def _load(path: Path) -> dict:
    """读取状态文件;文件缺失/损坏/结构不对时一律当空状态,绝不抛异常。"""
    if not path.exists():
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (json.JSONDecodeError, OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _day_sent(data: dict, today: str) -> dict:
    """取出当天的 sent 字典;结构损坏时就地重建。

    以前是 data.setdefault(today, {"sent": {}}) 之后直接取 day["sent"],
    只要当天的条目存在但没有 sent 键就会 KeyError,而它是在发送成功之后调用的,
    异常会让整轮运行中断、当日记录丢失。
    """
    day = data.get(today)
    if not isinstance(day, dict):
        day = {}
        data[today] = day
    sent = day.get("sent")
    if not isinstance(sent, dict):
        sent = {}
        day["sent"] = sent
    return sent


def _save(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    tmp.replace(path)


def mark_sent(state_path: Path, friend: str, ok: bool) -> None:
    today = date.today().isoformat()
    with _LOCK:
        data = _load(state_path)
        sent = _day_sent(data, today)
        sent[friend] = {"ok": bool(ok)}
        # 只保留最近 MAX_DAYS 天(键是 ISO 日期,字典序即时间序)
        for k in sorted(data.keys())[:-MAX_DAYS]:
            data.pop(k, None)
        _save(state_path, data)


def sent_today(state_path: Path) -> dict:
    today = date.today().isoformat()
    data = _load(state_path)
    day = data.get(today)
    if not isinstance(day, dict):
        return {}
    sent = day.get("sent")
    return sent if isinstance(sent, dict) else {}
