package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

// Repository Pattern + Dependency Inversion: this is only an interface — CaseService depends on
// this abstraction, never on Hibernate/JDBC directly. Spring Data generates the runtime
// implementation as a dynamic Proxy at startup, so no hand-written implementation class exists
// anywhere in this codebase; extending JpaRepository<CaseEntity, UUID> (generics: entity type +
// id type) is enough to get save/findById/existsById/etc. for free.
public interface CaseRepository extends JpaRepository<CaseEntity, UUID> {

    // @Query with JPQL: a custom query for a case where Spring Data's method-name-derived query
    // syntax can't express the "optional filter" logic (status/q may each be null and should then
    // be ignored) — the cast(:x as string) is null checks let one query serve every combination of
    // filters instead of building the query string dynamically in Java.
    @Query("""
            select c from CaseEntity c
            where (cast(:status as string) is null or c.status = :status)
              and (cast(:q as string) is null or lower(c.name) like concat('%', lower(cast(:q as string)), '%'))
            """)
    Page<CaseEntity> search(CaseStatus status, String q, Pageable pageable);
}
