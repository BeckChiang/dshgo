/**
 * 主题从哪来：App 的 `X-Fnh-Theme` 头（主动加载时）或 `fnh_theme` cookie。
 *
 * 必须有 cookie 这条：WebView 跟随后端的 303 重定向时不会把自定义头带过去，
 * 只靠 header 会出现「首屏是深色、跳一下就变回浅色」。
 *
 * 这里单独一个模块，是因为锁页（auth.mjs 的 passwordPage）也得知道主题 ——
 * 它由 gate 直接吐 HTML，不走 proxy 的注入，所以必须能拿到同一个判定。
 */
export function resolveTheme(req) {
  const header = String(req.headers['x-fnh-theme'] ?? '').trim();
  if (header === 'dark' || header === 'light') return header;
  const cookie = String(req.headers.cookie ?? '');
  const m = /(?:^|;\s*)fnh_theme=(dark|light)/.exec(cookie);
  return m ? m[1] : '';
}
