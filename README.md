# Fnh Go

> 在手机上控制你在电脑上跑的 [DSH（DeepSeek Harness）](https://github.com/deepseek-ai)。会话列表、桌面小组件、定时任务、审批推送、折叠屏适配。

Fnh Go 是 [xiazhi88/dshgo](https://github.com/xiazhi88/dshgo)（MIT）的社区分支（fork）。上游提供了「把只监听 `127.0.0.1` 的 `dsh web` 反代到局域网 + Android App」的完整底座；本分支在它之上加了会话/定时任务小组件、可切换工作区、上下文占比与余额、按条目跳转、PKG 重命名与自签发布链路等。

## 与上游的差别

| 方向 | 上游 dshgo 4.4.3 | 本分支 |
| --- | --- | --- |
| 桌面小组件 | 标题 + 详情 + 允许/拒绝按钮 | 3×2 卡片：工作区切换胶囊（点选，不轮换）、最多 3 条会话（标题 + 摘要 + 上下文占比）、右上账户余额 |
| 数据来源 | 客户端 `session/list` 轮询 | 新增宿主端点 `GET /__dshgo__/widget/state`（工作区/会话/摘要/占比/定时任务/余额，逐源降级） |
| 会话过滤 | — | 剔除种子会话、子代理会话（`parentSession` / `delegationDepth > 0`）、无标题会话 |
| 条目跳转 | — | 点某条会话直接进对应对话（`open_session_id`） |
| 包名 | `com.dshgo.app` | `com.beck.dshgo`（可与官方版并存） |
| 签名 | 官方 release 签名 | 自签 `dshgo-release.jks`（keystore 不入库，见 `.gitignore`） |
| 访问密码 | 锁页 + Cookie | 额外支持「401 自愈」：首屏无 token 且无 cookie 时补 launch token，401 时重试一次 |

## 组成

- **DSH 插件**（`lib/`、`client/`）：把 `dsh web` 反代到局域网（默认 `0.0.0.0:3081`，上游端口跟随宿主随机端口），在设置里提供「局域网访问」面板（地址 + 二维码 + 访问密码 + 下载 App）。
- **Android App**（`android/`）：WebView 壳 + 通知/审批 + 两个 3×2 小组件 + 折叠屏适配。

## 安装

插件（本机开发，profile 内 link）：

```bash
dsh plugin --profile desktop add /path/to/fnh-go -w
```

Android：从本仓库 Releases 下载 APK 侧载。首次连接：在 App 里填 `http://<电脑局域网IP>:3081` 或扫码；若开了访问密码，会先出现解锁页。

## 构建

```bash
# 插件侧无构建步骤；改 lib/client 后重启 dsh web
# Android
cd android
./gradlew :app:assembleDebug        # 或 assembleRelease
```

需要 JDK 17、Android SDK 35、Gradle 8.11+（仓库自带 wrapper）。release 签名依赖仓库根 `keystore.properties`（不入库）：

```properties
storeFile=keystore/dshgo-release.jks
storePassword=...
keyAlias=dshgo
keyPassword=...
```

## 安全

把 DSH 暴露到局域网等于把「可执行任意命令」的能力交出去：

- 只在可信网络开启；**不要**直接把 3081 端口转发到公网；
- 需要外网访问请用 Tailscale 等带身份认证的隧道；
- 强烈建议开启访问密码（设置 → 局域网访问 → 访问密码）。

## 许可与致谢

- 本项目沿用上游 MIT 许可，见 [LICENSE](LICENSE)。
- 上游：[xiazhi88/dshgo](https://github.com/xiazhi88/dshgo)（原作者 xiazhi88）。
- 移动端布局来源于 [mexiaosqwq/dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT），已内联。
- 第三方许可与改动声明见 [NOTICE](NOTICE)。
