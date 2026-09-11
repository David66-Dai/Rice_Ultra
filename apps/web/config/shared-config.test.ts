import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { isConfigRequest, readWebConfig } from './shared-config.ts'

const fixture = `server:\n  port: 8099\nweb:\n  dev-host: "0.0.0.0"\n  dev-port: 5199\n  api-host: "127.0.0.1"\nprivate:\n  password: CANARY_CONFIG_SECRET\n`

function withConfig(text: string, check: (file: string, dir: string) => void) {
  const dir = mkdtempSync(join(tmpdir(), 'rice-web-config-'))
  const file = join(dir, 'private.yaml')
  try {
    writeFileSync(file, text)
    check(file, dir)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

test('only returns proxy settings and follows the shared server port', () => {
  withConfig(fixture, (file) => {
    const result = readWebConfig({ RICE_CONFIG_PATH: file })
    assert.equal(result.apiTarget, 'http://127.0.0.1:8099')
    assert.equal(result.port, 5199)
    assert.ok(!JSON.stringify(result).includes('CANARY_CONFIG_SECRET'))
  })
})

test('explicit paths and environment overrides work without a fixed cwd', () => {
  withConfig(fixture, (_file, dir) => {
    const result = readWebConfig({ RICE_CONFIG_PATH: 'private.yaml', SERVER_PORT: '8100', WEB_DEV_PORT: '5200' }, dir)
    assert.equal(result.apiTarget, 'http://127.0.0.1:8100')
    assert.equal(result.port, 5200)
    assert.equal(readWebConfig({ RICE_CONFIG_PATH: 'private.yaml', WEB_API_TARGET: 'https://example.test' }, dir).apiTarget, 'https://example.test')
  })
})

test('bad YAML, duplicate keys and invalid ports fail without leaking source values', () => {
  for (const input of [fixture + 'private: CANARY_CONFIG_SECRET\n', fixture.replace('8099', 'true'), fixture + 'broken: [CANARY_CONFIG_SECRET']) {
    withConfig(input, file => {
      assert.throws(() => readWebConfig({ RICE_CONFIG_PATH: file }), error => {
        assert.ok(error instanceof Error)
        assert.ok(!error.stack?.includes('CANARY_CONFIG_SECRET'))
        return true
      })
    })
  }
})

test('missing explicit file never silently falls back to local credentials', () => {
  assert.throws(() => readWebConfig({ RICE_CONFIG_PATH: 'missing.yaml' }, tmpdir()))
})

test('config requests are blocked through direct, encoded and case-variant paths', () => {
  const file = join(tmpdir(), 'outside-private.yaml')
  const webRoot = join(tmpdir(), 'web')
  for (const request of ['/conf/config.yaml', '/@fs/C:/repo/CONF/config.yaml', '/%63onf/config.yaml', '/@fs/' + file.replace(/\\/g, '/')]) {
    assert.equal(isConfigRequest(request, file, webRoot), true)
  }
  assert.equal(isConfigRequest('/src/main.tsx', file, webRoot), false)
})
