import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxy = {
    target: env.VITE_API_PROXY || 'http://localhost:8080',
    changeOrigin: true
  }
  if (env.VITE_API_PROXY_STRIP_PREFIX === 'true') {
    apiProxy.rewrite = (path) => path.replace(/^\/api/, '')
  }

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: '0.0.0.0',
      port: Number(env.VITE_PORT || 5173),
      proxy: {
        '/api': apiProxy
      }
    }
  }
})
