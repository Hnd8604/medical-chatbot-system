package com.medicalchatbot.backend.entity;

import com.medicalchatbot.backend.enums.PatientRelationship;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

/** Grants an app account access to a FHIR patient record (see {@code docs/patient-link.md}). */
@Entity
@Table(name = "app_user_patient_links")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserPatientLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    User user;

    @Column(name = "fhir_patient_id", nullable = false, length = 100)
    String fhirPatientId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    PatientRelationship relationship = PatientRelationship.SELF;

    @Column(name = "is_primary", nullable = false)
    boolean primaryLink;
}
