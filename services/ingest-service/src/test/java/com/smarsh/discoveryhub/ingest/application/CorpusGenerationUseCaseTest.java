package com.smarsh.discoveryhub.ingest.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

/**
 * FR-1.3: the generator behind {@code POST /admin/generate} must produce a
 * corpus where a configurable share of messages carry attachments of varied
 * type/size — not the hardcoded empty list it shipped with originally.
 * Generation runs on a background thread, so tests poll for the expected
 * number of {@code execute} calls rather than blocking on {@code start}.
 */
class CorpusGenerationUseCaseTest {

    private final IngestMessageUseCase ingestUseCase = mock(IngestMessageUseCase.class);
    private final CorpusGenerationUseCase generator = new CorpusGenerationUseCase(ingestUseCase);

    @Test
    void hundredPercentAttachmentPctGivesEveryMessageOneOrTwoVariedAttachments() {
        generator.start(20, 100);

        List<IngestMessageCommand> commands = awaitCommands(20);
        assertThat(commands).hasSize(20);
        for (IngestMessageCommand command : commands) {
            assertThat(command.attachments()).isNotEmpty();
            assertThat(command.attachments().size()).isBetween(1, 2);
            for (IngestMessageCommand.AttachmentRef ref : command.attachments()) {
                assertThat(ref.filename()).isNotBlank();
                assertThat(ref.contentType()).isNotBlank();
                assertThat(ref.size()).isPositive();
                // Real bytes, not metadata-only: the generator is the sole source of demo
                // data, so it must exercise the same claim-check -> S3 path a real upload would.
                assertThat(ref.contentBase64()).isNotBlank();
                assertThat(Base64.getDecoder().decode(ref.contentBase64())).hasSize((int) ref.size());
            }
        }
        // varied types across the run, not one type repeated for everything
        long distinctTypes = commands.stream()
                .flatMap(c -> c.attachments().stream())
                .map(IngestMessageCommand.AttachmentRef::contentType)
                .distinct()
                .count();
        assertThat(distinctTypes).isGreaterThan(1);
    }

    @Test
    void zeroAttachmentPctGivesNoAttachments() {
        generator.start(10, 0);

        List<IngestMessageCommand> commands = awaitCommands(10);
        assertThat(commands).allSatisfy(c -> assertThat(c.attachments()).isEmpty());
    }

    @Test
    void defaultStartUsesAtLeastFivePercentAttachmentRate() {
        // Large enough sample that an 8% default rate should produce at least one attachment.
        generator.start(200);

        List<IngestMessageCommand> commands = awaitCommands(200);
        long withAttachments = commands.stream().filter(c -> !c.attachments().isEmpty()).count();
        assertThat(withAttachments).isGreaterThan(0);
    }

    /**
     * Polls raw invocation count until generation (running on its own
     * background thread) has produced at least {@code expected} calls, then
     * captures once — capturing inside the retry loop itself would
     * double-count across attempts.
     */
    private List<IngestMessageCommand> awaitCommands(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && mockingDetails(ingestUseCase).getInvocations().size() < expected) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        ArgumentCaptor<IngestMessageCommand> captor = ArgumentCaptor.forClass(IngestMessageCommand.class);
        verify(ingestUseCase, atLeast(expected)).execute(captor.capture());
        return captor.getAllValues();
    }
}
