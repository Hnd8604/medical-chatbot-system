export interface Notification {
  id: string;
  type: string;
  title: string;
  content: string;
  isRead: boolean;
  createdAt: string;
}

export interface NotificationList {
  unreadCount: number;
  notifications: Notification[];
}
