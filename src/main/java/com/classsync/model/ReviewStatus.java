package com.classsync.model;

/** Note review state machine: PENDING -> APPROVED | REJECTED; resubmission returns to PENDING. */
public enum ReviewStatus {
    PENDING, APPROVED, REJECTED
}
