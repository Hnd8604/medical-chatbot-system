package com.medicalchatbot.backend.exception;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public enum ErrorCode {

    /*
     * Error Code Convention
     *
     * 1000        : Success (ApiResponse.SUCCESS_CODE, never an error)
     * 1100 - 1199 : Authentication / Authorization
     * 1200 - 1299 : Account (registration, profile, password)
     * 1300 - 1399 : Password Reset
     * 1400 - 1499 : User Administration
     * 2000 - 2099 : Chat Session / Message / Feedback
     * 2100 - 2199 : Chat Export
     * 3000 - 3099 : Patient / Patient Link
     * 4000 - 4099 : Quota / Rate Limit / Quota Policy
     * 4100 - 4199 : Usage, Cost and Analytics Reports
     * 5000 - 5099 : External Services (chatbot-service, FHIR)
     * 6000 - 6099 : Notification
     * 6100 - 6199 : Alert
     * 7000 - 7099 : Backup / Restore
     * 9000 - 9099 : HTTP / Request / Platform
     * 9999        : Uncategorized Error
     *
     * A code is a client contract: never reuse one for a different meaning.
     */

    UNAUTHENTICATED(1101, "Bạn cần đăng nhập để tiếp tục.", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1102, "Bạn không có quyền truy cập tài nguyên này.", HttpStatus.FORBIDDEN),
    INVALID_CREDENTIALS(1103, "Tên đăng nhập/email hoặc mật khẩu không đúng.", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(1104, "Token không hợp lệ.", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(1105, "Phiên đăng nhập đã hết hạn.", HttpStatus.UNAUTHORIZED),
    TOKEN_REVOKED(1106, "Phiên đăng nhập đã hết hiệu lực.", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(1107, "Phiên đăng nhập đã hết hạn hoặc không hợp lệ.", HttpStatus.UNAUTHORIZED),
    ACCOUNT_NOT_EXISTED(1108, "Người dùng không tồn tại.", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(1109, "Tài khoản đã bị khóa.", HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(1110, "Tài khoản đã bị vô hiệu hóa.", HttpStatus.FORBIDDEN),

    USERNAME_EXISTED(1201, "Tên đăng nhập đã được sử dụng.", HttpStatus.CONFLICT),
    EMAIL_EXISTED(1202, "Email đã được sử dụng.", HttpStatus.CONFLICT),
    USERNAME_OR_EMAIL_EXISTED(1203, "Tên đăng nhập hoặc email đã được sử dụng.", HttpStatus.CONFLICT),
    WRONG_CURRENT_PASSWORD(1204, "Mật khẩu hiện tại không đúng.", HttpStatus.BAD_REQUEST),
    NEW_PASSWORD_SAME_AS_CURRENT(1205, "Mật khẩu mới phải khác mật khẩu hiện tại.", HttpStatus.BAD_REQUEST),
    PASSWORD_CONFIRMATION_MISMATCH(1206, "Xác nhận mật khẩu không khớp.", HttpStatus.BAD_REQUEST),
    PASSWORD_LENGTH_INVALID(1207, "Mật khẩu phải có từ 8 đến 72 byte.", HttpStatus.BAD_REQUEST),
    PASSWORD_TOO_WEAK(1208, "Mật khẩu phải có ít nhất một chữ và một số.", HttpStatus.BAD_REQUEST),

    RESET_CODE_INVALID(1301, "Mã xác thực không đúng hoặc đã hết hạn.", HttpStatus.BAD_REQUEST),
    RESET_TICKET_INVALID(1302, "Yêu cầu đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.", HttpStatus.BAD_REQUEST),

    USER_NOT_EXISTED(1401, "Không tìm thấy người dùng.", HttpStatus.NOT_FOUND),
    CANNOT_DEACTIVATE_SELF(1402, "Admin không thể tự khóa hoặc vô hiệu hóa chính mình.", HttpStatus.BAD_REQUEST),
    CANNOT_DEMOTE_SELF(1403, "Admin không thể tự hạ quyền của chính mình.", HttpStatus.BAD_REQUEST),

    CHAT_SESSION_NOT_EXISTED(2001, "Không tìm thấy phiên trò chuyện.", HttpStatus.NOT_FOUND),
    CHAT_SESSION_TITLE_BLANK(2002, "Tiêu đề hội thoại không được để trống.", HttpStatus.BAD_REQUEST),
    SEARCH_QUERY_TOO_LONG(2003, "Từ khóa tìm kiếm quá dài.", HttpStatus.BAD_REQUEST),
    MESSAGE_NOT_EXISTED(2004, "Không tìm thấy tin nhắn.", HttpStatus.NOT_FOUND),
    FEEDBACK_NOT_EXISTED(2005, "Không tìm thấy đánh giá.", HttpStatus.NOT_FOUND),
    FEEDBACK_EXISTED(2006, "Câu trả lời này đã được đánh giá.", HttpStatus.CONFLICT),
    EXPORT_FORMAT_INVALID(2101, "Định dạng xuất chỉ hỗ trợ pdf hoặc csv.", HttpStatus.BAD_REQUEST),

    PATIENT_LINK_REQUIRED(3001, "Tài khoản USER chưa được liên kết với hồ sơ FHIR nào.", HttpStatus.FORBIDDEN),
    PATIENT_ACCESS_DENIED(3002, "Tài khoản USER chỉ được truy cập hồ sơ FHIR đã liên kết với chính mình.",
            HttpStatus.FORBIDDEN),
    PATIENT_PROFILE_NOT_LINKED(3003, "Tài khoản chưa được liên kết với hồ sơ FHIR nào.", HttpStatus.NOT_FOUND),
    PATIENT_LINK_NOT_ALLOWED(3004, "Chỉ tài khoản USER mới cần liên kết hồ sơ bệnh nhân.", HttpStatus.FORBIDDEN),
    ACCOUNT_LINKED_TO_OTHER_PATIENT(3005, "Tài khoản đã được liên kết với hồ sơ FHIR khác.", HttpStatus.CONFLICT),
    PATIENT_LINKED_TO_OTHER_ACCOUNT(3006, "Hồ sơ bệnh nhân này đã được liên kết với tài khoản khác.",
            HttpStatus.CONFLICT),
    PATIENT_VERIFICATION_FAILED(3007, "Thông tin xác minh hồ sơ không khớp.", HttpStatus.UNPROCESSABLE_ENTITY),
    PATIENT_VERIFICATION_UNAVAILABLE(3008, "Không thể xác minh hồ sơ bệnh nhân với FHIR service, vui lòng thử lại sau.",
            HttpStatus.BAD_GATEWAY),
    PATIENT_ID_INVALID(3009, "Mã bệnh nhân không hợp lệ.", HttpStatus.BAD_REQUEST),
    BIRTH_DATE_INVALID(3010, "Ngày sinh phải có định dạng YYYY-MM-DD.", HttpStatus.BAD_REQUEST),
    PHONE_INVALID(3011, "Số điện thoại không hợp lệ.", HttpStatus.BAD_REQUEST),

    QUOTA_EXCEEDED(4001, "Bạn đã sử dụng hết hạn mức hiện tại.", HttpStatus.TOO_MANY_REQUESTS),
    RATE_LIMIT_EXCEEDED(4002, "Bạn thao tác quá nhanh, vui lòng thử lại sau ít phút.", HttpStatus.TOO_MANY_REQUESTS),
    QUOTA_POLICY_NOT_EXISTED(4003, "Không tìm thấy chính sách quota.", HttpStatus.NOT_FOUND),
    QUOTA_POLICY_NAME_EXISTED(4004, "Tên chính sách quota đã tồn tại.", HttpStatus.CONFLICT),
    QUOTA_POLICY_IN_USE(4005, "Không thể xóa chính sách quota đang được người dùng sử dụng.", HttpStatus.CONFLICT),
    QUOTA_POLICY_NOT_CONFIGURED(4006, "Chưa cấu hình chính sách quota.", HttpStatus.INTERNAL_SERVER_ERROR),

    DATE_RANGE_INVALID(4101, "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.", HttpStatus.BAD_REQUEST),
    DATE_RANGE_TOO_LONG(4102, "Khoảng thời gian thống kê quá dài.", HttpStatus.BAD_REQUEST),

    CHATBOT_REQUEST_REJECTED(5001, "Yêu cầu đã bị dịch vụ AI từ chối.", HttpStatus.BAD_REQUEST),
    CHATBOT_SERVICE_ERROR(5002, "Dịch vụ AI xử lý thất bại.", HttpStatus.BAD_GATEWAY),
    CHATBOT_UNAVAILABLE(5003, "Dịch vụ AI hiện không khả dụng.", HttpStatus.BAD_GATEWAY),
    CHATBOT_RESOURCE_NOT_FOUND(5004, "Không tìm thấy tài nguyên trong dịch vụ AI.", HttpStatus.NOT_FOUND),
    UPSTREAM_SERVICE_ERROR(5005, "Dịch vụ phụ thuộc xử lý thất bại.", HttpStatus.BAD_GATEWAY),

    NOTIFICATION_NOT_EXISTED(6001, "Không tìm thấy thông báo.", HttpStatus.NOT_FOUND),

    ALERT_NOT_EXISTED(6101, "Không tìm thấy cảnh báo.", HttpStatus.NOT_FOUND),

    BACKUP_DISABLED(7001, "Chức năng sao lưu đang bị tắt.", HttpStatus.SERVICE_UNAVAILABLE),
    BACKUP_IN_PROGRESS(7002, "Đang có một tiến trình sao lưu/khôi phục chạy, vui lòng đợi hoàn tất.",
            HttpStatus.CONFLICT),
    BACKUP_NOT_EXISTED(7003, "Không tìm thấy bản sao lưu.", HttpStatus.NOT_FOUND),
    BACKUP_NOT_RESTORABLE(7004, "Bản sao lưu này không có dữ liệu hợp lệ để khôi phục.", HttpStatus.BAD_REQUEST),

    MALFORMED_REQUEST(9001, "Nội dung yêu cầu không đúng định dạng.", HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED(9002, "Phương thức HTTP không được hỗ trợ.", HttpStatus.METHOD_NOT_ALLOWED),
    ENDPOINT_NOT_FOUND(9003, "Không tìm thấy API được yêu cầu.", HttpStatus.NOT_FOUND),
    PAYLOAD_TOO_LARGE(9004, "Dữ liệu tải lên vượt quá giới hạn cho phép.", HttpStatus.PAYLOAD_TOO_LARGE),
    DATA_CONFLICT(9005, "Dữ liệu xung đột với bản ghi hiện có.", HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE(9006, "Định dạng nội dung không được hỗ trợ.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    NOT_ACCEPTABLE(9007, "Định dạng phản hồi được yêu cầu không được hỗ trợ.", HttpStatus.NOT_ACCEPTABLE),
    ASYNC_REQUEST_TIMEOUT(9008, "Yêu cầu đã hết thời gian xử lý.", HttpStatus.SERVICE_UNAVAILABLE),
    VALIDATION_ERROR(9009, "Dữ liệu yêu cầu không hợp lệ.", HttpStatus.BAD_REQUEST),
    INVALID_ARGUMENT(9010, "Tham số yêu cầu không hợp lệ.", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(9011, "Không tìm thấy tài nguyên.", HttpStatus.NOT_FOUND),
    CONFLICT(9012, "Yêu cầu xung đột với trạng thái hiện tại của tài nguyên.", HttpStatus.CONFLICT),
    UNPROCESSABLE_ENTITY(9013, "Không thể xử lý dữ liệu yêu cầu.", HttpStatus.UNPROCESSABLE_ENTITY),
    TOO_MANY_REQUESTS(9014, "Có quá nhiều yêu cầu. Vui lòng thử lại sau.", HttpStatus.TOO_MANY_REQUESTS),
    SERVICE_UNAVAILABLE(9015, "Dịch vụ hiện không khả dụng.", HttpStatus.SERVICE_UNAVAILABLE),
    UNCATEGORIZED_EXCEPTION(9999, "Hệ thống phát sinh lỗi nội bộ.", HttpStatus.INTERNAL_SERVER_ERROR);

    int code;
    String message;
    HttpStatusCode statusCode;

    ErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }

    /** Closest application error for a bare HTTP status (framework exceptions, upstream errors). */
    public static ErrorCode fromStatus(HttpStatusCode status) {
        if (status == null) {
            return UNCATEGORIZED_EXCEPTION;
        }
        return switch (status.value()) {
            case 400 -> INVALID_ARGUMENT;
            case 401 -> UNAUTHENTICATED;
            case 403 -> UNAUTHORIZED;
            case 404 -> RESOURCE_NOT_FOUND;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> UNPROCESSABLE_ENTITY;
            case 429 -> TOO_MANY_REQUESTS;
            case 502 -> UPSTREAM_SERVICE_ERROR;
            case 503, 504 -> SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? INVALID_ARGUMENT : UNCATEGORIZED_EXCEPTION;
        };
    }
}
