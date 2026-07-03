export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, "") || "";

export const ACCESS_TOKEN_KEY = "medical_chatbot_access_token";
export const REFRESH_TOKEN_KEY = "medical_chatbot_refresh_token";

// Sự kiện phát ra khi refresh token thất bại (phiên hết hiệu lực) để AuthProvider dọn state.
export const SESSION_EXPIRED_EVENT = "auth:session-expired";

export const TEXT = {
  loginTitle: "Medical Chatbot",
  loginSubtitle: "Đăng nhập để bắt đầu hỏi đáp dữ liệu FHIR theo đúng quyền truy cập.",
  loginButton: "Đăng nhập",
  logoutButton: "Đăng xuất",
  usernameLabel: "Tên đăng nhập hoặc email",
  passwordLabel: "Mật khẩu",
  forgotPasswordLink: "Quên mật khẩu?",
  forgotPasswordTitle: "Quên mật khẩu",
  forgotPasswordEmailSubtitle: "Nhập email tài khoản để nhận mã đặt lại mật khẩu.",
  forgotPasswordCodeSubtitle: "Nhập mã 6 số đã được gửi tới email của bạn.",
  forgotPasswordResetSubtitle: "Tạo mật khẩu mới cho tài khoản của bạn.",
  forgotPasswordEmailLabel: "Email",
  forgotPasswordCodeLabel: "Mã xác thực",
  forgotPasswordNewPasswordLabel: "Mật khẩu mới",
  forgotPasswordConfirmLabel: "Xác nhận mật khẩu mới",
  forgotPasswordSendCode: "Gửi mã",
  forgotPasswordVerifyCode: "Xác nhận mã",
  forgotPasswordResend: "Gửi lại mã",
  forgotPasswordSubmit: "Đặt lại mật khẩu",
  forgotPasswordSuccess: "Đặt lại mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới.",
  backToLogin: "Quay lại đăng nhập",
  changePassword: "Đổi mật khẩu",
  changePasswordTitle: "Đổi mật khẩu",
  changePasswordSubtitle: "Nhập mật khẩu hiện tại và mật khẩu mới cho tài khoản của bạn.",
  changePasswordCurrentLabel: "Mật khẩu hiện tại",
  changePasswordNewLabel: "Mật khẩu mới",
  changePasswordConfirmLabel: "Xác nhận mật khẩu mới",
  changePasswordSubmit: "Đổi mật khẩu",
  changePasswordSuccess: "Đổi mật khẩu thành công. Vui lòng đăng nhập lại bằng mật khẩu mới.",
  newChat: "Hội thoại mới",
  refresh: "Làm mới",
  searchSessions: "Tìm hội thoại",
  exportPdf: "Xuất PDF",
  exportCsv: "Xuất CSV",
  emptySessions: "Chưa có hội thoại.",
  emptySessionSearch: "Không tìm thấy hội thoại phù hợp.",
  renameSession: "Đổi tên",
  deleteSession: "Xóa",
  save: "Lưu",
  cancel: "Hủy",
  send: "Gửi",
  notifications: "Thông báo",
  markAllRead: "Đọc tất cả",
  patientSearch: "Tìm bệnh nhân",
  myProfile: "Hồ sơ của tôi",
  currentSession: "Phiên hiện tại",
};

export const USER_QUICK_PROMPTS = [
  "Thông tin cá nhân của tôi là gì?",
  "Số điện thoại của tôi là gì?",
  "Ngày sinh của tôi là gì?",
  "Thuốc của tôi là gì?",
  "Chỉ số gần đây của tôi",
];

export const STAFF_QUICK_PROMPTS = [
  "Bệnh nhân này đang dùng thuốc gì?",
  "Cho tôi xem chỉ số gần đây của bệnh nhân này",
  "Lịch sử khám gần đây của bệnh nhân này",
  "Chẩn đoán hiện có của bệnh nhân này",
];
