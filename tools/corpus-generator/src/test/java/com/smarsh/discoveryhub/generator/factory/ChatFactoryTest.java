package com.smarsh.discoveryhub.generator.factory;

import com.smarsh.discoveryhub.generator.model.Custodian;
import com.smarsh.discoveryhub.generator.model.CustodianRoster;
import com.smarsh.discoveryhub.generator.model.IngestRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChatFactoryTest {

    private static final Custodian SENDER = CustodianRoster.CUSTODIANS.get(2);
    private static final Custodian RECIPIENT = CustodianRoster.CUSTODIANS.get(3);
    private static final Instant SENT_AT = Instant.parse("2026-09-14T11:30:00Z");

    private final ChatFactory factory = new ChatFactory();

    @Test
    void typeIsChat() {
        assertThat(factory.type()).isEqualTo("CHAT");
    }

    @Test
    void createsChatRequestWithoutSubjectOrAttachments() {
        IngestRequest req = factory.create(0, SENDER, RECIPIENT, SENT_AT, "thread-9", "ext-prev", 100);

        assertThat(req.type()).isEqualTo("CHAT");
        assertThat(req.subject()).isNull();
        assertThat(req.body()).isNotBlank();
        assertThat(req.attachments()).isEmpty();
        assertThat(req.threadId()).isEqualTo("thread-9");
        assertThat(req.inReplyTo()).isEqualTo("ext-prev");
        assertThat(req.custodian()).isEqualTo(SENDER.id());
        assertThat(req.externalMessageId()).startsWith("ext-");
        assertThat(req.participants()).extracting(IngestRequest.Participant::role)
                .containsExactly("FROM", "TO");
    }

    @Test
    void bodiesCycleAcrossIndexes() {
        IngestRequest first = factory.create(0, SENDER, RECIPIENT, SENT_AT, "t", null, 0);
        IngestRequest wrapped = factory.create(15, SENDER, RECIPIENT, SENT_AT, "t", null, 0);

        assertThat(first.body()).isEqualTo(wrapped.body());
        assertThat(factory.create(1, SENDER, RECIPIENT, SENT_AT, "t", null, 0).body())
                .isNotEqualTo(first.body());
    }
}
