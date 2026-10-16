package com.medicalchatbot.backend.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.medicalchatbot.backend.dto.request.auth.RegisterRequest;
import com.medicalchatbot.backend.dto.request.auth.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.request.chat.ChatRequest;
import com.medicalchatbot.backend.dto.request.chat.FeedbackRequest;
import com.medicalchatbot.backend.dto.request.patient.LinkPatientRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void registrationRejectsInvalidStructuralInput() {
        var request = new RegisterRequest("A", "invalid username", "not-an-email", "short", "short");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("displayName", "username", "email", "password", "passwordConfirmation");
    }

    @Test
    void profileUpdateRejectsShortNameAndEmailWithoutDomain() {
        var request = new UpdateProfileRequest("A", "user@localhost");

        assertThat(validator.validate(request))
                .extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder("Tên hiển thị phải có từ 2 đến 100 ký tự.", "Email không hợp lệ.");
    }

    @Test
    void usernameRuleIsExplainedInVietnamese() {
        var request = new RegisterRequest("User Demo", "no spaces", "user@example.com", "UserDemo123!", "UserDemo123!");

        assertThat(validator.validate(request))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(
                        "Tên đăng nhập phải có 3-30 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
    }

    @Test
    void chatRejectsOversizedMessages() {
        var request = new ChatRequest(null, null, "x".repeat(8001));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("message");
    }

    @Test
    void patientLinkRequiresIsoDateShapeAndBoundedIdentifiers() {
        var request = new LinkPatientRequest("P".repeat(101), "24/09/2026", "0".repeat(31));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("patientId", "birthDate", "phone");
    }

    @Test
    void patientLinkAcceptsIsoDate() {
        var request = new LinkPatientRequest("BN2026-00001", "1990-05-12", "0901234567");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void feedbackRejectsOversizedComment() {
        var request = new FeedbackRequest(5, "x".repeat(2001));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("comment");
    }
}
