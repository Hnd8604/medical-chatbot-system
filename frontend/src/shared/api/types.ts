/** Envelope of every successful JSON response from the Spring API. */
export interface ApiEnvelope<T> {
  code: number;
  message?: string | null;
  result?: T;
}

/** Body of every error response from the Spring API. */
export interface ApiErrorBody {
  code?: number;
  error_code?: string;
  status?: number;
  message?: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export type QueryParams = Record<string, string | number | boolean | null | undefined>;
