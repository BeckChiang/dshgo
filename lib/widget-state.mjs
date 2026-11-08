/**
 * 小组件数据端点：把「工作区 / 会话 / 上下文占比 / 定时任务 / 账户余额」聚合成一份 JSON，
 * 供 Android 两个 3×2 组件（会话组件、定时组件）拉取。
 *
 * 设计要点：
 * - 所有数据源都通过 Cordis 硬依赖注入拿到（见 index.js 的 ctx.inject），未注入的服务取不到。
 * - 每个数据源独立 try/catch，单个失败只让对应字段为 null，并把原因写进 sources，
 *   绝不因为「余额登录态过期」就让整个端点 500。
 * - 会话与工作区的归属靠 cwd 反查（SessionHeader 没有 workspaceId）。
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WIDGET_STATE_PATH } from './proxy.mjs';

const DEFAULT_SESSION_LIMIT = 5;
const MAX_SESSION_LIMIT = 20;
const MAX_SCHEDULES = 20;
/** 非活跃会话的上下文占比要读一次日志，缓存两分钟，避免手机每次刷新都重读。 */
const CONTEXT_TTL_MS = 120_000;
const contextCache = new Map();

/**
 * 置顶 / 归档不在 workspaceRegistry 的公开接口里（那个服务只有 pinSession/unpinSession），
 * 但 DSH 自己的 workspace 存储文件里有：$DSH_HOME/storages/workspace.json 的
 * global.pinnedSessionIds（最近置顶的排最前）与 global.archivedSessionIds。
 * 按 mtime+size 缓存，文件没变就不重新解析；解析失败沿用上一份，别让小组件因此空掉。
 */
let workspaceMetaCache = { key: '', pinnedOrder: new Map(), archived: new Set() };

function workspaceStoragePath() {
  const home = process.env.DSH_HOME ?? path.join(os.homedir(), '.dsh');
  return path.join(home, 'storages', 'workspace.json');
}

function readWorkspaceMeta() {
  const file = workspaceStoragePath();
  let stat;
  try {
    stat = fs.statSync(file);
  } catch {
    return workspaceMetaCache;
  }
  const key = `${stat.mtimeMs}:${stat.size}`;
  if (key === workspaceMetaCache.key) return workspaceMetaCache;
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    const global = parsed?.global ?? {};
    const pinned = Array.isArray(global.pinnedSessionIds) ? global.pinnedSessionIds.map(String) : [];
    const archived = Array.isArray(global.archivedSessionIds) ? global.archivedSessionIds.map(String) : [];
    workspaceMetaCache = {
      key,
      pinnedOrder: new Map(pinned.map((id, index) => [id, index])),
      archived: new Set(archived),
    };
  } catch {
    /* 读失败沿用上一份缓存 */
  }
  return workspaceMetaCache;
}

