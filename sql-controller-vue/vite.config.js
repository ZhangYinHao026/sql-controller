import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        // target: 'http://192.168.0.227:8083',
        target: 'http://192.168.0.227:8092',
        changeOrigin: true
      }
    }
  }
})
