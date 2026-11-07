package com.smarsh.discoveryhub.hold.domain.model;

/**
 * Hold lifecycle states (moved from case-service when holds became a separate
 * service per the 7-service split in execution-plan.md).
 */
public enum HoldStatus { PENDING, ACTIVE, RELEASING, RELEASED, FAILED }