/** 统一路径比较：大小写、斜杠方向、结尾斜杠都不该造成「同一个工作区」被判成两个。 */function normalizePath(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  let text = value.trim().replace(/\//g, '\\').replace(/\\+$/, '');
  if (/^[a-zA-Z]:/.test(text)) text = text[0].toUpperCase() + text.slice(1);
  return text.toLowerCase();
}

function clampLimit(raw) {  const value = Number.parseInt(String(raw ?? ''), 10);
  if (!Number.isFinite(value) || value <= 0) return DEFAULT_SESSION_LIMIT;
  return Math.min(value, MAX_SESSION_LIMIT);
}

/** 结构化失败：让 sources 里留下「哪个源坏了、为什么」。 */
function fail(sources, key, err) {
  const message = String(err?.message ?? err ?? 'unknown');
  sources[key] = `error: ${message}`;
  return null;
}

/**
 * DeepSeek 的高峰/低峰时段（北京时间）：低峰 00:30–08:30，其余为高峰。
 * 只用于展示，不参与计费；算错最多是小字写错，所以不引时区库。
 */
/**
 * 高峰 / 空闲。
 *
 * 口径跟本机 cost-meter 插件（`plugins/cost-meter/client.js` 的 `beijingPeak`）一致：
 * 工作日 09:00–12:00、14:00–18:00 是高峰，其余时间与周末全天是空闲。
 * 之前按「低峰 00:30–08:30」算，晚上 18 点后还会显示高峰，是错的。
 */
function pricingNow(now = new Date()) {
  const bj = new Date(now.getTime() + 8 * 3600 * 1000);
  const minutes = bj.getUTCHours() * 60 + bj.getUTCMinutes();
  const weekday = bj.getUTCDay(); // 0 = 周日
  const isWeekend = weekday === 0 || weekday === 6;
  const peak = !isWeekend && ((minutes >= 540 && minutes < 720) || (minutes >= 840 && minutes < 1080));
  return {
    peak,
    label: peak ? '高峰' : '空闲',
    window: '工作日 09:00–12:00 / 14:00–18:00',
    detail: peak ? '高峰 · 工作日 09:00–12:00 / 14:00–18:00' : '空闲 · 工作日 09:00–12:00 / 14:00–18:00',
  };
}

export function pickBalance(detail) {
  if (!detail || detail.status !== 'ready') return null;
  const wallets = Array.isArray(detail.value) ? detail.value : [];
  const wallet = wallets.find((item) => item?.currency === 'CNY') ?? wallets[0];
  if (!wallet) return null;
  const amount = Number.parseFloat(String(wallet.balance));
  if (!Number.isFinite(amount)) return null;
  return { amount, currency: wallet.currency };
}

/** 单条消息取纯文本：兼容 content 为字符串 / 文本块数组 / 直接 text 三种形态。 */
function messageText(message) {
  if (!message) return '';
  const raw = message.content ?? message.text ?? '';
  let text = '';
  if (typeof raw === 'string') text = raw;
  else if (Array.isArray(raw)) {
    text = raw
      .map((block) => (typeof block === 'string' ? block : block?.text ?? ''))
      .filter((part) => typeof part === 'string' && part !== '')
      .join(' ');
  }
  return text.replace(/\s+/g, ' ').trim();
}

/** 取第一句：按中文/英文句末标点切，最多 80 字；首句太短就顺延到第二句。 */
function firstSentence(text) {
  const cut = (input) => {
    const m = /^(.{0,80}?)[。！？!?]/.exec(input);
    return m ? m[1].trim() : input.trim();
  };
  const head = cut(text);
  if (head.length >= 8) return head;
  // 「好的。」「收到」这类没有信息量，接上第二句
  const rest = text.slice(head.length).replace(/^[。！？!?\s]+/, '');
  if (rest === '') return head;
  return `${head}，${cut(rest)}`;
}

/** 会话摘要：清掉 markdown 噪音，取用户真正看到的第一句，最多 40 字。 */
function clipSummary(text) {
  const clean = String(text ?? '')
    .replace(/```[\s\S]*?```/g, ' ')      // 代码块
    .replace(/`([^`]*)`/g, '$1')          // 行内代码
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ') // 图片
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1') // 链接留文字
    .replace(/^#{1,6}\s*/gm, '')          // 标题符号
    .replace(/^\s*[-*+]\s+/gm, '')        // 列表符号
    .replace(/[*_~]{1,3}/g, '')           // 强调符号
    .replace(/\s+/g, ' ')
    .trim();
  if (clean === '') return null;
  const head = firstSentence(clean);
  return head.length > 40 ? `${head.slice(0, 40)}…` : head;
}

/**
 * 一条会话的运行状态与上下文占比。
 * 活着的会话走 agents + tokenMeter（最准）；已落盘的会话退一步用 observeSession 的投影，
 * 投影里没有就返回 null —— 不为了一个百分比去整份重放日志。
 */
async function sessionMetrics({ sessionQuery, tokenMeter, agents, probe }, sessionId) {
  let status = 'idle';
  let contextPercent = null;
  let summary = null;

  const agent = agents?.get?.(sessionId);
  if (agent) {
    status = 'live';
    try {
      const session = agent.session;
      if (session) {
        const boundary = agents.turnBoundaryOf(session);
        status = boundary?.openTurnStartSeq === null || boundary?.openTurnStartSeq === undefined
          ? 'idle'
          : 'running';
        if (typeof tokenMeter?.contextPressure === 'function') {
          const pressure = tokenMeter.contextPressure(session);
          const used = Number(pressure?.pressureTokens);
          const total = Number(pressure?.contextWindow);
          if (Number.isFinite(used) && Number.isFinite(total) && total > 0) {
            contextPercent = Math.round((used / total) * 100);
          }
        }
      }
    } catch {
      status = 'live';
    }
    // 注意：这里**不能**直接 return —— 运行中的会话也要摘要。
    // 之前的写法在 live 分支提前返回，导致「从小组件点进对话后摘要整行消失」。
  }

  // 非活跃会话：只观察一份日志，从事件里推上下文占比（不整份重放，不依赖投影是否存在）。
  const cached = contextCache.get(sessionId);
  if (cached && Date.now() - cached.at < CONTEXT_TTL_MS) {
    return {
      status,
      contextPercent: contextPercent ?? cached.percent,
      summary: cached.summary ?? null,
    };
  }

  try {
    if (typeof sessionQuery?.observeSession !== 'function') return { status, contextPercent, summary };
    const observation = await sessionQuery.observeSession(sessionId, { projectionMode: 'all' });
    try {
      const values = observation?.projections?.values ?? {};
      if (probe && probe.length === 0) probe.push(...Object.keys(values));

      // 投影里就有 contextPressure 时直接用，省掉一遍事件扫描。
      const projected = values?.contextPressure ?? values?.tokenUsage;
      if (projected) {
        const used = Number(projected.pressureTokens ?? projected.inputTokens);
        const total = Number(projected.contextWindow);
        if (Number.isFinite(used) && Number.isFinite(total) && total > 0) {
          contextPercent = Math.round((used / total) * 100);
        }
      }

      // 事件列表只扫一遍：末尾往前找「最后一条能读的文本」当摘要，
      // 顺便补 pressure / window（投影里没有时才需要）。
      const events = Array.isArray(observation?.events) ? observation.events : [];
      let pressure = null;
      let window = null;
      for (let i = events.length - 1; i >= 0; i -= 1) {
        const event = events[i];
        if (summary === null) {
          const text = messageText(event?.data?.message);
          if (text !== '') summary = clipSummary(text);
        }
        if (pressure === null && event?.type === 'assistant/message' && event.data?.usage) {
          const used = Number(event.data.usage.inputTokens);
          if (Number.isFinite(used) && used > 0) pressure = used;
        }
        if (window === null && event?.type === 'request/context') {
          const size = Number(event.data?.contextWindow);
          if (Number.isFinite(size) && size > 0) window = size;
        }
        if (summary !== null && (contextPercent !== null || (pressure !== null && window !== null))) break;
      }
      if (contextPercent === null && pressure !== null && window !== null) {
        contextPercent = Math.round((pressure / window) * 100);
      }
    } finally {
      observation?.dispose?.();
    }
  } catch {
    // 忽略：观察失败就是没有百分比
  }
  if (contextPercent !== null || summary !== null) {
    if (contextCache.size > 400) contextCache.clear();
    contextCache.set(sessionId, {
      percent: contextPercent ?? contextCache.get(sessionId)?.percent ?? null,
      summary,
      at: Date.now(),
    });
  }
  return { status, contextPercent, summary };
}

/**
 * 聚合小组件状态。
 * @param {object} services 已注入 Cordis 服务的 ctx（workspaceRegistry / sessionQuery / tokenMeter / agents / schedule / deepseekAccount）
 * @param {{sessionLimit?: number}} options
 */
export async function buildWidgetState(services, options = {}) {
  const {
    workspaceRegistry,
    sessionQuery,
    tokenMeter,
    agents,
    schedule,
    deepseekAccount,
  } = services ?? {};

  const limit = clampLimit(options.sessionLimit);
  const sources = {};
  // 高峰/低峰只在展示层用；每请求重算一次，跨时段自然就对了。
  const pricing = pricingNow();

  // 1) 工作区（同步返回）
  let workspaces = [];
  try {
    const list = await workspaceRegistry?.list?.();
    workspaces = (Array.isArray(list) ? list : []).map((ws) => ({
      id: String(ws?.id ?? ws?.workspaceId ?? ''),
      title: String(ws?.title ?? '未命名工作区'),
      path: String(ws?.path ?? ''),
      sessionCount: 0,
      sessions: [],
    }));
    sources.workspaces = workspaces.length > 0 ? 'ok' : 'empty';
  } catch (err) {
    workspaces = [];
    fail(sources, 'workspaces', err);
  }

  // 2) 会话（完整语料，newest-first），再按 cwd 归到工作区
  let records = [];
  try {
    if (typeof sessionQuery?.listSessions !== 'function') throw new Error('sessionQuery.listSessions 不可用');
    const list = await sessionQuery.listSessions();
    records = Array.isArray(list) ? list : [];
    sources.sessions = records.length > 0 ? 'ok' : 'empty';
  } catch (err) {
    records = [];
    fail(sources, 'sessions', err);
  }

  const byPath = new Map();
  for (const ws of workspaces) {
    const key = normalizePath(ws.path);
    if (key) byPath.set(key, ws);
  }

  const resolvedCache = new Map();
  async function workspaceForCwd(cwd) {
    const key = normalizePath(cwd);
    if (!key) return null;
    if (byPath.has(key)) return byPath.get(key);
    if (resolvedCache.has(key)) return resolvedCache.get(key);
    let found = null;
    try {
      const ws = await workspaceRegistry?.resolveByPath?.(cwd);
      if (ws) {
        const id = String(ws.id ?? ws.workspaceId ?? '');
        found = workspaces.find((item) => item.id === id) ?? null;
        if (found) byPath.set(key, found);
      }
    } catch {
      found = null;
    }
    resolvedCache.set(key, found);
    return found;
  }

  const grouped = new Map(workspaces.map((ws) => [ws.id, []]));
  for (const record of records) {
    const header = record?.header;
    // 子代理会话 / 预置会话不是用户眼里的「对话」，混进来就是一堆看不懂的标题。
    if (!header) continue;
    if (header.isSeeded) continue;
    if (header.parentSession) continue;
    if (Number(header.delegationDepth ?? 0) > 0) continue;
    const ws = await workspaceForCwd(header.cwd);
    if (!ws) continue;
    // 已归档的会话在侧边栏里也是收起的，小组件不显示。
    if (readWorkspaceMeta().archived.has(String(header.id))) continue;
    const bucket = grouped.get(ws.id);
    if (bucket) bucket.push(record);
  }

  // 3) 每个工作区取前 N 条会话，补标题 / 状态 / 上下文占比
  //    排序口径与侧边栏一致：置顶在前，其余按创建时间从新到旧。
  const sessionTitles = new Map();
  const probe = [];
  const isPinned = (record) => Boolean(record?.pinned ?? record?.header?.pinned ?? record?.pinnedAt);
  // 置顶顺序取 DSH 自己的存储文件（workspaceRegistry 没有读 pin 的接口）：
  // global.pinnedSessionIds 里最近置顶的排最前，与侧边栏一致。
  const meta = readWorkspaceMeta();
  const pinnedOrder = meta.pinnedOrder;

  // 「正在跑」= 有 live agent 的会话，口径与 sessionMetrics 里判 running 一致。
  // 「有 agent」不等于「在跑」：agents.get() 只说明会话被加载着，
  // 真正的运行态要看 turnBoundaryOf(session).openTurnStartSeq 是否为 null
  // （和 sessionMetrics 里判 running 用的是同一个判据）。
  const isRunningAgent = (agent) => {
    try {
      const session = agent?.session;
      if (!session) return false;
      const boundary = agents?.turnBoundaryOf?.(session);
      return boundary ? boundary.openTurnStartSeq !== null : false;
    } catch {
      return false;
    }
  };
  const liveIds = new Set();
  try {
    for (const agent of agents?.list?.() ?? []) {
      if (!isRunningAgent(agent)) continue;
      const id = String(agent?.id ?? agent?.session?.header?.id ?? '');
      if (id !== '') liveIds.add(id);
    }
  } catch {
    /* 拿不到就当没有在跑的会话 */
  }

  // 用户要的顺序：正在跑且置顶 > 正在跑 > 置顶 > 一般。
  const rankOf = (record) => {
    const id = String(record?.header?.id ?? '');
    const pinned = pinnedOrder.has(id) || isPinned(record);
    // agents.list() 在某些宿主实现里可能拿不到，兜底再问一次 agents.get(id)，
    // 保证「正在跑」的判定和 sessionMetrics 用同一口径。
    let running = liveIds.has(id);
    if (!running) {
      try {
        running = isRunningAgent(agents?.get?.(id));
      } catch {
        running = false;
      }
    }
    if (running && pinned) return 3;
    if (running) return 2;
    if (pinned) return 1;
    return 0;
  };
  const byRecency = (a, b) => {
    const rank = rankOf(b) - rankOf(a);
    if (rank !== 0) return rank;
    const ai = pinnedOrder.get(String(a?.header?.id ?? ''));
    const bi = pinnedOrder.get(String(b?.header?.id ?? ''));
    if (ai !== undefined && bi !== undefined && ai !== bi) return ai - bi;
    return Number(b?.header?.createdAt ?? 0) - Number(a?.header?.createdAt ?? 0);
  };
  for (const ws of workspaces) {
    const bucket = (grouped.get(ws.id) ?? []).sort(byRecency);
    ws.sessionCount = bucket.length;
    // 先多取一批再按「有标题」过滤，避免把没有标题的空会话顶到列表最前面。
    const candidates = bucket.slice(0, Math.max(limit * 4, 12));

    let titles = new Map();
    try {
      if (candidates.length > 0 && typeof sessionQuery?.readTitleSnapshots === 'function') {
        const results = await sessionQuery.readTitleSnapshots(candidates.map((item) => item.header.id));
        for (const result of Array.isArray(results) ? results : []) {
          if (result?.status === 'fulfilled') {
            const title = result.value?.title?.title;
            if (typeof title === 'string' && title.trim() !== '') titles.set(result.sessionId, title.trim());
          }
        }
        for (const [id, title] of titles) sessionTitles.set(id, title);
        sources.titles = 'ok';
      }
    } catch (err) {
      titles = new Map();
      fail(sources, 'titles', err);
    }

    // 只保留有标题的会话：没有标题的通常是刚建出来、还没说一句话的空会话，
    // 显示成「未命名会话」只会让人以为数据错了。
    const shown = candidates.filter((item) => titles.has(String(item.header.id))).slice(0, limit);

    ws.sessions = [];
    for (const record of shown) {
      const id = String(record.header.id);
      const metrics = await sessionMetrics({ sessionQuery, tokenMeter, agents, probe }, id);
      ws.sessions.push({
        id,
        title: titles.get(id) ?? '未命名会话',
        summary: metrics.status === 'running' || metrics.status === 'live' ? '运行中' : metrics.summary ?? null,
        status: metrics.status,
        persisted: Boolean(record.persisted),
        createdAt: Number(record.header.createdAt ?? 0) || null,
        contextPercent: metrics.contextPercent,
      });
    }
  }

  if (records.length > 0 && sources.context === undefined) {
    sources.context = agents ? 'ok' : 'agents unavailable';
  }

  // 4) 定时任务：catalog 拿全量，按 target 的 sessionId / create.cwd 归到工作区
  let schedules = [];
  try {
    if (typeof schedule?.catalog !== 'function') throw new Error('schedule.catalog 不可用');
    const list = await schedule.catalog();
    const recordById = new Map(records.map((item) => [String(item.header.id), item]));
    const items = Array.isArray(list) ? list : [];

    // 定时任务指向的会话常常不在「工作区前几条」里，单独补一次标题
    const missing = [...new Set(
      items.map((item) => String(item?.target?.sessionId ?? '')).filter((id) => id && !sessionTitles.has(id)),
    )].slice(0, MAX_SCHEDULES);
    if (missing.length > 0 && typeof sessionQuery?.readTitleSnapshots === 'function') {
      const results = await sessionQuery.readTitleSnapshots(missing);
      for (const result of Array.isArray(results) ? results : []) {
        if (result?.status === 'fulfilled') {
          const title = result.value?.title?.title;
          if (typeof title === 'string' && title.trim() !== '') sessionTitles.set(result.sessionId, title.trim());
        }
      }
    }
    schedules = [];
    for (const item of items) {
      if (schedules.length >= MAX_SCHEDULES) break;
      const target = item?.target;
      const sessionId = String(target?.sessionId ?? item?.involvedSessionIds?.[0] ?? '');
      const cwd = target?.create?.cwd ?? recordById.get(sessionId)?.header?.cwd;
      const ws = cwd ? await workspaceForCwd(cwd) : null;
      const session = sessionId ? recordById.get(sessionId) : null;
      schedules.push({
        id: String(item?.id ?? ''),
        title: String(item?.title ?? '未命名任务'),
        status: String(item?.status ?? 'unknown'),
        kind: String(item?.kind ?? target?.kind ?? ''),
        scheduledAt: typeof item?.scheduledAt === 'string' ? item.scheduledAt : null,
        workspaceId: ws?.id ?? null,
        workspaceTitle: ws?.title ?? '',
        sessionId: sessionId || null,
        sessionTitle: sessionId ? (sessionTitles.get(sessionId) ?? '') : '',
      });
    }
    sources.schedule = items.length > 0 ? 'ok' : 'empty';
  } catch (err) {
    schedules = [];
    fail(sources, 'schedule', err);
  }

  // 5) 账户余额（host 侧直接调，不需要浏览器上下文）
  let balance = null;
  try {
    if (typeof deepseekAccount?.getBalance !== 'function') throw new Error('deepseekAccount.getBalance 不可用');
    const detail = await deepseekAccount.getBalance({
      version: '4.4.3-dshgo',
      locale: 'zh-CN',
      timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
    });
    balance = pickBalance(detail);
    sources.balance = balance ? 'ok' : 'signed-out';
  } catch (err) {
    balance = null;
    fail(sources, 'balance', err);
  }

  return {
    workspaces,
    schedules,
    balance,
    updatedAt: new Date().toISOString(),
    pricing,
    pricingLabel: pricing.label,
    sources,
    projectionKeys: probe,
  };
}

/**
 * 注册小组件数据端点。挂在宿主 webServer 上，局域网代理会把它一起转出去，
 * 所以手机经 3081 也能直接拉到同一份 JSON。
 */
export function registerWidgetState(ctx, { info = () => {} } = {}) {
  const handler = async (req, res) => {
    let payload;
    try {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      payload = await buildWidgetState(ctx, {
        sessionLimit: clampLimit(url.searchParams.get('sessions')),
      });
    } catch (err) {
      payload = {
        error: { code: 'widget-state-failed', message: String(err?.message ?? err) },
        workspaces: [],
        schedules: [],
        balance: null,
      };
    }
    const text = JSON.stringify(payload);
    res.writeHead(200, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(text),
      'cache-control': 'no-store',
    });
    res.end(text);
    info(`dshgo: 小组件状态 ${WIDGET_STATE_PATH} 被读取`);
  };

  return ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: WIDGET_STATE_PATH,
    handler,
  }), 'dshgo: 小组件数据端点');
}
