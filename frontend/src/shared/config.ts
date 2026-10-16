/** Backend origin; empty means same origin (the Vite proxy in development). */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, "") || "";
