"""命令行入口:python -m spark <login|send|daemon|probe>"""
from __future__ import annotations

import argparse
import logging
import sys
import time
from datetime import datetime

from .config import load_config
from .scheduler import run_daemon
from .sender import run_probe, run_send

log = logging.getLogger("spark")


def _log_dir():
    """日志目录:与 -c 指定的配置文件同级(和 probe 结果、失败截图保持一致)。

    以前固定写 PROJECT_DIR/logs,而 sender/probe 用的是 cfg.logs_dir(配置文件同级),
    用 -c 指定别处配置时两处日志会分家,排查时看不到同一份记录。
    """
    from pathlib import Path

    from .config import PROJECT_DIR

    try:
        argv = sys.argv[1:]
        for i, a in enumerate(argv):
            if a in ("-c", "--config") and i + 1 < len(argv):
                return Path(argv[i + 1]).resolve().parent / "logs"
            if a.startswith("--config="):
                return Path(a.split("=", 1)[1]).resolve().parent / "logs"
    except Exception:  # noqa: BLE001
        pass
    return PROJECT_DIR / "logs"


def _setup_logging(verbose: bool) -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except Exception:  # noqa: BLE001
        pass
    fmt = "%(asctime)s %(levelname)s %(message)s"
    handlers = [logging.StreamHandler()]
    root = logging.getLogger()
    root.setLevel(logging.DEBUG if verbose else logging.INFO)

    log_dir = _log_dir()
    log_dir.mkdir(parents=True, exist_ok=True)
    fh = logging.FileHandler(
        log_dir / f"spark-{datetime.now().strftime('%Y%m%d')}.log", encoding="utf-8"
    )
    handlers.append(fh)
    for h in handlers:
        h.setFormatter(logging.Formatter(fmt))
    root.handlers = handlers


def cmd_login(cfg_path: str) -> int:
    from .browser import login_panel_visible, take_page
    from .sender import find_inputs, open_context_playwright, session_items

    cfg = load_config(cfg_path)
    log.info("启动浏览器打开抖音聊天页,请在窗口中扫码登录(5 分钟内完成)")
    ok = False
    with open_context_playwright(cfg, headless=False) as (pw, ctx):
        page = take_page(ctx)
        page.goto(cfg.chat_url, wait_until="domcontentloaded")
        deadline = time.time() + 300
        while time.time() < deadline:
            if not login_panel_visible(page) and (find_inputs(page) or session_items(page)):
                ok = True
                break
            time.sleep(3)
        if ok:
            log.info("✅ 登录成功,登录态已保存在 user_data/ 目录,以后无需重复登录")
            log.info("接下来可以运行:python -m spark send")
        else:
            log.warning("等待登录超时,请重新运行 python -m spark login 再试")
    return 0 if ok else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="spark",
        description="抖音自动续火花(基于网页版 https://www.douyin.com/chat)",
    )
    parser.add_argument("-c", "--config", default=None, help="配置文件路径(默认项目根目录 config.yaml)")
    parser.add_argument("-v", "--verbose", action="store_true", help="输出调试日志")
    # 子命令里也接受 -c/-v:README 的命令表写的是 send -v,
    # 而 argparse 默认只认子命令之前的选项,那样写会直接报 unrecognized arguments。
    # SUPPRESS 保证子解析器没写这些选项时不会把全局取值覆盖成默认值。
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-c", "--config", default=argparse.SUPPRESS,
                        help="配置文件路径(默认项目根目录 config.yaml)")
    common.add_argument("-v", "--verbose", action="store_true", default=argparse.SUPPRESS,
                        help="输出调试日志")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("login", parents=[common], help="打开浏览器扫码登录,保存登录态")
    p_send = sub.add_parser("send", parents=[common], help="立即执行一次续火发送")
    p_send.add_argument("--force", action="store_true", help="忽略今日已发送记录,强制重发")
    p_send.add_argument("--only", default=None, help="只对指定好友发送")
    sub.add_parser("daemon", parents=[common], help="守护模式:每天定时自动发送")
    sub.add_parser("probe", parents=[common], help="探查聊天页 DOM 结构(用于改版后校准)")

    args = parser.parse_args(argv)
    _setup_logging(args.verbose)

    try:
        cfg = load_config(args.config)
    except (FileNotFoundError, ValueError) as e:
        log.error("%s", e)
        return 1

    if args.command == "login":
        return cmd_login(args.config)
    if args.command == "send":
        return run_send(cfg, force=args.force, only=args.only)
    if args.command == "daemon":
        run_daemon(cfg)
        return 0
    if args.command == "probe":
        return run_probe(cfg)
    return 1


if __name__ == "__main__":
    sys.exit(main())
