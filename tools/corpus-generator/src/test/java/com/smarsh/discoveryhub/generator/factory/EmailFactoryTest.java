package com.smarsh.discoveryhub.generator.factory;

import com.smarsh.discoveryhub.generator.model.Custodian;
import com.smarsh.discoveryhub.generator.model.CustodianRoster;
import com.smarsh.discoveryhub.generator.model.IngestRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EmailFactoryTest {

    private static final Custodian SENDER = CustodianRoster.CUSTODIANS.get(0);
    private static final Custodian RECIPIENT = CustodianRoster.CUSTODIANS.get(1);
    private static final Instant SENT_AT = Instant.parse("2026-09-14T10:00:00Z");

    private final EmailFactory factory = new EmailFactory();

    @Test
    void typeIsEmail() {
        assertThat(factory.type()).isEqualTo("EMAIL");
    }

    @Test
    void createsPopulatedEmailRequest() {
        IngestRequest req = factory.create(3, SENDER, RECIPIENT, SENT_AT, "thread-1", null, 0);

        assertThat(req.type()).isEqualTo("EMAIL");
        assertThat(req.subject()).isNotBlank();
        assertThat(req.body()).isNotBlank();
        assertThat(req.threadId()).isEqualTo("thread-1");
        assertThat(req.inReplyTo()).isNull();
        assertThat(req.custodian()).isEqualTo(SENDER.id());
        assertThat(req.sentAt()).isEqualTo(SENT_AT);
        assertThat(req.externalMessageId()).startsWith("ext-");
    }

    @Test
    void participantsAreFromAndTo() {
        IngestRequest req = factory.create(0, SENDER, RECIPIENT, SENT_AT, "thread-1", null, 0);

        assertThat(req.participants()).hasSize(2);
        assertThat(req.participants().get(0).name()).isEqualTo(SENDER.name());
        assertThat(req.participants().get(0).address()).isEqualTo(SENDER.email());
        assertThat(req.participants().get(0).role()).isEqualTo("FROM");
        assertThat(req.participants().get(1).name()).isEqualTo(RECIPIENT.name());
        assertThat(req.participants().get(1).role()).isEqualTo("TO");
    }

    @Test
    void zeroAttachmentPctProducesNoAttachments() {
        IngestRequest req = factory.create(0, SENDER, RECIPIENT, SENT_AT, "thread-1", null, 0);

        assertThat(req.attachments()).isEmpty();
    }

    @Test
    void hundredAttachmentPctAlwaysProducesAttachments() {
        for (int i = 0; i < 25; i++) {
            IngestRequest req = factory.create(i, SENDER, RECIPIENT, SENT_AT, "thread-1", null, 100);

            assertThat(req.attachments()).hasSizeBetween(1, 2);
            req.attachments().forEach(a -> {
                assertThat(a.id()).startsWith(req.externalMessageId() + "-a");
                assertThat(a.filename()).contains(".");
                assertThat(a.contentType()).isNotBlank();
                assertThat(a.size()).isBetween(1024L, 2_001_023L);
            });
        }
    }

    @Test
    void creationIsDeterministicPerIndex() {
        IngestRequest a = factory.create(7, SENDER, RECIPIENT, SENT_AT, "thread-1", null, 50);
        IngestRequest b = factory.create(7, SENDER, RECIPIENT, SENT_AT, "thread-1", null, 50);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void inReplyToIsCarriedThrough() {
        IngestRequest req = factory.create(1, SENDER, RECIPIENT, SENT_AT, "thread-1", "ext-abc123", 0);

        assertThat(req.inReplyTo()).isEqualTo("ext-abc123");
    }
}
