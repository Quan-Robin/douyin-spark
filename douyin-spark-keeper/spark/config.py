"""配置加载。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, List

import yaml

PROJECT_DIR = Path(__file__).resolve().parent.parent


@dataclass
class Target:
    name: str
    messages: List[str] = field(default_factory=list)


@dataclass
class AppConfig:
    send_time: str = "21:30"
    jitter_minutes: int = 30
    targets: List[Target] = field(default_factory=list)
    messages: List[str] = field(default_factory=list)
    headless: bool = False
    browser_channel: str = "chrome"
    locale: str = "zh-CN"
    timezone_id: str = "Asia/Shanghai"
    retry: int = 2
    interval_seconds: List[int] = field(default_factory=lambda: [8, 20])
    chat_url: str = "https://www.douyin.com/chat"
    config_path: Path = field(default_factory=lambda: PROJECT_DIR / "config.yaml")

    @property
    def user_data_dir(self) -> Path:
        """浏览器 profile 目录,与 config 文件同目录(每个配置独立登录态)。"""
        return self.config_path.parent / "user_data"

    @property
    def state_path(self) -> Path:
        return self.config_path.parent / "state.json"

    @property
    def logs_dir(self) -> Path:
        return self.config_path.parent / "logs"


def _as_target(item: Any) -> Target:
    if isinstance(item, str):
        return Target(name=item)
    if isinstance(item, dict) and item.get("name"):
        return Target(name=str(item["name"]), messages=list(item.get("messages") or []))
    raise ValueError(f"无法解析 targets 中的配置项: {item!r}")


def parse_hhmm(value: str, field: str = "send_time") -> str:
    """校验并规整 HH:MM。

    注意 YAML 陷阱:写 send_time: 21:30(不加引号)时 PyYAML 会按六十进制把它
    解析成整数 1290,结果守护模式每轮都在 _today_target 里抛 ValueError、
    永远不发消息。这里直接把它挡在配置加载阶段并给出可操作的提示。
    """
    text = str(value).strip()
    parts = text.split(":")
    if len(parts) != 2:
        raise ValueError(
            f"{field} 格式不对:{text!r},应为 HH:MM 且必须用引号包起来(如 send_time: \"21:30\")"
        )
    try:
        h, m = int(parts[0]), int(parts[1])
    except ValueError:
        raise ValueError(f"{field} 格式不对:{text!r},应为 HH:MM(如 \"21:30\")") from None
    if not (0 <= h <= 23 and 0 <= m <= 59):
        raise ValueError(f"{field} 超出范围:{text!r},小时 0-23、分钟 0-59")
    return f"{h:02d}:{m:02d}"


def _interval_seconds(raw: Any) -> List[int]:
    """允许 [8, 20] 或单个数字 10(以前写单个数字会 TypeError 崩在加载阶段)。"""
    if raw is None:
        return [8, 20]
    values = raw if isinstance(raw, (list, tuple)) else [raw]
    try:
        items = [int(x) for x in values]
    except (TypeError, ValueError):
        raise ValueError(f"interval_seconds 只能是数字或数字列表,当前是 {raw!r}") from None
    if not items:
        return [8, 20]
    if len(items) == 1:
        items = [items[0], items[0]]
    lo, hi = min(items[0], items[1]), max(items[0], items[1])
    if lo < 0:
        raise ValueError("interval_seconds 不能为负数")
    return [lo, hi]


def load_config(path: str | os.PathLike | None = None) -> AppConfig:
    cfg_path = Path(path) if path else PROJECT_DIR / "config.yaml"
    if not cfg_path.exists():
        raise FileNotFoundError(
            f"找不到配置文件 {cfg_path}\n"
            f"请先复制 config.example.yaml 为 config.yaml 并填写好友列表"
        )
    try:
        with open(cfg_path, "r", encoding="utf-8") as f:
            raw = yaml.safe_load(f) or {}
    except yaml.YAMLError as e:
        raise ValueError(f"配置文件 {cfg_path} 不是合法的 YAML: {e}") from None
    if not isinstance(raw, dict):
        raise ValueError(f"配置文件 {cfg_path} 的顶层结构应当是键值对,当前是 {type(raw).__name__}")

    cfg = AppConfig(
        send_time=parse_hhmm(raw.get("send_time", "21:30")),
        jitter_minutes=max(0, int(raw.get("jitter_minutes", 30))),
        targets=[_as_target(t) for t in raw.get("targets", [])],
        messages=[str(m).strip() for m in raw.get("messages", []) if str(m).strip()],
        headless=bool(raw.get("headless", False)),
        browser_channel=str(raw.get("browser_channel", "chrome")),
        locale=str(raw.get("locale", "zh-CN")),
        timezone_id=str(raw.get("timezone_id", "Asia/Shanghai")),
        retry=max(0, int(raw.get("retry", 2))),
        interval_seconds=_interval_seconds(raw.get("interval_seconds")),
        chat_url=str(raw.get("chat_url", "https://www.douyin.com/chat")),
        config_path=cfg_path.resolve(),
    )

    if not cfg.targets:
        raise ValueError("配置里 targets 为空,至少要填写一个要续火花的好友")
    if not cfg.messages:
        raise ValueError("配置里 messages 为空,至少要写一条要发送的消息")
    for t in cfg.targets:
        if not t.name.strip():
            raise ValueError("targets 里有空的 name")
        if "," in t.name or "\n" in t.name:
            raise ValueError(f"好友名不能包含逗号或换行: {t.name!r}")
    return cfg
