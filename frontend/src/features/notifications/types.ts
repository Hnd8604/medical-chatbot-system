export interface Notification {
  id: string;
  type: string;
  title: string;
  content: string;
  is_read: boolean;
  created_at: string;
}

export interface NotificationList {
  unread_count: number;
  notifications: Notification[];
}
