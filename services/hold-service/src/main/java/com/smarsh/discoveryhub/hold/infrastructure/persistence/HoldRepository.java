package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import com.smarsh.discoveryhub.hold.domain.model.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<HoldEntity, UUID> {

    List<HoldEntity> findByCaseIdAndStatusIn(UUID caseId, List<HoldStatus> statuses);

    List<HoldEntity> findAllByOrderByPlacedAtAsc();

    List<HoldEntity> findByCaseIdOrderByPlacedAtAsc(UUID caseId);

    @Query("select h from HoldEntity h where h.caseId = :caseId and h.status in (com.smarsh.discoveryhub.hold.domain.model.HoldStatus.ACTIVE, com.smarsh.discoveryhub.hold.domain.model.HoldStatus.PENDING, com.smarsh.discoveryhub.hold.domain.model.HoldStatus.RELEASING)")
    List<HoldEntity> findActiveOrReleasingByCaseId(UUID caseId);
}
