import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import { isConfigRequest, readWebConfig } from './config/shared-config.ts'

// https://vite.dev/config/
export default defineConfig(({ command, isPreview }) => {
  // Production builds need no local credentials and never import the private YAML.
  if (command === 'build' || isPreview) return { plugins: [react()] }
  const config = readWebConfig()
  const webRoot = fileURLToPath(new URL('.', import.meta.url))
  return {
    plugins: [react(), {
      name: 'protect-private-config',
      configureServer(server) {
        server.middlewares.use((req, res, next) => {
          if (isConfigRequest(req.url ?? '/', config.configPath, webRoot)) {
            res.statusCode = 403
            res.end('Forbidden')
            return
          }
          next()
        })
      },
    }],
    server: {
      host: config.host,
      port: config.port,
      strictPort: true,
      allowedHosts: true,
      fs: {
        deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/conf/**', config.configPath.replace(/\\/g, '/')],
      },
      // 开发时把 /api 同源转发到 Java 后端，前端无需处理 CORS
      proxy: {
        '/api': {
          target: config.apiTarget,
          changeOrigin: true,
          timeout: 180_000,
          proxyTimeout: 180_000,
          // 局域网用 IP 打开页面时，浏览器会带 Origin: http://192.168.x.x:5173；
          // 不改写的话 Spring CORS 会当成跨域并返回 403。
          configure(proxy) {
            proxy.on('proxyReq', (proxyReq) => {
              proxyReq.setHeader('Origin', config.apiTarget)
              proxyReq.setHeader('Referer', `${config.apiTarget}/`)
            })
          },
        },
      },
    },
  }
})
