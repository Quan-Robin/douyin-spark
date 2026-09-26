# Douyin web private-message automation — research report

Researched via web_search + direct source download (GitHub raw fetched through Node, because the tool-level `web_fetch` was DNS-blocked in this session). All selectors below are quoted **verbatim** from the cited file:line.

## 1. Open-source projects (stars from GitHub API, fetched this session)

| Repo | Stars | Lang | Approach | Relevant files |
|---|---|---|---|---|
| https://github.com/3441293738/creatorhub | 2087 | Python | Multi-platform; Douyin DM via XHR/WS interception (**DM automation is XHS-only**) | `app/browser/douyin_im_ws.py`, `app/browser/douyin_im_pb.py` |
| https://github.com/halfwaystudent/douyin-sparkflow | 495 | Python/JS | Fork of DouYinSparkFlow; **loads creator.douyin.com IM webpack SDK in Node** | `DouYinSparkFlow/core/protocol_sender.mjs` |
| https://github.com/2061360308/DouYinSparkFlow | 379 | Python | Playwright DOM automation on `/chat` | `core/tasks.py` |
| https://github.com/pen9un/douyin-chatgpt-bot | 372 | (none) | README only — **no source published** (commercial product) | — |
| https://github.com/unmev/douyin-auto-fire | 323 | Python | Playwright; **dedicated selector module** | `app/selectors.py`, `app/douyin.py`, `app/sender.py` |
| https://github.com/bling-yshs/douyin-auto-spark | 104 | TypeScript | Playwright; repo description now reads **【已被风控失效】** | `src/main.ts` |
| https://github.com/Yuriz132/douyin-cloud-streak | 57 | Python | Playwright; geometry+text based, most defensive | `core/automation.py` |
| https://github.com/Airmole/douyin-wss | 20 | HTML | WSS capture analysis of web 私信 (stale, self-declared) | `README.md` |
| https://github.com/Lozzi1910/Douyin-mcp | 17 | Python | Playwright MCP; includes design/technical report | `douyin_mcp/browser.py`, `TECHNICAL_REPORT.md` |
| https://github.com/terrytz/dy-mcp-server | 4 | JS | **asar injector into the desktop app** (not web) | `scripts/patch-asar.cjs` |
| https://github.com/zhinjs/douyin-im | 2 | TypeScript | **Pure HTTP/WS SDK, no browser** (npm `douyin-im`) | `docs/adr/*`, `fixtures/captured/services/im_endpoints.json` |
| https://github.com/wangjiacheng121/chengDouyinFire | 1 | Python | Selenium | `xuhuohua.py` |
| https://github.com/hexiruwu/Douyin-Automatic-Spark-Plug-Extension-Plugin | 0 | HTML/JS | Userscript-style, geometry heuristics | `project.js` |
| https://github.com/BowenYuan1/Douyin-AutoResponder | 0 | JS | Chrome MV3 extension, **hooks protobuf IM traffic** | `background.js`, `libs/message.proto` |

## 2. Selectors found in source (verbatim)

### Conversation list item 会话列表条目
| Selector (verbatim) | Source |
|---|---|
| `.conversationConversationItemwrapper` | 2061360308/DouYinSparkFlow `core/tasks.py:13` |
| `.conversationConversationItemtitle` | 2061360308/DouYinSparkFlow `core/tasks.py:14` |
| `.conversationConversationListwrapper` | 2061360308/DouYinSparkFlow `core/tasks.py:15` |
| `[data-e2e="conversation-item"]` | unmev/douyin-auto-fire `app/douyin.py:110` |
| `[class*="conversationConversationItem"]` / `[class*="conversation-item"]` / `[class*="ConversationItem"]` | unmev/douyin-auto-fire `app/douyin.py:111-113` |
| `[class*="conversationConversationItemtitle"]`, `[class*="ConversationItemtitle"]`, `[class*="ConversationItemTitle"]`, `[class*="conversation-item-title"]`, `[class*="conversation-item-Title"]` | unmev/douyin-auto-fire `app/douyin.py:116-120` |
| `[class*="conversation"], [class*="Conversation"]` (ready check) | bling-yshs/douyin-auto-spark `src/main.ts:224` |
| `//div[@data-e2e="conversation-item" and contains(@class, " ") and not(contains(substring-after(@class, " "), " "))]//pre` | Tencent Cloud article (唯一Chat) |
| `//div[contains(@class,"conversationConversationItemcurConversation")]//pre` | Tencent Cloud article |

