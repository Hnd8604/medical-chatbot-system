-- Bỏ ràng buộc unique (message_id, user_id) trên message_feedback.
-- Việc chống tạo trùng feedback do tầng ứng dụng (FeedbackService.createFeedback) đảm nhiệm.
DROP INDEX IF EXISTS idx_feedback_message_user;
