import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import {
  apiGet,
  apiPost,
  ApiError,
  clearTokens,
  getAccessToken,
  setTokens,
} from "../services/api";
import { SESSION_EXPIRED_EVENT } from "../lib/constants";
import type { AuthLoginResponse, AuthUser } from "../lib/types";

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  token: string | null;
  login: (usernameOrEmail: string, password: string) => Promise<AuthUser>;
  logout: () => Promise<void>;
  restore: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [token, setToken] = useState<string | null>(() => getAccessToken());
  const [loading, setLoading] = useState(true);

  const clearSession = useCallback(() => {
    clearTokens();
    setToken(null);
    setUser(null);
  }, []);

  const restore = useCallback(async () => {
    const storedToken = getAccessToken();
    if (!storedToken) {
      setLoading(false);
      return;
    }
    try {
      const me = await apiGet<AuthUser>("/api/auth/me");
      setUser(me);
      // Access token có thể đã được api.ts xoay ngầm khi /me gặp 401, nên đọc lại từ store.
      setToken(getAccessToken());
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        clearSession();
      }
    } finally {
      setLoading(false);
    }
  }, [clearSession]);

  useEffect(() => {
    void restore();
  }, [restore]);

  // api.ts phát sự kiện này khi refresh token thất bại => dọn state + buộc về trang đăng nhập.
  useEffect(() => {
    function handleSessionExpired() {
      setToken(null);
      setUser(null);
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
  }, []);

  const login = useCallback(async (usernameOrEmail: string, password: string) => {
    const response = await apiPost<AuthLoginResponse>("/api/auth/login", {
      username_or_email: usernameOrEmail,
      password,
    });
    setTokens(response.access_token, response.refresh_token);
    setToken(response.access_token);
    setUser(response.user);
    return response.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiPost<void>("/api/auth/logout");
    } catch {
      // Token may already be expired; local cleanup is still correct.
    } finally {
      clearSession();
    }
  }, [clearSession]);

  const value = useMemo(
    () => ({ user, loading, token, login, logout, restore }),
    [user, loading, token, login, logout, restore],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return context;
}