### Search input 搜索框
| Selector (verbatim) | Source |
|---|---|
| `input.semi-input[placeholder="搜索"]` | bling-yshs/douyin-auto-spark `src/main.ts:130` |
| `input[placeholder*="搜索"]` | unmev/douyin-auto-fire `app/selectors.py:20` |
| `input[placeholder="搜索"]` | unmev/douyin-auto-fire `app/selectors.py:21` |
| `[role="textbox"][placeholder*="搜索"]` | unmev/douyin-auto-fire `app/selectors.py:22` |
| `input[aria-label*="搜索"]` / `[role="textbox"][aria-label*="搜索"]` | unmev/douyin-auto-fire `app/selectors.py:23-24` |
| `page.get_by_placeholder("搜索", exact=False)` | Yuriz132/douyin-cloud-streak `core/automation.py:228` |

**Important trap (found, not inferred):** there are two search boxes. Yuriz132 `core/automation.py:226-235` only accepts the one whose bounding box `x < 400`, commenting that the top global box "跳离 /chat 页". Also `box.click()` before `fill()`.

### Search result item 搜索结果项
| Selector (verbatim) | Source |
|---|---|
| `.SearchPanelitembox` | bling-yshs/douyin-auto-spark `src/main.ts:256, 266` |
| `[class*="SearchPanelitembox"], [class*="SearchPanelitem-box"], [class*="SearchPanelitem_box"]` | unmev/douyin-auto-fire `app/douyin.py:66` |
| `[class*="SearchPanelitemtitle"]`, `[class*="SearchPanelitemTitle"]`, `[class*="SearchPanelitem_title"]`, `[class*="SearchPanelitem-title"]`, `[class*="SearchPanelitemname"]`, `[class*="SearchPanelitemName"]`, `[class*="SearchPanelitem_name"]`, `[class*="SearchPanelitem-name"]` | unmev/douyin-auto-fire `app/douyin.py:68-75` |
| `[class*="SearchPanelitemchat_btn"]` (the action button) | unmev/douyin-auto-fire `app/douyin.py:88, 100` |
| `getByText(/^(发消息|发私信)$/)` then `.click()` | bling-yshs/douyin-auto-spark `src/main.ts:160` |

### Message input editor 输入框 — **Slate confirmed; Draft.js also claimed**
| Selector (verbatim) | Source |
|---|---|
| `.messageEditorimChatEditorContainer [data-slate-editor="true"][contenteditable="true"]` | bling-yshs/douyin-auto-spark `src/main.ts:164-166` |
| `.messageEditorimChatEditorContainer` (container) | 2061360308/DouYinSparkFlow `core/tasks.py:16` |
| `[data-contents="true"]` | unmev/douyin-auto-fire `app/selectors.py:35` |
| `.DraftEditor-editor [contenteditable="true"]` | unmev/douyin-auto-fire `app/selectors.py:36` |
| `.DraftEditor-root [contenteditable="true"]` | unmev/douyin-auto-fire `app/selectors.py:37` |
| `[contenteditable="true"][data-placeholder*="发送消息"]` | unmev/douyin-auto-fire `app/selectors.py:38` |
| `[contenteditable="true"][aria-label*="消息"]` | unmev/douyin-auto-fire `app/selectors.py:39` |
| `[contenteditable="true"]` (ordered last) | unmev/douyin-auto-fire `app/selectors.py:40` |
| `textarea[placeholder*="消息"]` | unmev/douyin-auto-fire `app/selectors.py:41` |
| `div[contenteditable="true"]` | Yuriz132/douyin-cloud-streak `core/automation.py:545` |
| `//div[@contenteditable="true"]/div/div` and `//div[@contenteditable="true"]/div/span` | Tencent Cloud article |
| `[class*=messageEditor] [contenteditable=true], .messageEditorinputArea` | unmev/douyin-auto-fire `app/sender.py:147` (inside `page.evaluate`) |

