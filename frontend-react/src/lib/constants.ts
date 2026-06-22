export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, "") || "";

export const ACCESS_TOKEN_KEY = "medical_chatbot_access_token";

export const TEXT = {
  loginTitle: "Medical Chatbot",
  loginSubtitle: "Đăng nhập để bắt đầu hỏi đáp dữ liệu FHIR theo đúng quyền truy cập.",
  loginButton: "Đăng nhập",
  logoutButton: "Đăng xuất",
  usernameLabel: "Tên đăng nhập hoặc email",
  passwordLabel: "Mật khẩu",
  newChat: "Hội thoại mới",
  refresh: "Làm mới",
  searchSessions: "Tìm hội thoại",
  exportPdf: "Xuất PDF",
  exportCsv: "Xuất CSV",
  emptySessions: "Chưa có hội thoại.",
  emptySessionSearch: "Không tìm thấy hội thoại phù hợp.",
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
