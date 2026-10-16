/** Body of every JSON response from the Spring API: code 1000 with a result on success. */
export interface ApiEnvelope<T> {
  code: number;
  message?: string | null;
  result?: T;
}

/** Body of every error response from the Spring API; `message` is safe to show. */
export interface ApiErrorBody {
  code?: number;
  message?: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type QueryParams = Record<string, string | number | boolean | null | undefined>;
