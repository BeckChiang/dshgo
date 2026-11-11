/**
 * 查「手机 App 最新版本」。
 *
 * ## 为什么由服务端查
 *
 * 页面在浏览器里直接请求 GitHub API 会被 CORS 与网络两道坎挡住；宿主本来就是
 * 个 Node 进程，取一次顺手，还能缓存。
 *
 * ## 为什么要有 package.json 兜底
 *
 * GitHub 在国内经常不通（这个项目就踩过整轮）。查不到时不能把卡片变成一句
 * 「未知版本」—— 用发版时写进 `package.json` 的 `dshgo.appVersion/appApk`
 * 当兜底，至少和最近一次发版一致。
 */

const API = 'https://api.github.com/repos/BeckChiang/dshgo/releases/latest';

/** 发版页：永远存在，比 API 稳。 */
export const RELEASES_PAGE = 'https://github.com/BeckChiang/dshgo/releases/latest';

/** 查一次最多等这么久（挂在 info 端点上，不能把页面拖住）。 */
const TIMEOUT_MS = 4000;

/** 多久查一次：发版不是实时数据。 */
const CACHE_MS = 6 * 60 * 60 * 1000;

let cache = { at: 0, value: null };

function fallback(pkg) {
  const conf = pkg?.dshgo ?? {};
  return {
    version: typeof conf.appVersion === 'string' ? conf.appVersion : '',
    apk: typeof conf.appApk === 'string' ? conf.appApk : '',
    releases: RELEASES_PAGE,
    source: 'package',
  };
}

/**
 * 手机 App 的最新版本、下载地址、发版页。
 *
 * @param {object} pkg 本插件 package.json（用于兜底）
 * @returns {Promise<{version: string, apk: string, releases: string, source: string}>}
 */
export async function latestApp(pkg, { now = Date.now() } = {}) {
  if (cache.value && now - cache.at < CACHE_MS) return cache.value;

  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(API, {
      signal: ac.signal,
      headers: { accept: 'application/vnd.github+json', 'user-agent': 'dshgo-plugin' },
    });
    if (res.ok) {
      const body = await res.json();
      const tag = typeof body?.tag_name === 'string' ? body.tag_name : '';
      // tag 形如 v4.5.0-beck.52；去掉前导 v 就是版本号
      const version = tag.replace(/^v/, '');
      const assets = Array.isArray(body?.assets) ? body.assets : [];
      const apk = assets.find((a) => typeof a?.name === 'string' && a.name.endsWith('.apk'));
      const value = {
        version,
        apk: typeof apk?.browser_download_url === 'string' ? apk.browser_download_url : '',
        releases: RELEASES_PAGE,
        source: 'github',
      };
      if (value.version) {
        cache = { at: now, value };
        return value;
      }
    }
  } catch {
    // 查不到不是错误，走兜底
  } finally {
    clearTimeout(timer);
  }

  // 失败：保留上一次成功结果；没有就用 package.json
  if (cache.value) {
    cache = { at: now, value: cache.value };
    return cache.value;
  }
  const value = fallback(pkg);
  cache = { at: now, value };
  return value;
}

/** 测试用：清缓存。 */
export function resetAppCache() {
  cache = { at: 0, value: null };
}
