import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { apiJson, ApiError } from "./api";
import { ACCESS_TOKEN_KEY } from "./constants";
import type { AuthLoginResponse, AuthUser } from "./types";

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
  const [token, setToken] = useState<string | null>(() => sessionStorage.getItem(ACCESS_TOKEN_KEY));
  const [loading, setLoading] = useState(true);

  const clearSession = useCallback(() => {
    sessionStorage.removeItem(ACCESS_TOKEN_KEY);
    setToken(null);
    setUser(null);
  }, []);

  const restore = useCallback(async () => {
    const storedToken = sessionStorage.getItem(ACCESS_TOKEN_KEY);
    if (!storedToken) {
      setLoading(false);
      return;
    }
    try {
      const me = await apiJson<AuthUser>("/api/auth/me");
      setUser(me);
      setToken(storedToken);
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

  const login = useCallback(async (usernameOrEmail: string, password: string) => {
    const response = await apiJson<AuthLoginResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username_or_email: usernameOrEmail,
        password,
      }),
    });
    sessionStorage.setItem(ACCESS_TOKEN_KEY, response.access_token);
    setToken(response.access_token);
    setUser(response.user);
    return response.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiJson<void>("/api/auth/logout", { method: "POST" });
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
