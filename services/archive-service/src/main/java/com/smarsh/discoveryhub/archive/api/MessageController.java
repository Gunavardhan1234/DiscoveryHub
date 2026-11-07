package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.application.RetentionGuard;
import com.smarsh.discoveryhub.archive.domain.model.MessageNotFoundException;
import com.smarsh.discoveryhub.archive.domain.port.MessageReadPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final RetentionGuard retentionGuard;
    private final MessageReadPort messageRead;

    public MessageController(RetentionGuard retentionGuard, MessageReadPort messageRead) {
        this.retentionGuard = retentionGuard;
        this.messageRead = messageRead;
    }

    /** Authoritative single read from Mongo (FR-1.4, api-contracts.md §archive). */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return messageRead.findById(id)
                .orElseThrow(() -> new MessageNotFoundException(id));
    }

    /**
     * Bulk read (FR-1.4): one round-trip for up to a batch of ids — the export
     * worker's message source. Unknown ids are simply absent from the result.
     */
    @GetMapping
    public Map<String, Object> bulk(@RequestParam(required = false) String ids) {
        List<String> requested = ids == null || ids.isBlank()
                ? List.of()
                : Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        return Map.of("messages", requested.isEmpty() ? List.of() : messageRead.findByIds(requested));
    }

    /**
     * Hold-guarded delete (FR-4.2/FR-4.6): routed through the
     * {@link RetentionGuard} protection proxy, which rejects with
     * {@code 409 MESSAGE_ON_HOLD} + the holds list while the message's hold set
     * is non-empty and delegates to the defence-in-depth
     * {@code deleteOne({_id, holds:{$size:0}})} otherwise.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        retentionGuard.delete(id);
        return ResponseEntity.noContent().build();
    }
}
