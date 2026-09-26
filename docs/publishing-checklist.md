# 开源发布前检查清单

> 这份清单是按"这个仓库里实际存在的东西"写的,逐条确认一遍再 push。

## 1. 绝对不能提交的东西(凭据 / 隐私)

| 路径 | 里面是什么 | 现状 |
|---|---|---|
| `douyin-spark-keeper/user_data/` | **完整的 Chrome 浏览器 profile,含抖音登录 Cookie、History、Local Storage** —— 等同于账号凭据 | 已被 `.gitignore` 忽略,发布前请再确认它没有被 `git add -f` 加进来 |
| `douyin-spark-keeper/config.yaml` | 你的真实好友名 / 消息内容 | 已忽略;仓库里只保留 `config.example.yaml`(用的是"测试好友A/B") |
| `douyin-spark-keeper/logs/` | 运行日志、失败截图、probe 结果(可能含聊天截图与好友昵称) | 已忽略 |
| `douyin-spark-keeper/state.json` | 当日发送记录(含好友昵称) | 已忽略 |
| `douyin-spark-mobile/logs/`、`spark_state.json` | AutoX.js 脚本版的运行数据 | 已忽略 |
| `douyin-spark-mobile/apk/debug.keystore` | 调试签名私钥(口令是公开的 `android`,不是秘密,但没必要入库) | 已忽略,`build.sh` 会在缺失时自动重新生成 |

**验证方法**(在仓库根目录执行,应当没有任何输出):

```bash
git status --porcelain | Select-String "user_data|config.yaml|state.json|logs/|keystore"
git ls-files | Select-String "user_data|config.yaml|state.json|debug.keystore"
```

另外建议全文搜一遍自己的昵称 / 手机号 / 抖音号:

```bash
git grep -n -i -E "你的昵称|你的手机号|sessionid|passport_csrf_token"
```

## 1.5 仓库根目录别把别人的项目带上

`D:\AI\DSH` 是**共用工作目录**,里面除了本项目还有 `wallpaper-schedule/`、`legacy/`、`tests/`、
`.tmp-github-*/`、`DOCS-README.md` 等无关内容(其中 `wallpaper-schedule/` 自己还带 `.git`)。

两种处理方式,任选:

1. **推荐:单独放一个目录再 init**(最干净)
   ```bash
   mkdir douyin-spark && cd douyin-spark
   git init
   # 把 README.md / LICENSE / .gitignore / docs/ / douyin-spark-mobile/ / douyin-spark-keeper/ 拷进来
   ```
   注意 `douyin-spark-keeper/user_data/`(登录态)与 `config.yaml` 不要拷。
2. 就在当前根目录 init:根 `.gitignore` 已把这些无关目录排除,但每次 `git add .` 之后
   **必须**用 `git status` 逐屏确认。

## 2. 构建产物

- `douyin-spark-mobile/apk/build/`、`SparkKeeper.apk`、`*.idsig` 已加入 `.gitignore`。
- 如果你希望仓库里**直接带一个可安装的 APK**(对非技术用户更友好),把 `.gitignore` 里对应两行删掉即可;
  更推荐的做法是构建后作为 **GitHub Release 附件**上传,README 里链接 Release 页面。

## 3. 许可证与合规

- [x] `LICENSE`(MIT)。若想换 Apache-2.0 / GPL-3.0,替换 LICENSE 全文,并同步更新根 README 的"开源说明"一节。
- [x] 根 README 已包含"隐私与安全""请遵守抖音用户协议""风险自担"的说明。
- [ ] 如果你的仓库要接受他人贡献,建议补一个 `CONTRIBUTING.md`(可选)。

## 4. 首次发布的命令

```bash
cd <仓库根目录>
git init
git add .
git status                 # 逐屏确认:不该出现的文件一个都没有
git commit -m "feat: 抖音自动续火花(手机端 APK + 电脑端 Python)"
git branch -M main
git remote add origin git@github.com:<你的账号>/<仓库名>.git
git push -u origin main
```

## 5. 发布之后

- 在 GitHub 上补 **Topics**:`douyin` `automation` `accessibility-service` `playwright` `android` `autojs`
- 在 Releases 里上传构建好的 `SparkKeeper.apk`(Release 说明里写清版本号与变更)
- 提醒使用者:**不要把自己的 `user_data/`、日志或配置贴到 Issue 里**
- 抖音网页版/App 改版后选择器会失效,仓库里的 `docs/douyin-web-chat-selectors.md` 是校准用的对照表
