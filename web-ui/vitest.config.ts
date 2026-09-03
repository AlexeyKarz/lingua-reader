import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    environmentOptions: {
      jsdom: {
        url: 'http://localhost/',
      },
    },
    globals: false,
    include: ['test/unit/**/*.test.ts', 'test/unit/**/*.test.tsx'],
    setupFiles: ['test/setup.ts'],
  },
})