**Verdict:** a working, maintained Playwright project types into a **Slate** editor (`data-slate-editor="true"`). Lozzi1910's report claims **Draft.js** (`[data-contents="true"]`, `.DraftEditor-root`), but that report's DOM tree is explicitly labelled 大致如下 ("roughly as follows") — I could not verify Draft.js from a real captured DOM. Both may coexist as A/B variants. Recommended order: Slate → `[contenteditable="true"]` generic. Inputs: `keyboard.insertText()` (spark:185) or `fill()` (fire).

### Send action 发送
| Selector (verbatim) | Source |
|---|---|
| `page.keyboard.press('Enter')` | bling-yshs/douyin-auto-spark `src/main.ts:186`; fallback in unmev `app/sender.py:45` |
| `[class*="messageMsgInputpublishBtn"]` | unmev/douyin-auto-fire `app/sender.py:25` |
| `.e2e-send-msg-bt` | unmev/douyin-auto-fire `app/sender.py:26` |
| `button[aria-label*="发送"]` / `[role="button"][aria-label*="发送"]` | unmev/douyin-auto-fire `app/sender.py:27-28` |
| `//span[contains(@class,"e2e-send-msg-btn")]` then `//div[contains(@class,"messageMsgInputinputAction")]/*[3]` | Tencent Cloud article |

**Discrepancy worth flagging:** the article says `e2e-send-msg-btn` (with trailing **n**); unmev's code says `.e2e-send-msg-bt` (no **n**). Prefix-match both.

### Sent message bubble 消息气泡
| Selector (verbatim) | Source |
|---|---|
| `.messageMessageListlist [data-index="0"] .messageMessageBoxmessageBox:has(.messageMessageBoxcontentBox.messageMessageBoxisFromMe)` | unmev/douyin-auto-fire `app/sender.py:59-62` |
| `[data-e2e="msg-item-content"]` | unmev/douyin-auto-fire `app/sender.py:267, 432` |
| `//div[@class="messageMessageListlist"]//div[@data-index="0"]//div[@data-e2e="msg-item-content"]` | Tencent Cloud article |
| `//div[@id="messageContent"]/div[1]/div[3]/div[contains(@style, "justify-content: space-between;")]//pre` | Tencent Cloud article |
| send-failure markers: `[class*="ContentSideSendStatusretry"]`, `[class*="SendStatusretry"]`, `[aria-label*="重试"]`, `[title*="重试"]`, `text=发送失败` | unmev/douyin-auto-fire `app/sender.py:76-84, 93-98` |
| pending spinner: `.semi-spin`, `[class*="im-saas-message-spin"]`, `[data-icon="spin"]` | unmev/douyin-auto-fire `app/sender.py:88-92` |

Yuriz132 deliberately avoids bubble classes: it counts elements matching the message text exactly with `bounding_box()` filtering `x > 300 and 60 < y < input_top - 6` (`core/automation.py:472-490`).

### Extras
| Selector | Source |
|---|---|
| `//a[@data-e2e='messaging-icon']` (私信 entry icon) | wangjiacheng121/chengDouyinFire `xuhuohua.py:190` |
| `//div[contains(@class, 'message-icon')]` (fallback) | `xuhuohua.py:199` |
| nickname: `//div[@data-mask="conversaton-detail-content"]/div[1]//span` — **note the source's own typo "conversaton"**, fallback `//div[@class="RightPanelHeadertitle"]` | Tencent Cloud article |
| panel markers: `[class*="RightPanelHeader"]`, `[class*="chatHeader"]`, `[class*="ChatHeader"]`, `[class*="messageContent"]`, `[class*="chatContent"]`, `[class*="MessagePanel"]` | unmev/douyin-auto-fire `app/selectors.py:26-33` |

