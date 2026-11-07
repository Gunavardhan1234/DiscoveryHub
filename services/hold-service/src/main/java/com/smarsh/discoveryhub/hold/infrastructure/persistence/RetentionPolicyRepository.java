package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicyEntity, String> {

    List<RetentionPolicyEntity> findAllByOrderByCommTypeAsc();
}
