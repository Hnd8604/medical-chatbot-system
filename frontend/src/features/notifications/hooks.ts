import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { notificationApi, notificationKeys, openNotificationStream } from "./api";
import type { NotificationList } from "./types";

const EMPTY: NotificationList = { unreadCount: 0, notifications: [] };

/** Notifications of the signed-in user, kept live through the server-sent event stream. */
export function useNotifications() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: notificationKeys.list(), queryFn: notificationApi.list });

  useEffect(
    () =>
      openNotificationStream({
        onConnected: () => void queryClient.invalidateQueries({ queryKey: notificationKeys.list() }),
        onNotification: (notification) =>
          queryClient.setQueryData<NotificationList>(notificationKeys.list(), (current = EMPTY) =>
            current.notifications.some((item) => item.id === notification.id)
              ? current
              : {
                  unreadCount: current.unreadCount + 1,
                  notifications: [notification, ...current.notifications],
                },
          ),
      }),
    [queryClient],
  );

  const refresh = () => queryClient.invalidateQueries({ queryKey: notificationKeys.list() });
  const markRead = useMutation({ mutationFn: notificationApi.markRead, onSuccess: refresh });
  const markAllRead = useMutation({ mutationFn: notificationApi.markAllRead, onSuccess: refresh });

  return {
    ...(list.data ?? EMPTY),
    error: list.error ?? markRead.error ?? markAllRead.error,
    markRead: (id: string) => markRead.mutate(id),
    markAllRead: () => markAllRead.mutate(),
  };
}
