import type { Config } from "tailwindcss";

export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        display: ["system-ui", "-apple-system", "Segoe UI", "Roboto", "Helvetica", "Arial", "sans-serif"],
        sans: ["system-ui", "-apple-system", "Segoe UI", "Roboto", "Helvetica", "Arial", "sans-serif"],
        mono: ["JetBrains Mono", "monospace"],
      },
      colors: {
        background: "var(--background)",
        foreground: "var(--foreground)",
        muted: "var(--muted)",
        "muted-foreground": "var(--muted-foreground)",
        accent: "var(--accent)",
        "accent-secondary": "var(--accent-secondary)",
        border: "var(--border)",
        card: "var(--card)",
        success: "var(--success)",
        warning: "var(--warning)",
        danger: "var(--danger)",
      },
      boxShadow: {
        card: "0 10px 25px rgba(15, 23, 42, 0.08)",
        lift: "0 20px 40px rgba(15, 23, 42, 0.12)",
        accent: "0 8px 24px rgba(0, 82, 255, 0.24)",
      },
    },
  },
  plugins: [],
} satisfies Config;
