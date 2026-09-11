import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as createHttpServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { createServer } from 'vite'

test('Vite follows YAML proxy settings and forbids serving private configuration', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'rice-vite-config-'))
  const configPath = join(dir, 'private.yaml')
  const canary = 'CANARY_PRIVATE_YAML_DO_NOT_SERVE'
  const previousPath = process.env.RICE_CONFIG_PATH
  const backend = createHttpServer((_req, res) => {
    res.setHeader('Content-Type', 'application/json')
    res.end('{"fixture":true}')
  })
  let vite: Awaited<ReturnType<typeof createServer>> | undefined
  try {
    await new Promise<void>(resolve => backend.listen(0, '127.0.0.1', resolve))
    const backendAddress = backend.address()
    assert.ok(backendAddress && typeof backendAddress !== 'string')
    writeFileSync(configPath, `server:\n  port: ${backendAddress.port}\nweb:\n  dev-host: "127.0.0.1"\n  dev-port: 5199\n  api-host: "127.0.0.1"\nprivate:\n  password: ${canary}\n`)
    process.env.RICE_CONFIG_PATH = configPath
    const root = fileURLToPath(new URL('../', import.meta.url))
    vite = await createServer({
      root,
      configFile: fileURLToPath(new URL('../vite.config.ts', import.meta.url)),
      server: { port: 0, host: '127.0.0.1' },
      logLevel: 'silent',
    })
    await vite.listen()
    const address = vite.httpServer?.address()
    assert.ok(address && typeof address !== 'string')
    const origin = `http://127.0.0.1:${address.port}`
    const result = await fetch(`${origin}/api/config-probe`)
    assert.deepEqual(await result.json(), { fixture: true })
    const filePath = configPath.replace(/\\/g, '/')
    for (const path of ['/conf/config.yaml', `/@fs/${filePath}`, `/@fs/${filePath.toUpperCase()}`, '/%63onf/config.yaml']) {
      const response = await fetch(origin + path)
      assert.equal(response.status, 403, path)
      assert.ok(!(await response.text()).includes(canary))
    }
    const page = await fetch(origin)
    assert.equal(page.status, 200)
    assert.ok(!(await page.text()).includes(canary))
  } finally {
    await vite?.close()
    await new Promise<void>(resolve => backend.close(() => resolve()))
    if (previousPath === undefined) delete process.env.RICE_CONFIG_PATH
    else process.env.RICE_CONFIG_PATH = previousPath
    rmSync(dir, { recursive: true, force: true })
  }
})
