import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, proxy the API and WebSocket to the Spring Boot engine on :8080.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
});
