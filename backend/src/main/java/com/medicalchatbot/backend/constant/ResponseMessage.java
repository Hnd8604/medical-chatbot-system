package com.medicalchatbot.backend.constant;

/** Messages of successful responses ({@code ApiResponse.message}), shown to the user by the UI. */
public final class ResponseMessage {

    private ResponseMessage() {
    }

    // Authentication & account
    public static final String LOGIN_SUCCESS = "Đăng nhập thành công.";
    public static final String REGISTER_SUCCESS = "Đăng ký tài khoản thành công.";
    public static final String REFRESH_SUCCESS = "Làm mới phiên đăng nhập thành công.";
    public static final String LOGOUT_SUCCESS = "Đăng xuất thành công.";
    public static final String GET_MY_INFO_SUCCESS = "Lấy thông tin tài khoản thành công.";
    public static final String UPDATE_PROFILE_SUCCESS = "Cập nhật hồ sơ thành công.";
    public static final String CHANGE_PASSWORD_SUCCESS = "Đổi mật khẩu thành công. Vui lòng đăng nhập lại.";
    public static final String LINK_PATIENT_SUCCESS = "Liên kết hồ sơ bệnh nhân thành công.";

    // Password reset
    public static final String SEND_RESET_CODE_SUCCESS = "Đã xử lý yêu cầu đặt lại mật khẩu.";
    public static final String VERIFY_RESET_CODE_SUCCESS = "Xác thực mã thành công.";
    public static final String RESET_PASSWORD_SUCCESS = "Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại.";

    // Chat
    public static final String CHAT_SUCCESS = "Gửi tin nhắn thành công.";
    public static final String GET_CHAT_SESSIONS_SUCCESS = "Lấy danh sách cuộc trò chuyện thành công.";
    public static final String GET_CHAT_MESSAGES_SUCCESS = "Lấy tin nhắn của cuộc trò chuyện thành công.";
    public static final String RENAME_CHAT_SESSION_SUCCESS = "Đổi tên cuộc trò chuyện thành công.";
    public static final String DELETE_CHAT_SESSION_SUCCESS = "Xóa cuộc trò chuyện thành công.";
    public static final String CREATE_FEEDBACK_SUCCESS = "Đánh giá câu trả lời thành công.";
    public static final String UPDATE_FEEDBACK_SUCCESS = "Cập nhật đánh giá thành công.";
    public static final String DELETE_FEEDBACK_SUCCESS = "Xóa đánh giá thành công.";

    // Notification
    public static final String GET_NOTIFICATIONS_SUCCESS = "Lấy danh sách thông báo thành công.";
    public static final String MARK_NOTIFICATION_READ_SUCCESS = "Đã đánh dấu thông báo là đã đọc.";
    public static final String MARK_ALL_NOTIFICATIONS_READ_SUCCESS = "Đã đánh dấu tất cả thông báo là đã đọc.";

    // Patient
    public static final String SEARCH_PATIENTS_SUCCESS = "Tìm kiếm bệnh nhân thành công.";
    public static final String GET_PATIENT_SUCCESS = "Lấy thông tin bệnh nhân thành công.";
    public static final String GET_OBSERVATIONS_SUCCESS = "Lấy danh sách chỉ số sức khỏe thành công.";
    public static final String GET_CONDITIONS_SUCCESS = "Lấy danh sách chẩn đoán thành công.";
    public static final String GET_ENCOUNTERS_SUCCESS = "Lấy danh sách lượt khám thành công.";
    public static final String GET_MEDICATIONS_SUCCESS = "Lấy danh sách thuốc thành công.";
    public static final String GET_MY_PATIENT_PROFILE_SUCCESS = "Lấy hồ sơ bệnh nhân của bạn thành công.";

    // Usage
    public static final String GET_QUOTA_SUCCESS = "Lấy hạn mức sử dụng thành công.";
    public static final String GET_COSTS_SUCCESS = "Lấy thống kê chi phí thành công.";

    // System
    public static final String GET_CHATBOT_STATUS_SUCCESS = "Lấy trạng thái dịch vụ AI thành công.";

    // Admin - users
    public static final String GET_USERS_SUCCESS = "Lấy danh sách người dùng thành công.";
    public static final String UPDATE_USER_STATUS_SUCCESS = "Cập nhật trạng thái người dùng thành công.";
    public static final String UPDATE_USER_ROLE_SUCCESS = "Cập nhật vai trò người dùng thành công.";
    public static final String GET_USER_QUOTA_SUCCESS = "Lấy hạn mức của người dùng thành công.";
    public static final String GET_USER_COSTS_SUCCESS = "Lấy thống kê chi phí của người dùng thành công.";

    // Admin - quota policies
    public static final String GET_QUOTA_POLICIES_SUCCESS = "Lấy danh sách chính sách quota thành công.";
    public static final String GET_QUOTA_POLICY_SUCCESS = "Lấy chính sách quota thành công.";
    public static final String CREATE_QUOTA_POLICY_SUCCESS = "Tạo chính sách quota thành công.";
    public static final String UPDATE_QUOTA_POLICY_SUCCESS = "Cập nhật chính sách quota thành công.";
    public static final String DELETE_QUOTA_POLICY_SUCCESS = "Xóa chính sách quota thành công.";

    // Admin - alerts and audit log
    public static final String GET_ALERTS_SUCCESS = "Lấy danh sách cảnh báo thành công.";
    public static final String RESOLVE_ALERT_SUCCESS = "Đã đánh dấu cảnh báo là đã xử lý.";
    public static final String GET_AUDIT_LOGS_SUCCESS = "Lấy nhật ký hệ thống thành công.";
    public static final String GET_AUDIT_LOG_FILTERS_SUCCESS = "Lấy bộ lọc nhật ký hệ thống thành công.";

    // Admin - analytics
    public static final String GET_INTENT_ANALYTICS_SUCCESS = "Lấy thống kê ý định câu hỏi thành công.";
    public static final String GET_ERROR_ANALYTICS_SUCCESS = "Lấy thống kê lỗi thành công.";
    public static final String GET_PERFORMANCE_ANALYTICS_SUCCESS = "Lấy thống kê hiệu năng thành công.";
    public static final String GET_REQUEST_COUNT_SUCCESS = "Lấy số lượt hỏi thành công.";
    public static final String GET_CACHE_METRICS_SUCCESS = "Lấy thống kê cache thành công.";

    // Admin - backup and restore
    public static final String START_BACKUP_SUCCESS = "Đã bắt đầu sao lưu dữ liệu.";
    public static final String GET_BACKUPS_SUCCESS = "Lấy lịch sử sao lưu thành công.";
    public static final String START_RESTORE_SUCCESS = "Đã bắt đầu khôi phục dữ liệu.";
    public static final String GET_RESTORES_SUCCESS = "Lấy lịch sử khôi phục thành công.";
}
