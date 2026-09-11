// Node/Vite only. Never import this module from src/ or expose its input to the client.
import { readFileSync } from 'node:fs'
import { dirname, isAbsolute, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseAllDocuments } from 'yaml'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

type Env = Record<string, string | undefined>
type Mapping = Record<string, unknown>

function invalid(field: string): never {
  throw new Error(`统一配置中的 ${field} 无效，请检查 conf/config.yaml。`)
}

function mapping(value: unknown, field: string): Mapping {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid(field)
  return value as Mapping
}

function port(value: unknown, field: string): number {
  const number = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value
  if (typeof number !== 'number' || !Number.isInteger(number) || number < 1 || number > 65535) invalid(field)
  return number
}

function host(value: unknown, field: string): string {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9.:[\]-]+$/.test(value)) invalid(field)
  return value
}

export function readWebConfig(env: Env = process.env, cwd = process.cwd()) {
  const configuredPath = env.RICE_CONFIG_PATH
  if (configuredPath !== undefined && !configuredPath.trim()) invalid('RICE_CONFIG_PATH')
  const configPath = configuredPath
    ? resolve(isAbsolute(configuredPath) ? configuredPath : resolve(cwd, configuredPath))
    : resolve(projectRoot, 'conf/config.yaml')
  let root: Mapping
  try {
    const documents = parseAllDocuments(readFileSync(configPath, 'utf8'), { uniqueKeys: true })
    if (!documents.length || documents.some(document => document.errors.length || document.warnings.length)) {
      throw new Error('invalid YAML')
    }
    root = mapping(documents[0].toJS(), '根节点')
  } catch {
    // Parser messages may include a database password from the source line.
    throw new Error('无法读取统一 YAML 配置，请检查 RICE_CONFIG_PATH，或将 conf/config.example.yaml 复制为 conf/config.yaml。')
  }
  const web = mapping(root.web, 'web')
  const server = mapping(root.server, 'server')
  const devHost = host(env.WEB_DEV_HOST ?? web['dev-host'], 'web.dev-host')
  const devPort = port(env.WEB_DEV_PORT ?? web['dev-port'], 'web.dev-port')
  const apiPort = port(env.SERVER_PORT ?? server.port, 'server.port')
  const apiHost = host(web['api-host'], 'web.api-host')
  const bracketedHost = apiHost.includes(':') && !apiHost.startsWith('[') ? `[${apiHost}]` : apiHost
  let target: URL
  try {
    target = new URL(env.WEB_API_TARGET ?? `http://${bracketedHost}:${apiPort}`)
  } catch {
    invalid('WEB_API_TARGET')
  }
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password ||
      target.pathname !== '/' || target.search || target.hash) invalid('WEB_API_TARGET')
  // Only these public dev-server settings escape this function; root may contain secrets.
  return { configPath, host: devHost, port: devPort, apiTarget: target.origin }
}

export function isConfigRequest(requestUrl: string, configPath: string, webRoot: string): boolean {
  let path = requestUrl.split('?')[0]
  try {
    // Reject encoded separators as well as ordinary /@fs/ access, including Windows casing.
    for (let i = 0; i < 3; i++) {
      const decoded = decodeURIComponent(path)
      if (decoded === path) break
      path = decoded
    }
  } catch {
    return true
  }
  path = path.replace(/\\/g, '/')
  if (/(^|\/)conf(\/|$)/i.test(path)) return true
  const candidate = path.startsWith('/@fs/') ? path.slice('/@fs/'.length) : resolve(webRoot, `.${path}`)
  return resolve(candidate).toLowerCase() === resolve(configPath).toLowerCase()
}
