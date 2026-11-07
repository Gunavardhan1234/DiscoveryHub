package com.smarsh.discoveryhub.archive.domain.port;

import com.smarsh.discoveryhub.events.HoldApplied;

/**
 * Outbound port for emitting {@code hold.applied.v1} from the archive
 * hold-applier after each batch. Consumed by hold-service's {@code hold-tracker}
 * group (to update {@code heldCount}/status) and by search-service (to mirror
 * the {@code onHold} flag into ES).
 */
public interface HoldAppliedPublisher {

    void publish(HoldApplied payload, String kafkaKey);
}
