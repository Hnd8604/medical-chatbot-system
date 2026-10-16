import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from "axios";
import { API_BASE_URL } from "@/shared/config";
import { expireSession, getAccessToken, getRefreshToken, setTokens } from "./tokens";
import type { ApiEnvelope, ApiErrorBody, QueryParams } from "./types";

const SUCCESS_CODE = 1000;
const FALLBACK_MESSAGE = "Yêu cầu không thành công.";
/** Endpoints whose 401 means bad credentials, not an expired access token. */
const CREDENTIAL_ENDPOINTS = ["/api/auth/login", "/api/auth/refresh"];

export class ApiError extends Error {
  readonly status: number;
  readonly code?: number;
  readonly errorCode?: string;

  constructor(status: number, message: string, code?: number, errorCode?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.errorCode = errorCode;
  }
}

/** Readable message for any thrown value, for display in the UI. */
export function errorMessage(error: unknown, fallback = FALLBACK_MESSAGE): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

const http = axios.create({
  baseURL: API_BASE_URL,
  headers: { "Content-Type": "application/json" },
});

http.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }
  return config;
});

// ----- Access token refresh -----

let pendingRefresh: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return null;
  }
  try {
    // Plain axios so that a failing refresh does not re-enter the 401 interceptor.
    const { data } = await axios.post<ApiEnvelope<{ access_token: string; refresh_token: string }>>(
      `${API_BASE_URL}/api/auth/refresh`,
      { refresh_token: refreshToken },
    );
    const tokens = unwrap(data);
    setTokens(tokens.access_token, tokens.refresh_token);
    return tokens.access_token;
  } catch {
    return null;
  }
}

/** Concurrent 401s share a single refresh call; the refresh token is single-use. */
function refreshOnce(): Promise<string | null> {
  pendingRefresh ??= refreshAccessToken().finally(() => {
    pendingRefresh = null;
  });
  return pendingRefresh;
}

http.interceptors.response.use(undefined, async (error) => {
  const original = (axios.isAxiosError(error) ? error.config : undefined) as
    | (InternalAxiosRequestConfig & { _retried?: boolean })
    | undefined;
  const isExpiredToken =
    axios.isAxiosError(error) &&
    error.response?.status === 401 &&
    original &&
    !original._retried &&
    !CREDENTIAL_ENDPOINTS.some((path) => original.url?.includes(path));
  if (!isExpiredToken || !original) {
    return Promise.reject(error);
  }

  original._retried = true;
  const token = await refreshOnce();
  if (!token) {
    expireSession();
    return Promise.reject(error);
  }
  original.headers.set("Authorization", `Bearer ${token}`);
  return http(original);
});

// ----- Response and error mapping -----

function unwrap<T>(data: ApiEnvelope<T> | "" | null | undefined): T {
  // 204 No Content and other intentionally empty bodies.
  if (!data) {
    return undefined as T;
  }
  if (typeof data !== "object" || typeof data.code !== "number") {
    throw new ApiError(0, "Phản hồi từ máy chủ không đúng định dạng.");
  }
  if (data.code !== SUCCESS_CODE) {
    throw new ApiError(0, data.message || FALLBACK_MESSAGE, data.code);
  }
  return data.result as T;
}

async function readErrorBody(data: unknown): Promise<ApiErrorBody | null> {
  // Downloads request a Blob, so their JSON error body arrives as a Blob too.
  if (data instanceof Blob) {
    try {
      return JSON.parse(await data.text()) as ApiErrorBody;
    } catch {
      return null;
    }
  }
  return data && typeof data === "object" ? (data as ApiErrorBody) : null;
}

async function toApiError(error: unknown): Promise<ApiError> {
  if (error instanceof ApiError) {
    return error;
  }
  if (!axios.isAxiosError(error)) {
    return new ApiError(0, errorMessage(error));
  }
  const status = error.response?.status ?? 0;
  const body = await readErrorBody(error.response?.data);
  if (body?.message) {
    return new ApiError(status, body.message, body.code, body.error_code);
  }
  if (!error.response) {
    return new ApiError(0, "Không thể kết nối tới máy chủ.");
  }
  return new ApiError(status, error.response.statusText || FALLBACK_MESSAGE);
}

/** Drops empty query values so optional filters are simply left out of the URL. */
function compact(params?: QueryParams): QueryParams | undefined {
  if (!params) {
    return undefined;
  }
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== null && value !== undefined && String(value).trim() !== ""),
  );
}

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const { data } = await http.request<ApiEnvelope<T>>(config);
    return unwrap(data);
  } catch (error) {
    throw await toApiError(error);
  }
}

function attachmentFilename(header: unknown): string | null {
  if (typeof header !== "string") {
    return null;
  }
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (encoded) {
    return decodeURIComponent(encoded[1]);
  }
  return /filename="?([^";]+)"?/i.exec(header)?.[1] ?? null;
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export const api = {
  get: <T>(url: string, params?: QueryParams) => request<T>({ method: "GET", url, params: compact(params) }),
  post: <T = void>(url: string, body?: unknown) => request<T>({ method: "POST", url, data: body }),
  put: <T = void>(url: string, body?: unknown) => request<T>({ method: "PUT", url, data: body }),
  patch: <T = void>(url: string, body?: unknown) => request<T>({ method: "PATCH", url, data: body }),
  delete: <T = void>(url: string) => request<T>({ method: "DELETE", url }),

  /** Downloads a file, preferring the server's filename over the fallback. */
  async download(url: string, params: QueryParams, fallbackFilename: string): Promise<void> {
    try {
      const response = await http.get<Blob>(url, { params: compact(params), responseType: "blob" });
      saveBlob(response.data, attachmentFilename(response.headers["content-disposition"]) ?? fallbackFilename);
    } catch (error) {
      throw await toApiError(error);
    }
  },
};

/** Absolute URL for endpoints that cannot go through axios, such as EventSource. */
export function apiUrl(path: string, params?: QueryParams): string {
  const entries = Object.entries(compact(params) ?? {}).map(([key, value]) => [key, String(value)]);
  const query = new URLSearchParams(entries).toString();
  return `${API_BASE_URL}${path}${query ? `?${query}` : ""}`;
}
