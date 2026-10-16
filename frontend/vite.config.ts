import { fileURLToPath, URL } from "node:url";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");

  return {
    plugins: [react()],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    server: {
      host: "127.0.0.1",
      port: 5174,
      proxy: {
        "/api": {
          // Spring backend; override with VITE_PROXY_TARGET when it runs elsewhere.
          target: env.VITE_PROXY_TARGET || "http://localhost:8081",
          changeOrigin: true,
        },
      },
    },
  };
});
