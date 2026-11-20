// 小组件数据端点的行为测试：重点是「哪些会话该出现在首页」。
//
// 跑法：node test/widget-state.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { buildWidgetState, isInternalSession } from '../lib/widget-state.mjs';

const HOME = 'C:\\Users\\recko\\DSH';

/** 造一条 sessionQuery.listSessions() 的记录。 */
function record(id, title, extra = {}) {
  return {
    header: { id, cwd: HOME, createdAt: 1_700_000_000_000, ...extra.header },
    ...extra,
    __title: title,
  };
}

/**
 * 最小可用的服务桩：一个工作区、若干会话、标题快照。
 * 没有 agents / tokenMeter 时 sessionMetrics 会走 `sessionQuery.observeSession`
 * 不存在的分支，直接返回 idle，不额外读日志。
 */
function services(records) {
  return {
    workspaceRegistry: {
      list: async () => [{ id: 'ws-1', title: 'DSH', path: HOME }],
      resolveByPath: async () => null,
    },
    sessionQuery: {
      listSessions: async () => records,
      readTitleSnapshots: async (ids) =>
        ids.map((id) => {
          const hit = records.find((r) => r.header.id === id);
          return { sessionId: id, status: 'fulfilled', value: { title: { title: hit?.__title ?? '' } } };
        }),
    },
  };
}

test('内部会话前缀识别', () => {
  assert.equal(isInternalSession('mnemon-review-v1-0f62f101-4560-4317-a2b5-530bf461fb2b'), true);
  assert.equal(isInternalSession('dsh-mnemon-review'), false, '只是含关键字，不以前缀开头就不算');
  assert.equal(isInternalSession('9f2c1a4e-1111-2222-3333-444455556666'), false);
  assert.equal(isInternalSession(''), false);
  assert.equal(isInternalSession(null), false);
});

test('小组件不显示插件后台会话，真实对话照常显示', async () => {
  const records = [
    record('mnemon-review-v1-aaa', 'Mnemon · idle review'),
    record('s-real-1', '检查 dshops 插件功能'),
    record('mnemon-review-v1-bbb', 'Mnemon · idle review'),
    record('s-real-2', 'Fnh Go 小组件改版'),
  ];

  const state = await buildWidgetState(services(records), { sessionLimit: 5 });
  const ws = state.workspaces.find((item) => item.id === 'ws-1');
  assert.ok(ws, '工作区应该存在');

  const ids = ws.sessions.map((item) => item.id);
  assert.deepEqual(ids, ['s-real-1', 's-real-2'], '后台会话必须被滤掉，顺序保持原样');
  assert.equal(ws.sessionCount, 2, '计数也要按过滤后的算');
});

test('全部都是后台会话时，工作区里是空列表而不是报错', async () => {
  const records = [
    record('mnemon-review-v1-ccc', 'Mnemon · idle review'),
    record('mnemon-review-v1-ddd', 'Mnemon · idle review'),
  ];

  const state = await buildWidgetState(services(records), { sessionLimit: 5 });
  const ws = state.workspaces.find((item) => item.id === 'ws-1');
  assert.deepEqual(ws.sessions, []);
  assert.equal(ws.sessionCount, 0);
});