## 3. Anti-bot / device-signature requirements

**Found:**
- `a_bogus`, `msToken`, `X-Bogus` algorithms: https://github.com/ylcangel/douyin_sign (README only documents module layout).
- mafqla/douyin-api `docs/sign_reverse_findings.md`: **feed/favorite** endpoints need a "three-piece" set — `a_bogus` + `timestamp` + `x-secsdk-web-signature`; deleting any → 403 / "Sign Invalid". secsdk keys live in localStorage (`security-sdk/s_sdk_crypt_sdk`, `s_sdk_server_cert_key`, `web_runtime_security_uid`). **This document is about feed endpoints, not the chat API.**
- zhinjs/douyin-im `fixtures/captured/services/im_endpoints.json`: `getToken` = `GET /aweme/v1/creator/im/user_token/v2/` with params `aid=2906`, `app_name=aweme_creator_platform`, `device_platform=web`, `msToken: "<required>"`, `a_bogus: "<computed>"`, `certificate: "<base64 bd_ticket_guard CSR - optional>"`; header `x-secsdk-csrf-token`; response returns `token` + `ts_sign` + `sdk_cert` (鉴权三要素). Transport: protobuf POST to `imapi.snssdk.com`.
- `bd_ticket_guard` mechanics — zhinjs/douyin-im `scripts/research/bdticket-oracle.cjs`: headers `bd-ticket-guard-client-data` (base64 JSON with `timestamp`, `req_sign_ree`, `ts_sign_ree`), `bd-ticket-guard-ree-public-key` (EC P-256), `bd-ticket-guard-iteration-version: 3`, `bd-ticket-guard-server-data`. `req_sign_ree = HMAC-SHA256(HKDF(ECDH-shared-secret), "ticket=<session>&path=<path>&timestamp=<ts>")`. Guarded paths listed are **login/follow**: `/passport/account/info/v2/`, `/aweme/v1/web/commit/follow/user/`, `/passport/token/beat/v2/`, `/passport/web/check_qrconnect/`, `/passport/web/user/login/`, `/passport/web/sms_login/`. Host in the oracle: `imdesktop.douyin.com`. Implemented as a native addon (`bdticket.node`).
- `identity_security_token` — halfwaystudent/douyin-sparkflow `core/protocol_sender.mjs:366-395, 612-617`: `GET https://creator.douyin.com/passport/safe/get_identity_security_token/` (params `scene=im_send_msg`, `aid=2906`, `passport_jssdk_version=5.1.4`, …), then send headers `identity_security_token`, `identity_security_device_id`, `identity_security_aid: "2906"`. Fetch is skipped on `--dry-run` — i.e. **it is required only to actually send**.
- zhinjs/douyin-im README:223: "`msToken` 不再作为登录签名 token".

**Does a real browser/WebView avoid it? Yes — strongly supported:**
- None of the DOM-automation repos contain any signing code. A grep for `bd_ticket_guard|identity_security_token|a_bogus|msToken|X-Bogus|verifyFp|s_v_web_id` across their sources returned **zero hits**. They inject cookies and click; the page's own JS produces every signature.
- zhinjs/douyin-im `docs/adr/0001-pure-http-no-browser-automation.md` states the opposite tradeoff explicitly: they reimplement 签名/加密/设备画像 in Node precisely to avoid Puppeteer/Playwright.
- The real limiter is risk control, not signatures: bling-yshs/douyin-auto-spark's description now reads 【已被风控失效】, and its README rates GitHub-Actions (datacenter IP) success as 低 (基本不可用) vs Docker/local 高.

**Not found:** no source documents `bd_ticket_guard` or `identity_security_token` being required by the **consumer** `www.douyin.com/chat` web IM. Every capture of those tokens I found is for **creator.douyin.com / imdesktop (aid 2906)**. Treat that as an open question.

