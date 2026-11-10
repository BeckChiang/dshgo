/**
 * 这台电脑的设备身份。
 *
 * 手机端要把「同一个 DSH 的两条地址」（家里局域网 / 出门 Tailscale）
 * 认成一台设备，而不是两张卡片。麻烦在于这台电脑**可能还锁着密码**：
 * 没解锁时 `/__dshgo__/info` 直接 401，解锁前拿不到身份 —— 于是身份
 * 必须跟着 401 一起给出去，否则「要密码」那条永远并不到一起。
 *
 * 用「主机名 + 用户名 + 平台」派生：不落盘、不用管理员权限；
 * 改主机名或换用户之后本来就该算新设备。
 */
import { createHash } from 'node:crypto';
import { hostname, platform, userInfo } from 'node:os';

/** 给人看的设备名（主机名）。 */
export function deviceName() {
  try {
    return hostname() || '';
  } catch {
    return '';
  }
}

/** 稳定标识：sha256(主机名|用户名|平台) 取前 12 位。 */
export function deviceIdOf() {
  try {
    return createHash('sha256')
      .update([hostname(), userInfo().username, platform()].join('|'))
      .digest('hex')
      .slice(0, 12);
  } catch {
    return '';
  }
}

/** 塞进 JSON body 的设备字段。 */
export function deviceFields() {
  const id = deviceIdOf();
  return id ? { deviceId: id, deviceName: deviceName() } : {};
}

/**
 * 塞进响应头的设备字段。
 *
 * 只放 id：主机名理论上可以是任意字节，header 里塞非 ASCII 会被 Node 拒绝。
 * 名字走 body。
 */
export function deviceHeaders() {
  const id = deviceIdOf();
  return id ? { 'x-dshgo-device-id': id } : {};
}
