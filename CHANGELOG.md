# 更新记录

插件（`@xiazhi88/dshgo`）与 App（`Fnh Go`）**各自独立编号**：

- 插件版本 = 本文件与 `package.json` 的 `version`，只跟宿主侧代码走；
- App 版本 = `4.5.0-beck.N`，只跟 Android 侧代码走。

两者互不约束：插件改了不一定要出 App，App 改了也不一定要动插件。
App 在设备列表/设备详情里显示的 `vX.Y.Z` 就是这里读到的插件版本（`/__dshgo__/info` 的 `version`）。

## 2.1.1

- 设置面板的「手机 App」卡片不再写死版本号与下载地址：版本 / 下载链接 / 发版页改为服务端实时查 GitHub Releases（`lib/app-release.mjs`，6 小时缓存，失败回退 `package.json` 的 `dshgo.appVersion/appApk`）。
- 面板同时显示「插件版本」和「手机 App 最新版本」，下载按钮指向 `FnhGo-latest.apk`（旧链接指向已改名的 `fnh-go.apk`，一直是 404）。
- 面板「运行状态」卡片把单行「版本」拆成「插件版本」与「手机 App 最新」两行。

## 2.1.0 — 2026-11-11

与 App `4.5.0-beck.26` 起的一轮配合改动，宿主侧全部内容：

- **设备身份**：`/__dshgo__/info` 与 `/__dshgo__/auth/status` 增加 `deviceId`、`deviceName`；
  未解锁时的 401 响应（body 与 `x-dshgo-device-id` 头）也带上，App 才能把同一台电脑的
  局域网/外网两条地址并成一张卡。`deviceId = sha256(主机名|用户名|平台)` 前 12 位。
- **地址分组**：`addrs: { lan: [], tail: [] }`，客户端不用自己再猜哪个是 `100.x`。
- **转发层存活**：`upstreamReachable`，区分「电脑能连上但 dsh web 没起来」与「整个连不上」。
- **`/__dshgo__/info` 进白名单**：锁着时也能读到设备身份与地址，否则「局域网地址自动跟随」
  永远触发不了。
- **锁页主题**：`resolveTheme(req)` 读 `x-fnh-theme` 头或 `fnh_theme` cookie，锁页输出
  `data-theme="dark|light"`；Android WebView 不把系统深色透成 `prefers-color-scheme`，
  所以必须走显式通道。`lib/theme.mjs` 同时被 `gate.mjs` 与 `proxy.mjs` 复用。
- **小组件会话顺序**：以宿主 `listSessions()` 的返回顺序为基准（`baseIndex` 做 tiebreak，
  不再用 `createdAt`），在其上稳定分组「运行中且置顶 → 运行中 → 其余」，先分组再截断；
  候选窗口从 `limit*4` 放宽到 `limit*6`，避免刚在电脑端冒出来的会话被截掉。
- **会话摘要/待完成**：从事件文本里切「摘要」「待完成」两段（`splitSections`），清洗 markdown
  与消息引用后随会话下发 `summary`/`todo`。

## 2.0.5 — 2026-11-07

- 上游基线版本：把只监听 `127.0.0.1` 的 dsh web 转发到局域网，附移动端适配。
