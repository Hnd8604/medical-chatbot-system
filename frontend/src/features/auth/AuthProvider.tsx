import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/shared/api/client";
import { clearTokens, getAccessToken, SESSION_EXPIRED_EVENT, setTokens } from "@/shared/api/tokens";
import { authApi } from "./api";
import type { AuthUser } from "./types";

interface AuthContextValue {
  user: AuthUser | null;
  /** True while a stored session is being restored on page load. */
  loading: boolean;
  login: (usernameOrEmail: string, password: string) => Promise<AuthUser>;
  logout: () => Promise<void>;
  /** Replaces the cached user after an endpoint returned its new state. */
  setUser: (user: AuthUser) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const queryClient = useQueryClient();
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(() => getAccessToken() !== null);

  // Cached queries belong to the previous user and must never leak into the next session.
  const endSession = useCallback(() => {
    clearTokens();
    setUser(null);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => {
    if (!getAccessToken()) {
      return;
    }
    let cancelled = false;
    authApi
      .me()
      .then((me) => {
        if (!cancelled) setUser(me);
      })
      .catch((error: unknown) => {
        if (!cancelled && error instanceof ApiError && (error.status === 401 || error.status === 403)) {
          endSession();
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [endSession]);

  // The API client fires this when the refresh token is rejected.
  useEffect(() => {
    window.addEventListener(SESSION_EXPIRED_EVENT, endSession);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, endSession);
  }, [endSession]);

  const login = useCallback(
    async (usernameOrEmail: string, password: string) => {
      const response = await authApi.login(usernameOrEmail, password);
      queryClient.clear();
      setTokens(response.accessToken, response.refreshToken);
      setUser(response.user);
      return response.user;
    },
    [queryClient],
  );

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // The token may already be expired; signing out locally is still correct.
    } finally {
      endSession();
    }
  }, [endSession]);

  const value = useMemo(() => ({ user, loading, login, logout, setUser }), [user, loading, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return context;
}

/** The signed-in user, for components rendered only behind ProtectedRoute. */
export function useSignedInUser(): AuthUser {
  const { user } = useAuth();
  if (!user) {
    throw new Error("useSignedInUser must be used inside a protected route");
  }
  return user;
}
