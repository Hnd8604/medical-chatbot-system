import { ACCESS_TOKEN_KEY, API_BASE_URL } from "../lib/constants";

export class ApiError extends Error {
  status: number;
  detail: string;

  constructor(status: number, detail: string) {
    super(detail);
    this.status = status;
    this.detail = detail;
  }
}

function authHeaders(): HeadersInit {
  const token = sessionStorage.getItem(ACCESS_TOKEN_KEY);
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function parseError(response: Response): Promise<string> {
  const text = await response.text();
  if (!text) {
    return response.statusText || "Yêu cầu không thành công.";
  }
  try {
    const data = JSON.parse(text) as { message?: string; detail?: string; error?: string };
    return data.message || data.detail || data.error || text;
  } catch {
    return text;
  }
}

export async function apiJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (!headers.has("Content-Type") && init.body) {
    headers.set("Content-Type", "application/json");
  }
  Object.entries(authHeaders()).forEach(([key, value]) => headers.set(key, value));

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers,
  });

  if (!response.ok) {
    throw new ApiError(response.status, await parseError(response));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  if (!text) {
    return undefined as T;
  }

  return JSON.parse(text) as T;
}

export async function apiDownload(path: string, filename: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: authHeaders(),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await parseError(response));
  }

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
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
