package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.application.DltReplayService;
import com.smarsh.discoveryhub.archive.application.ReindexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin endpoints for the archive (Day-3 M1).
 *
 * <p>{@code POST /admin/reindex} reads Mongo — the system of record — and
 * republishes {@code comm.stored.v1} for every stored message. The search
 * indexer rebuilds ES from those events, proving ES is a rebuildable
 * projection, never the source of truth (architecture §3.4). This is stronger
 * than replaying the Kafka topic: it regenerates the event stream from the
 * durable store even if the topic had retention gaps.
 *
 * <p>{@code POST /admin/dlt/replay} forwards every record on
 * {@code comm.ingest.v1.DLT} back onto {@code comm.ingest.v1} for reprocessing
 * — the standard Kafka dead-letter replay tooling.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ReindexService reindexService;
    private final DltReplayService dltReplayService;

    public AdminController(ReindexService reindexService, DltReplayService dltReplayService) {
        this.reindexService = reindexService;
        this.dltReplayService = dltReplayService;
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        long republished = reindexService.republishAll();
        return ResponseEntity.accepted().body(Map.of(
                "status", "ACCEPTED",
                "republished", republished,
                "note", "Read Mongo (source of truth) and republished comm.stored.v1; "
                        + "the search indexer rebuilds ES from the republished events"));
    }

    @PostMapping("/dlt/replay")
    public ResponseEntity<Map<String, Object>> replayDlt() {
        long replayed = dltReplayService.replayAll();
        return ResponseEntity.accepted().body(Map.of(
                "status", "ACCEPTED",
                "replayed", replayed,
                "note", "Replayed comm.ingest.v1.DLT records back onto comm.ingest.v1"));
    }
}
