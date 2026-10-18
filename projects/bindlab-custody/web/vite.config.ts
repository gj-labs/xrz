import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: { host: true }, // reachable from the phone/headset on the LAN
  build: {
    target: 'es2022', // top-level await in xr.ts
    rollupOptions: { input: { main: 'index.html', xr: 'xr.html' } },
  },
})
