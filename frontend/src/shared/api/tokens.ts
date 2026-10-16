// Tokens live in sessionStorage so a session ends with the browser tab.
const ACCESS_TOKEN_KEY = "medical_chatbot_access_token";
const REFRESH_TOKEN_KEY = "medical_chatbot_refresh_token";

/** Fired when the session can no longer be refreshed, so the app can sign the user out. */
export const SESSION_EXPIRED_EVENT = "auth:session-expired";

export function getAccessToken(): string | null {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(accessToken: string, refreshToken: string): void {
  sessionStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  sessionStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

export function clearTokens(): void {
  sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  sessionStorage.removeItem(REFRESH_TOKEN_KEY);
}

export function expireSession(): void {
  clearTokens();
  window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
}
