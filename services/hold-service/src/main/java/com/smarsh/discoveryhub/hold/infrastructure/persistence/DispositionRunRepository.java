package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispositionRunRepository extends JpaRepository<DispositionRunEntity, UUID> {

    List<DispositionRunEntity> findAllByOrderByStartedAtDesc();

    Optional<DispositionRunEntity> findFirstByOrderByStartedAtDesc();
}
