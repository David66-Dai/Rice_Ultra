import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    strictPort: true,
    allowedHosts: true,
    // 开发时把 /api 同源转发到 Java 后端，前端无需处理 CORS
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        // 局域网用 IP 打开页面时，浏览器会带 Origin: http://192.168.x.x:5173；
        // 不改写的话 Spring CORS 会当成跨域并返回 403。
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('Origin', 'http://127.0.0.1:5173')
            proxyReq.setHeader('Referer', 'http://127.0.0.1:5173/')
          })
        },
      },
    },
  },
})
