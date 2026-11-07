package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {
    List<OutboxEntity> findByPublishedAtIsNullOrderByCreatedAtAsc();
}
