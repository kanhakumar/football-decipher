/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Relative asset paths so the build works from any path on Static Web Apps. public/config.js is
// copied as is: the deploy hook rewrites it with the cloud URLs and sign-in settings (ADR-018).
export default defineConfig({
  base: "./",
  plugins: [react()],
  server: { port: 5600, strictPort: true },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});
