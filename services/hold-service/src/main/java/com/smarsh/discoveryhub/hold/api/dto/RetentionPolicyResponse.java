package com.smarsh.discoveryhub.hold.api.dto;

import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyEntity;

import java.util.List;

/**
 * Retention policy list response (FR-5.1). Each policy pins a retention
 * duration (ISO-8601) to a communication type; the disposition executor (FR-5)
 * deletes messages older than this duration that are not on hold.
 */
public record RetentionPolicyResponse(List<Policy> policies) {

    public record Policy(String commType, String duration) {
        public static Policy from(RetentionPolicyEntity e) {
            return new Policy(e.commType, e.duration);
        }
    }
}
