/**
 * 令牌存放策略：
 * - accessToken：sessionStorage（仅当前标签页，关闭即失效）
 * - rememberToken：localStorage（勾选“记住密码”时；服务端可撤销、每次使用即轮换）
 * - username：localStorage（用于回填账号）
 * 任何情况下都不会在浏览器保存明文密码。
 */
const ACCESS_KEY = 'srs.accessToken'
export const REMEMBER_KEY = 'srs.rememberToken'
const USERNAME_KEY = 'srs.username'

function safeGet(storage: Storage, key: string): string | null {
  try {
    return storage.getItem(key)
  } catch {
    return null
  }
}

function safeSet(storage: Storage, key: string, value: string | null): void {
  try {
    if (value === null) storage.removeItem(key)
    else storage.setItem(key, value)
  } catch {
    /* 隐私模式等场景写入失败时静默忽略 */
  }
}

export const tokenStore = {
  getAccess: () => safeGet(sessionStorage, ACCESS_KEY),
  setAccess: (value: string | null) => safeSet(sessionStorage, ACCESS_KEY, value),

  getRemember: () => safeGet(localStorage, REMEMBER_KEY),
  setRemember: (value: string | null) => safeSet(localStorage, REMEMBER_KEY, value),

  getUsername: () => safeGet(localStorage, USERNAME_KEY),
  setUsername: (value: string | null) => safeSet(localStorage, USERNAME_KEY, value),
}
