import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from "axios";
import {
  ACCESS_TOKEN_KEY,
  API_BASE_URL,
  REFRESH_TOKEN_KEY,
  SESSION_EXPIRED_EVENT,
} from "../lib/constants";
import type { AuthRefreshResponse } from "../lib/types";

export class ApiError extends Error {
  status: number;
  detail: string;

  constructor(status: number, detail: string) {
    super(detail);
    this.status = status;
    this.detail = detail;
  }
}

// ----- Quản lý token (nguồn sự thật duy nhất cho access + refresh token) -----

export function getAccessToken(): string | null {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(accessToken: string, refreshToken?: string): void {
  sessionStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  if (refreshToken) {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  }
}

export function clearTokens(): void {
  sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  sessionStorage.removeItem(REFRESH_TOKEN_KEY);
}

// ----- Axios instance dùng chung -----

const http: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  headers: { "Content-Type": "application/json" },
});

// Request interceptor: gắn Authorization từ access token hiện tại.
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  } else {
    config.headers.delete("Authorization");
  }
  return config;
});

// ----- Refresh token xoay vòng, gọi tự động khi access token hết hạn (401) -----

// Single-flight: nhiều request 401 cùng lúc chỉ kích hoạt MỘT lần gọi /refresh.
let refreshPromise: Promise<string | null> | null = null;

function skipRefresh(url: string | undefined): boolean {
  if (!url) {
    return false;
  }
  return url.includes("/api/auth/login") || url.includes("/api/auth/refresh");
}

async function doRefresh(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return null;
  }
  try {
    // Gọi axios "trần" (không qua interceptor) để tránh đệ quy refresh.
    const { data } = await axios.post<AuthRefreshResponse>(
      `${API_BASE_URL}/api/auth/refresh`,
      { refresh_token: refreshToken },
      { headers: { "Content-Type": "application/json" } },
    );
    setTokens(data.access_token, data.refresh_token);
    return data.access_token;
  } catch {
    handleRefreshFailure();
    return null;
  }
}

async function tryRefresh(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

function handleRefreshFailure(): void {
  clearTokens();
  window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
}

// Response interceptor: gặp 401 → xoay token một lần rồi thử lại request gốc.
http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as
      | (InternalAxiosRequestConfig & { _retried?: boolean })
      | undefined;

    if (
      error.response?.status === 401 &&
      original &&
      !original._retried &&
      !skipRefresh(original.url) &&
      getRefreshToken()
    ) {
      original._retried = true;
      const newToken = await tryRefresh();
      if (newToken) {
        original.headers.set("Authorization", `Bearer ${newToken}`);
        return http(original);
      }
    }

    return Promise.reject(error);
  },
);

// ----- Chuyển AxiosError thành ApiError với thông điệp đọc được -----

function toApiError(error: unknown): ApiError {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status ?? 0;
    const data = error.response?.data as
      | { message?: string; detail?: string; error?: string }
      | string
      | undefined;

    if (typeof data === "string" && data) {
      return new ApiError(status, data);
    }
    if (data && typeof data === "object") {
      const detail = data.message || data.detail || data.error;
      if (detail) {
        return new ApiError(status, detail);
      }
    }
    return new ApiError(
      status,
      error.response?.statusText || error.message || "Yêu cầu không thành công.",
    );
  }
  return new ApiError(0, error instanceof Error ? error.message : "Yêu cầu không thành công.");
}

// ----- Helper HTTP idiomatic: nhận/trả object, tự ném ApiError -----

export async function apiGet<T>(path: string, config?: AxiosRequestConfig): Promise<T> {
  try {
    const { data } = await http.get<T>(path, config);
    return data;
  } catch (error) {
    throw toApiError(error);
  }
}

export async function apiPost<T>(
  path: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  try {
    const { data } = await http.post<T>(path, body, config);
    return data;
  } catch (error) {
    throw toApiError(error);
  }
}

export async function apiPut<T>(
  path: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  try {
    const { data } = await http.put<T>(path, body, config);
    return data;
  } catch (error) {
    throw toApiError(error);
  }
}

export async function apiPatch<T>(
  path: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  try {
    const { data } = await http.patch<T>(path, body, config);
    return data;
  } catch (error) {
    throw toApiError(error);
  }
}

export async function apiDelete<T = void>(
  path: string,
  config?: AxiosRequestConfig,
): Promise<T> {
  try {
    const { data } = await http.delete<T>(path, config);
    return data;
  } catch (error) {
    throw toApiError(error);
  }
}

export async function apiDownload(path: string, filename: string): Promise<void> {
  let blob: Blob;
  try {
    const response = await http.get<Blob>(path, { responseType: "blob" });
    blob = response.data;
  } catch (error) {
    throw toApiError(error);
  }

  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

// ----- SSE: stream thông báo realtime -----

export interface NotificationStreamHandlers {
  /** Kết nối (hoặc tự kết nối lại) thành công — nên resync bằng một lần load đầy đủ. */
  onConnected?: () => void;
  /** Có thông báo mới; data là JSON của NotificationItem. */
  onNotification: (raw: string) => void;
}

/**
 * Mở EventSource tới /api/notifications/stream. Vì EventSource không gửi được
 * header Authorization, access token được đính kèm qua query param ?token=.
 * Trả về hàm đóng kết nối (gọi khi unmount).
 */
export function openNotificationStream(handlers: NotificationStreamHandlers): () => void {
  const token = getAccessToken();
  if (!token) {
    return () => {};
  }
  const url = `${API_BASE_URL}/api/notifications/stream?token=${encodeURIComponent(token)}`;
  const source = new EventSource(url);

  if (handlers.onConnected) {
    source.addEventListener("connected", () => handlers.onConnected?.());
  }
  source.addEventListener("notification", (event) => {
    handlers.onNotification((event as MessageEvent).data);
  });

  return () => source.close();
}

export function toQuery(params: Record<string, string | number | null | undefined>): string {
  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined && String(value).trim() !== "") {
      searchParams.set(key, String(value));
    }
  });
  const query = searchParams.toString();
  return query ? `?${query}` : "";
}

export function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}
