package com.smarsh.discoveryhub.caseapp.domain.port;

import com.smarsh.discoveryhub.events.AuditEventPayload;

/**
 * Outbound port for emitting {@code audit.events.v1} from case-service
 * (FR-7.1: case created/updated/transitioned, custodian added, evidence
 * added, search saved).
 */
// Dependency Inversion Principle: the service layer depends on this abstraction, not on
// KafkaTemplate or the Kafka client library directly — CaseService/CustodianService/etc. know
// nothing about messaging infrastructure, so they compile and unit-test without a broker.
// Interface Segregation: a single-method interface scoped to exactly what callers need (publish
// one audit event), so implementers/mocks aren't forced to satisfy unrelated messaging concerns.
public interface AuditEventPublisher {

    void publish(AuditEventPayload payload, String kafkaKey);
}
