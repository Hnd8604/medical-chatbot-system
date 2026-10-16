package com.medicalchatbot.backend.dto.response.auth;

/** One-time ticket proving the reset code was verified; required to set the new password. */
public record ResetTicketResponse(String resetTicket) {
}
