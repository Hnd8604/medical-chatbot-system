package com.medicalchatbot.backend.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

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
        var request = new AuthRegisterRequest(
                "A",
                "invalid username",
                "not-an-email",
                "short",
                "short"
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("displayName", "username", "email", "password", "passwordConfirmation");
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
        var request = new AuthLinkPatientRequest("P".repeat(101), "24/09/2026", "0".repeat(31));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("patientId", "birthDate", "phone");
    }

    @Test
    void feedbackRejectsOversizedComment() {
        var request = new FeedbackRequest(5, "x".repeat(2001));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("comment");
    }
}
