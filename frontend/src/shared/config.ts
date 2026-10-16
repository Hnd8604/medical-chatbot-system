/** Backend origin; empty means same origin (the Vite proxy in development). */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, "") || "";

/** Root of the versioned REST API; feature modules call paths relative to it, e.g. `/auth/login`. */
export const API_ROOT = `${API_BASE_URL}/api/v1`;
