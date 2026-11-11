/**
 * 从一条回答里切出「摘要」和「待完成」两节。
 * 约定：`摘要：…`（小组件显示）与 `待完成：1. …`（通知显示）；
 * 缺这两节时退回老行为：第一段当摘要。
 */
const HEAD_WORDS = [
  ['summary', ['摘要', 'Summary', '**摘要**', '## 摘要']],
  ['todo', ['待完成', '待办', 'TODO', '**待完成**', '## 待完成']],
];
function headKind(line) {
  const t = line.trim();
  for (const [kind, words] of HEAD_WORDS) {
    for (const w of words) {
      if (t === w) return { kind, inline: '' };
      if (t.startsWith(w + '：') || t.startsWith(w + ':')) {
        return { kind, inline: t.slice(w.length + 1).trim() };
      }
      if (w.startsWith('**') && t.startsWith(w)) {
        const rest = t.slice(w.length).replace(/^[：:]\s*/, '').trim();
        return { kind, inline: rest };
      }
    }
  }
  const capped = t.replace(/^#{1,6}\s*/, '');
  if (capped && capped !== t) {
    if (capped.startsWith('摘要')) return { kind: 'summary', inline: capped.slice(2).replace(/^[：:]\s*/, '') };
    if (capped.startsWith('待完成')) return { kind: 'todo', inline: capped.slice(3).replace(/^[：:]\s*/, '') };
    if (capped.startsWith('正文')) return { kind: 'body', inline: '' };
  }
  return null;
}
/** 一节的边界：空行、分隔线、或者另一个像标题的行。 */
function isSectionBreak(line) {
  const t = line.trim();
  if (t === '') return true;
  if (/^-{3,}$/.test(t)) return true;
  if (/^#{1,6}\s/.test(t)) return true;
  if (/^\*\*[^*]+\*\*\s*$/.test(t)) return true;
  if (/^\*\*[^*]+\*\*\s*[：:]/.test(t)) return true;
  return false;
}

function splitSections(text) {
  const raw = String(text ?? '');
  if (raw.trim() === '') return { summary: null, todo: [] };
  const lines = raw.split(/\r?\n/);
  const buckets = { summary: [], todo: [] };
  let mode = null;
  for (let i = 0; i < lines.length; i += 1) {
    const head = headKind(lines[i]);
    if (head) {
      mode = head.kind === 'body' ? null : head.kind;
      if (mode && head.inline) buckets[mode].push(head.inline);
      continue;
    }
    if (!mode) continue;
    if (isSectionBreak(lines[i])) {
      // 摘要只取紧跟着的一两行；遇到空行就收工，等下一个标题。
      if (mode === 'summary' && buckets.summary.length > 0) mode = null;
      continue;
    }
    buckets[mode].push(lines[i].trim());
  }
  const clean = (arr) => cleanMarkdown(arr.join('\n')).replace(/\s+/g, ' ').trim();
  let summary = clean(buckets.summary);
  if (summary === '') summary = cleanMarkdown(raw).split(/\n\s*\n/)[0].replace(/\s+/g, ' ').trim();
  const todoText = cleanMarkdown(buckets.todo).trim();
  const todo = todoText === ''
    ? []
    : todoText.split(/(?<=\S)\s*(?=\d+[.、)])/).map((s) => s.trim()).filter(Boolean).slice(0, 3);
  return { summary: summary === '' ? null : summary, todo };
}
