import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "../services/api";

// Không retry các lỗi client (4xx) vì gần như luôn lặp lại cùng kết quả:
// 401/403 đã được axios interceptor xử lý (refresh token / session expired),
// 404/422 là lỗi nghiệp vụ. Chỉ retry lỗi mạng / 5xx, tối đa 2 lần.
function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < 2;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetry,
      // Dữ liệu được coi là "tươi" trong 30s → tránh refetch trùng lặp khi
      // điều hướng qua lại giữa các trang.
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: false,
    },
  },
});
