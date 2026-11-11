# Fnh Go

> 在手机上控制你在电脑上跑的 [DSH（DeepSeek Harness）](https://github.com/deepseek-ai)。会话列表、桌面小组件、定时任务、审批推送、折叠屏适配；局域网与 Tailscale 双通道，自动选路。

Fnh Go 是 [xiazhi88/dshgo](https://github.com/xiazhi88/dshgo)（MIT）的社区分支（fork）。上游提供了「把只监听 `127.0.0.1` 的 `dsh web` 反代到局域网 + Android App」的完整底座；本分支在它之上重做了连接与设备管理、内嵌了 Tailscale 隧道、补齐了深色模式与图标体系。

当前版本：**App `4.5.0-beck.40`（versionCode 82）**、**插件 `2.0.5`**。

## 与上游的差别

| 方向 | 上游 dshgo 4.4.3 | 本分支 |
| --- | --- | --- |
| 设备管理 | 每条地址一张卡 | 一台电脑一张卡：插件上报 `deviceId`，局域网与 Tailscale 两条通道自动归并，卡片只显示当前网络该用的那条，右上角标「N 条通道」 |
| 连接状态 | 在线 / 不在线 | 四分级：可以连接·需要密码登录 / 连不上 / 转发层没就绪 / 在线（含 ms、插件版本）；两条通道都不可用时显示「断联」 |
| 通道切换 | 无 | 自动选路（同一 Wi-Fi 走局域网，其它网络走 Tailscale）+ 手动锁定；顶部常驻「局域网 / Tailscale」标识，换路后地址与标识一起刷新 |
| Tailscale | 无 | 内嵌隧道（`VpnService` + `libtailscale`）：只代理本 App，15 秒看门狗在 tun 丢失时自动重建，状态以 tun 是否存在为准 |
| 解锁 | 锁页 + Cookie | 除锁页外，解锁 Cookie 会在通道间传递（`dshgo-unlock`），换路不再重复输密码；401 响应也带设备身份，未解锁也能归并成同一台设备 |
| 深色模式 | 仅系统跟随 | App 全量 `DshTheme` + `values-night` 窗口底色，WebView 首帧背景随日夜切换，锁页支持 `data-theme`（不依赖 `prefers-color-scheme`） |
| 图标 | Material 默认 | 43 枚自绘图标 + 品牌标记（按源图手描轮廓矢量重建），含 Android 13 主题图标 |
| 返回键 | 直接退出 | DSH → 设备页 → 退出三级；添加/设置页返回上一级 |
| 桌面小组件 | 标题 + 详情 + 允许/拒绝 | 3×2 卡片：工作区切换胶囊、最多 3 条会话（标题 + 摘要 + 上下文占比）、右上账户余额 |
| 数据来源 | 客户端 `session/list` 轮询 | 新增宿主端点 `GET /__dshgo__/widget/state` |
| 包名 | `com.dshgo.app` | `com.beck.dshgo`（可与官方版并存） |
| 签名 | 官方 release 签名 | 自签 `dshgo-release.jks`（keystore 不入库，见 `.gitignore`） |

## 组成

- **DSH 插件**（`lib/`、`client/`）：把 `dsh web` 反代到局域网（默认 `0.0.0.0:3081`，上游端口跟随宿主随机端口），设置里提供「局域网访问」面板（地址 + 二维码 + 访问密码 + 下载 App）。
- **Android App**（`android/`）：WebView 壳 + 通知/审批 + 两个 3×2 小组件 + 折叠屏适配 + 内嵌 Tailscale 隧道。

### 插件端点

| 端点 | 说明 |
| --- | --- |
| `GET /__dshgo__/info` | `{ name, version, listening, port, upstreamPort, updateAvailable, addresses, deviceId, deviceName, addrs:{lan,tail}, upstreamReachable }` |
| `GET /__dshgo__/auth/status` | `{ hasPassword, deviceId, deviceName }` |
| `POST /__dshgo__/unlock` | 访问密码解锁，下发 30 天 Cookie `dshgo-unlock` |

未解锁时：导航请求返回锁页 HTML（支持 `x-fnh-theme` / `fnh_theme` Cookie 指定深浅色），非导航请求返回 `401 {"error":{"code":"locked"},"needAuth":true,"deviceId":…,"deviceName":…}`。

`deviceId` 由 `sha256(主机名|用户名|平台)` 前 12 位派生，不落盘、不需要管理员权限；换主机名或用户名会被视为新设备。

## 安装

插件（本机开发，profile 内 link）：

```bash
dsh plugin --profile desktop add /path/to/fnh-go -w
```

Android：从 Releases 下载 APK 侧载。首次连接：App 首页点右上角「+」，填局域网地址与外网地址（外网选填；填了且能连上，才会开放自动选路开关）。

## 构建

```bash
# 插件侧无构建步骤；改 lib/client 后重启 dsh web
cd android
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
"C:\tools\gradle-8.11.1\bin\gradle.bat" --no-daemon --no-build-cache \
  -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
  :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

需要 JDK 17、Android SDK 35、Gradle 8.11+（仓库自带 wrapper）。release 签名依赖仓库根 `keystore.properties`（不入库）：

```properties
storeFile=keystore/dshgo-release.jks
storePassword=...
keyAlias=dshgo
keyPassword=...
```

`libtailscale.aar`（17 MB）不入库，需要放在 `src/dshgo/libtailscale.aar`（构建脚本按此路径定位）。

## 测试

```bash
node --test test/auth.test.mjs    # 17 项
node --test test/proxy.test.mjs
node --test test/version.test.mjs
```

App 侧真机清单见 `work/` 下的 `TEST-CHECKLIST-beck.39.md`（随每次发版更新）。

## 更新记录

- `beck.39`：小组件行状态直接取事件流的实时运行集合（不再等 8 秒一次的快照，底栏说「1 个在跑」时那一行也会立刻变绿）；被节流合并掉的刷新会排队补跑一次；小组件自己按 TCP 可达性排通道（600ms 探测），不再因为挑了另一条地址而误报「未连接」；底栏精简为「通道 · 状态 · 峰谷」，不再显示具体地址。
- `beck.38`：小组件与常驻通知都认「摘要 / 待完成」两节 —— 会话行显示摘要，常驻通知优先显示「待完成：…」；小组件底栏改成「地址 · 通道 · 状态 · 峰谷」两行；事件流连续失败改指数退避（5s→60s 带抖动），静默 90 秒判定半开连接并主动重连；插件侧新增 `summary`/`todo` 提取（按标题切节，最多 3 条待完成）。
- `beck.37`：隧道看门狗不再看引擎状态（引擎卡在非 Running 时也能重建 VpnService），30 秒节流；`establish()` 返回 null 时把「已有别的 VPN 占用」写进设置页与 Tailscale 页；设备列表只显示当前该用的那条通道，两条都不可用时只写「断联」不显示地址；小组件跟随网络变化强制刷新；常驻通知被清掉后自动补回。
- `beck.36`：应用内更新改用系统 `DownloadManager`（走手机默认网络，第三方 VPN/代理照常生效，断点续传），解决「开着 VPN 时 App 内下载停在 0 字节」；顶部绿点改为按页面连接状态显示；设置页收紧分组间距（去掉重复的 20+22 双层间隔）并统一字号层级。
- `beck.35`：设备页按返回直接退出 App（不再与 DSH 来回跳）；进设备 4 秒没首帧自动补一次导航；设置里「小组件」按设备归并通道，小组件取数失败会自动换另一条通道重试；`establish()` 返回 null 时日志点明「可能已有系统 VPN」。
- `beck.34`：更新检查再补一处 —— jsDelivr 的 tags 列表返回的是不带前导 v 的 `4.5.0-beck.N`，这种写法现在也认（此时 GitHub API 不通也能查到版本）。
- `beck.33`：修「检查不到更新」——`tagToVersion` 之前把 `v4.5.0-beck.33` 截成 `4.5.0`，导致永远判定为最新；更新区新增「在浏览器打开 GitHub 更新页」兜底；设置重排为连接 / 界面 / 通知 / 小组件 / 网络 / 安全 / 诊断 / 更新 / 重置，清除登录与缓存移到底部「重置」组并二次确认。
- `beck.32`：小组件改用「选定设备 + 当前网络」的地址（不再吃 App 上次那个入口地址）、底栏直接写失败原因、设置里新增「小组件」一节可选看哪台电脑、常驻看门狗每分钟催一次刷新。
- `beck.31`：首屏不等探测（探测与查更新挪到首帧之后）、已有解锁 Cookie 时直连跳过锁页；**App 内「检查更新」改为直连 GitHub Releases 的最新资产**（`FnhGo-latest.apk`，不再维护 jsDelivr dist 分支）。
- `beck.30`：从 DSH 回设备页按当前网络重读重探；换路后 700 ms 二次选址（外网→内网不再卡在旧地址）；顶部条按钮真正贴右。
- `beck.29`：解锁 Cookie 跨通道传递（换路不再重复输密码）；从 DSH 返回设备页自动刷新。
- `beck.28`：窗口底色日夜双份（`values-night`），彻底消除指纹解锁与换路时的白屏；换路补加载遮罩。
- `beck.27`：返回键三级（DSH → 设备页 → 退出）；自动换路同步顶部地址与通道标识；锁页与 WebView 深色。
- `beck.26`：设备卡只显示当前通道（含「断联」态）；WebView 首帧背景随日夜切换。
- `beck.25`：R1–R6 主体（隧道自愈与真实状态、401 带设备身份、外网地址校验与选路门禁、设备统一管理、返回退级、工具栏右对齐）。
- `beck.24`：设备合并与通道标签、状态四分级、Tailscale 单入口、换路提示；插件上报 `deviceId` / `addrs`。
- `beck.23`：设置页 Tailscale 收敛为单入口，二级页改 `DshTheme`。
- `beck.22`：43 枚自绘图标 + 品牌标记矢量重建 + 启动器/通知剪影。

## 安全

把 DSH 暴露到局域网等于把「可执行任意命令」的能力交出去：

- 只在可信网络开启；**不要**直接把 3081 端口转发到公网；
- 外网访问请用 Tailscale 等带身份认证的隧道（本分支已内嵌）；
- 强烈建议开启访问密码（设置 → 局域网访问 → 访问密码）。

## 许可与致谢

- 本项目沿用上游 MIT 许可，见 [LICENSE](LICENSE)。
- 上游：[xiazhi88/dshgo](https://github.com/xiazhi88/dshgo)（原作者 xiazhi88）。
- 移动端布局来源于 [mexiaosqwq/dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT），已内联。
- 第三方许可与改动声明见 [NOTICE](NOTICE)。
