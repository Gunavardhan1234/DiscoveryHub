package com.smarsh.discoveryhub.caseapp.domain.model;

// Enum: restricts a case's status to a fixed, closed set of valid values instead of a free-text
// String, so illegal statuses are caught by the compiler/JPA mapping rather than at runtime (FR-2.2).
public enum CaseStatus { DRAFT, ACTIVE, UNDER_REVIEW, CLOSED }
