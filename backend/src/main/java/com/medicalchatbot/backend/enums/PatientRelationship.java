package com.medicalchatbot.backend.enums;

/** How an app account relates to a linked FHIR patient (see {@code app_user_patient_links}). */
public enum PatientRelationship {
    /** The patient record belongs to the account owner. */
    SELF,
    DEPENDENT,
    CAREGIVER
}
