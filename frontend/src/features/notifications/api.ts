import { api, apiUrl } from "@/shared/api/client";
import { getAccessToken } from "@/shared/api/tokens";
import type { Notification, NotificationList } from "./types";

export const notificationKeys = {
  list: () => ["notifications"] as const,
};

export const notificationApi = {
  list: () => api.get<NotificationList>("/api/notifications"),
  markRead: (id: string) => api.post(`/api/notifications/${encodeURIComponent(id)}/read`),
  markAllRead: () => api.post("/api/notifications/read-all"),
};

interface StreamHandlers {
  /** (Re)connected: reload the list to catch up on anything missed. */
  onConnected: () => void;
  onNotification: (notification: Notification) => void;
}

/**
 * Opens the server-sent event stream. EventSource cannot send headers, so the
 * access token goes in the query string. Returns a function that closes it.
 */
export function openNotificationStream({ onConnected, onNotification }: StreamHandlers): () => void {
  const token = getAccessToken();
  if (!token) {
    return () => {};
  }
  const source = new EventSource(apiUrl("/api/notifications/stream", { token }));
  source.addEventListener("connected", onConnected);
  source.addEventListener("notification", (event) => {
    try {
      onNotification(JSON.parse((event as MessageEvent<string>).data) as Notification);
    } catch {
      // Malformed payload: the next "connected" event resynchronizes the list.
    }
  });
  return () => source.close();
}
