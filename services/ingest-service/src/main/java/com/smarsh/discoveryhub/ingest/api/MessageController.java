package com.smarsh.discoveryhub.ingest.api;

import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.ingest.api.dto.AttachmentDto;
import com.smarsh.discoveryhub.ingest.api.dto.GenerateResponse;
import com.smarsh.discoveryhub.ingest.api.dto.IngestBatchRequest;
import com.smarsh.discoveryhub.ingest.api.dto.IngestMessageRequest;
import com.smarsh.discoveryhub.ingest.api.dto.IngestResponse;
import com.smarsh.discoveryhub.ingest.api.dto.ParticipantDto;
import com.smarsh.discoveryhub.ingest.application.CorpusGenerationUseCase;
import com.smarsh.discoveryhub.ingest.application.IngestMessageCommand;
import com.smarsh.discoveryhub.ingest.application.IngestMessageUseCase;
import com.smarsh.discoveryhub.ingest.application.IngestResult;
import com.smarsh.discoveryhub.ingest.domain.model.Participant;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Ingest endpoints (api-contracts &sect;ingest). The single-message and batch
 * endpoints only validate and publish {@code comm.ingest.v1} — they never
 * write to Mongo (FR-1.4: acceptance and durability are separate components).
 */
@RestController
@RequestMapping("/api")
public class MessageController {

    private final IngestMessageUseCase ingestUseCase;
    private final CorpusGenerationUseCase generateUseCase;

    public MessageController(IngestMessageUseCase ingestUseCase, CorpusGenerationUseCase generateUseCase) {
        this.ingestUseCase = ingestUseCase;
        this.generateUseCase = generateUseCase;
    }

    @PostMapping("/messages/ingest")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestMessageRequest request) {
        IngestResult result = ingestUseCase.execute(toCommand(request));
        return ResponseEntity.status(statusFor(result)).body(toResponse(result));
    }

    @PostMapping("/messages/ingest:batch")
    public ResponseEntity<List<IngestResponse>> ingestBatch(@Valid @RequestBody IngestBatchRequest request) {
        List<IngestResult> results = ingestUseCase.executeBatch(
                request.messages().stream().map(this::toCommand).toList());
        List<IngestResponse> body = results.stream().map(this::toResponse).toList();
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/admin/generate")
    public ResponseEntity<GenerateResponse> generate(@RequestParam(defaultValue = "10000") int count,
                                                       @RequestParam(defaultValue = "8") int attachmentPct) {
        var started = generateUseCase.start(count, attachmentPct);
        return ResponseEntity.accepted().body(
                new GenerateResponse(started.jobId(), started.count(), started.status()));
    }

    private IngestMessageCommand toCommand(IngestMessageRequest request) {
        List<Participant> participants = request.participants().stream()
                .map(p -> new Participant(p.name(), p.address(), p.role()))
                .toList();
        List<IngestMessageCommand.AttachmentRef> attachments = request.attachments().stream()
                .map(this::toAttachmentRef)
                .toList();
        return new IngestMessageCommand(
                request.externalMessageId(),
                request.type(),
                request.subject(),
                request.body(),
                request.threadId(),
                request.inReplyTo(),
                request.custodian(),
                participants,
                request.sentAt(),
                attachments);
    }

    private IngestMessageCommand.AttachmentRef toAttachmentRef(AttachmentDto dto) {
        return new IngestMessageCommand.AttachmentRef(dto.id(), dto.filename(), dto.contentType(), dto.size(), dto.content());
    }

    private IngestResponse toResponse(IngestResult result) {
        return new IngestResponse(result.messageId(), result.status().name(), CorrelationId.current());
    }

    private HttpStatus statusFor(IngestResult result) {
        return result.status() == IngestResult.Status.ACCEPTED ? HttpStatus.ACCEPTED : HttpStatus.OK;
    }
}
