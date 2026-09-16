import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 临时测试配置：代理指向新版后端 8091（含字段对比快照接口）
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8091',
        changeOrigin: true
      }
    }
  }
})
