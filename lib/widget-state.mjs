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
import { WIDGET_STATE_PATH } from './proxy.mjs';

const DEFAULT_SESSION_LIMIT = 5;
const MAX_SESSION_LIMIT = 20;
const MAX_SCHEDULES = 20;
/** 非活跃会话的上下文占比要读一次日志，缓存两分钟，避免手机每次刷新都重读。 */
const CONTEXT_TTL_MS = 120_000;
const contextCache = new Map();

/** 统一路径比较：大小写、斜杠方向、结尾斜杠都不该造成「同一个工作区」被判成两个。 */
function normalizePath(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  let text = value.trim().replace(/\//g, '\\').replace(/\\+$/, '');
  if (/^[a-zA-Z]:/.test(text)) text = text[0].toUpperCase() + text.slice(1);
  return text.toLowerCase();
}

function clampLimit(raw) {
  const value = Number.parseInt(String(raw ?? ''), 10);
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
function pricingNow(now = new Date()) {
  const bj = new Date(now.getTime() + 8 * 3600 * 1000);
  const minutes = bj.getUTCHours() * 60 + bj.getUTCMinutes();
  const offPeak = minutes >= 30 && minutes < 510;
  return {
    peak: !offPeak,
    label: offPeak ? '低峰' : '高峰',
    window: '00:30–08:30',
    detail: offPeak ? '低峰 · 00:30–08:30 · 5 折' : '高峰 · 低峰 00:30–08:30',
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

/** 会话摘要：截到 90 字符，超长补省略号。 */
function clipSummary(text) {
  const clean = String(text ?? '').replace(/\s+/g, ' ').trim();
  if (clean === '') return null;
  return clean.length > 90 ? `${clean.slice(0, 90)}…` : clean;
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
    return { status, contextPercent, summary };
  }

  // 非活跃会话：只观察一份日志，从事件里推上下文占比（不整份重放，不依赖投影是否存在）。
  const cached = contextCache.get(sessionId);
  if (cached && Date.now() - cached.at < CONTEXT_TTL_MS) {
    return { status, contextPercent: cached.percent, summary: cached.summary ?? null };
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
        if (summary !== null && pressure !== null && window !== null) break;
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
    const bucket = grouped.get(ws.id);
    if (bucket) bucket.push(record);
  }

  // 3) 每个工作区取前 N 条会话，补标题 / 状态 / 上下文占比
  //    排序口径与侧边栏一致：置顶在前，其余按创建时间从新到旧。
  const sessionTitles = new Map();
  const probe = [];
  const isPinned = (record) => Boolean(record?.pinned ?? record?.header?.pinned ?? record?.pinnedAt);
  const byRecency = (a, b) => {
    const pin = Number(isPinned(b)) - Number(isPinned(a));
    if (pin !== 0) return pin;
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
        summary: metrics.summary ?? null,
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
    pricingLabel: pricing.detail,
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
