/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Absolute backend origin; leave empty to call the same origin (Vite proxy in development). */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