## 4. Desktop UA and SPA behaviour

- **No source states a desktop UA is required.** unmev/douyin-auto-fire (323★) launches plain `chromium.launch(headless=...)` with context `{"viewport": {"width": 1440, "height": 1000}, "locale": "zh-CN"}` and **no `user_agent` override** (`app/browser.py:80,85`). Lozzi1910/Douyin-mcp *does* set desktop Chrome UAs (macOS 10.15 / Linux x86_64) plus `locale="zh-CN"`, `timezone_id="Asia/Shanghai"` — presented as anti-detection, not as a stated requirement.
- **React SPA — confirmed in sources:** Lozzi1910 `TECHNICAL_REPORT.md:22` "整个 douyin.com 是单页应用，私信 UI 由 React 组件动态渲染"; line 36 "聊天消息界面通过 React Router 在 /messages 路径下管理". Behavioural proof of client-side routing: Yuriz132 `core/automation.py:226-229` warns the top global search box "fill 后会跳离 /chat 页" with no reload.
- **Cold start / hydration:** Yuriz132 `core/automation.py:863-878` — `/chat` first renders a skeleton whose placeholder text is repeated "word"; real rows mount only after the conversation API returns. Wait for `.conversationConversationItemtitle, [class*='conversationItem']`. bling-yshs likewise waits for `networkidle` (`src/main.ts:235`).
- **Virtual scrolling** conversation list (`core/automation.py:881-884`) — small scroll steps required.
- **Micro-frontend caveat:** the Tencent Cloud article's architecture lists "DOM Access Layer … `shadowRoot.firstElementChild` (wujie)" — i.e. in some builds the chat UI is inside a **wujie shadow root**, which would make `document.querySelector` miss it. Relevant for a WebView app.
- **URL ambiguity (unresolved):** bling-yshs `src/main.ts:126`, unmev `app/selectors.py:1`, DouYinSparkFlow use `https://www.douyin.com/chat`; Lozzi1910 uses `https://www.douyin.com/messages` (`douyin_mcp/browser.py:40`). No source explains the relationship.
- **Unverified:** I downloaded hexiruwu's 1.2 MB saved page (`douyin.html`) hoping for real chat markup, but it is a **homepage/feed** snapshot — it contains `data-e2e="searchbar-input"`, `data-e2e="searchbar-button"`, `data-e2e="douyin-navigation"` and **hashed CSS-module classes** (e.g. `class="YEhxqQNi jUqDCyab"`), with zero occurrences of `conversationConversationItem`, `data-slate-editor`, `DraftEditor`, `semi-input` or `msg-item-content`. So the main site's classes are hashed, while the IM module's are readable BEM-like names — but **I could not verify chat-page classes against a real captured DOM.**

## Sources
- https://cloud.tencent.cn/developer/article/2682152 (唯一Chat, 2026-06-03/04) — multi-version DOM adaptation
- https://github.com/unmev/douyin-auto-fire — `app/selectors.py`, `app/douyin.py`, `app/sender.py`, `app/browser.py`
- https://github.com/bling-yshs/douyin-auto-spark — `src/main.ts`
- https://github.com/2061360308/DouYinSparkFlow — `core/tasks.py`
- https://github.com/Yuriz132/douyin-cloud-streak — `core/automation.py`
- https://github.com/Lozzi1910/Douyin-mcp — `TECHNICAL_REPORT.md`, `douyin_mcp/browser.py`
- https://github.com/halfwaystudent/douyin-sparkflow — `core/protocol_sender.mjs`
- https://github.com/zhinjs/douyin-im — `docs/adr/0001…`, `fixtures/captured/services/im_endpoints.json`, `scripts/research/bdticket-oracle.cjs`
- https://github.com/mafqla/douyin-api — `docs/sign_reverse_findings.md`
- https://github.com/wangjiacheng121/chengDouyinFire — `xuhuohua.py`
- https://github.com/Airmole/douyin-wss — `README.md`
