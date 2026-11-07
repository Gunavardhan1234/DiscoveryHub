package com.smarsh.discoveryhub.search.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.smarsh.discoveryhub.search.domain.model.FacetResult;
import com.smarsh.discoveryhub.search.domain.model.IndexedMessage;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test against a real Elasticsearch. Proves the Day-1 hard gate: a
 * message indexed via the consumer path is searchable, with highlighting, filters,
 * paging, count/scope-ids, and the reference-counted onHold projection.
 *
 * <p>By default a Testcontainers ES is started. To run against an externally
 * managed ES (e.g. when the Docker daemon is not discoverable by Testcontainers),
 * set the system property {@code -Dtest.es.uri=http://localhost:9201} (or the
 * {@code TEST_ES_URI} env var) and the container is skipped. Standalone — no Spring
 * context — so it exercises only the ES adapter + translator and does not need Kafka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIntegrationIT {

    private static final String EXTERNAL_URI =
            System.getProperty("test.es.uri", System.getenv().getOrDefault("TEST_ES_URI", ""));
    private static final String INDEX = "messages-search-it";

    private ElasticsearchContainer container;
    private RestClient restClient;
    private ElasticsearchClient client;
    private ElasticsearchSearchPort port;

    @BeforeAll
    void setUp() {
        String uri;
        if (EXTERNAL_URI != null && !EXTERNAL_URI.isBlank()) {
            uri = EXTERNAL_URI;
        } else {
            container = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.1")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    // The default ElasticsearchContainer wait strategy matches a log line
                    // that shifts across ES versions/CI load, causing flaky startup
                    // timeouts. Waiting on the HTTP endpoint itself is more reliable.
                    .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(3));
            container.start();
            uri = "http://" + container.getHttpHostAddress();
        }
        restClient = RestClient.builder(HttpHost.create(uri)).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        try {
            client.indices().delete(d -> d.index(INDEX).ignoreUnavailable(true));
        } catch (Exception ignored) {
        }
        new IndexInitializer(client, new DefaultResourceLoader(), INDEX,
                "classpath:es/messages-index.json").run(null);
        port = new ElasticsearchSearchPort(client, new EsQueryTranslator(INDEX), new DefaultResourceLoader(), INDEX,
                "classpath:es/messages-index.json");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (restClient != null) {
            restClient.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void indexedMessageIsSearchableWithHighlight() throws Exception {
        port.index(msg("msg-phoenix", "EMAIL", "Project Phoenix quarterly review",
                "Please confirm the wire routing for Project Phoenix", "c-0007", true));
        refresh();

        SearchResult result = port.search(QuerySpec.builder().q("Project Phoenix").build());
        assertThat(result.total()).isGreaterThanOrEqualTo(1);
        assertThat(result.hits()).isNotEmpty();
        var hit = result.hits().get(0);
        assertThat(hit.messageId()).isEqualTo("msg-phoenix");
        assertThat(hit.snippet()).contains("<mark>");
        assertThat(hit.snippet()).containsIgnoringCase("phoenix");
        assertThat(hit.custodianId()).isEqualTo("c-0007");
    }

    /**
     * FR-3.3: a query matching only the participant field (not subject/body)
     * must still come back with a highlighted snippet. {@code msg()} always
     * sets {@code participantsText = "Ada Byron ada@fict.example"}, which
     * doesn't appear in this message's subject/body, so any {@code <mark>}
     * here can only have come from the {@code participantsText} highlighter.
     */
    @Test
    void participantOnlyMatchIsHighlighted() throws Exception {
        port.index(msg("msg-participant-only", "EMAIL", "Q3 budget notes",
                "Please review the attached spreadsheet", "c-0021", false));
        refresh();

        SearchResult result = port.search(QuerySpec.builder().q("Ada Byron").build());
        var hit = result.hits().stream()
                .filter(h -> "msg-participant-only".equals(h.messageId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected msg-participant-only in results"));
        assertThat(hit.snippet()).contains("<mark>");
        assertThat(hit.snippet()).containsIgnoringCase("ada");
    }

    @Test
    void filtersByCustodianAndType() throws Exception {
        port.index(msg("msg-filt-1", "EMAIL", "Atlas budget notes", "Atlas budget for Q2", "c-0007", false));
        port.index(msg("msg-filt-2", "CHAT", "Atlas standup", "Atlas standup notes", "c-0012", false));
        refresh();

        SearchResult byCustodian = port.search(QuerySpec.builder().q("Atlas")
                .filters(new QuerySpec.Filters(null, null, List.of(), List.of("c-0007"), null, null, null)).build());
        assertThat(byCustodian.hits()).allSatisfy(h -> assertThat(h.custodianId()).isEqualTo("c-0007"));

        SearchResult byType = port.search(QuerySpec.builder().q("Atlas")
                .filters(new QuerySpec.Filters(null, null, List.of("CHAT"), List.of(), null, null, null)).build());
        assertThat(byType.hits()).allSatisfy(h -> assertThat(h.type()).isEqualTo("CHAT"));
    }

    @Test
    void countsAndStreamsIds() throws Exception {
        port.index(msg("msg-acme-1", "EMAIL", "Acme deal", "Acme deal terms one", "c-0007", false));
        port.index(msg("msg-acme-2", "EMAIL", "Acme deal", "Acme deal terms two", "c-0008", false));
        refresh();

        long count = port.count(QuerySpec.builder().q("Acme deal").build());
        assertThat(count).isGreaterThanOrEqualTo(2);

        List<String> ids = port.scopeIds(QuerySpec.builder().q("Acme deal").build());
        assertThat(ids).contains("msg-acme-1", "msg-acme-2");
    }

    @Test
    void paginatesResults() throws Exception {
        port.index(msg("msg-page-1", "EMAIL", "Bravo review", "Bravo review item one", "c-0007", false));
        port.index(msg("msg-page-2", "EMAIL", "Bravo review", "Bravo review item two", "c-0007", false));
        port.index(msg("msg-page-3", "EMAIL", "Bravo review", "Bravo review item three", "c-0007", false));
        refresh();

        SearchResult page0 = port.search(QuerySpec.builder().q("Bravo review").page(0).size(2).build());
        assertThat(page0.hits()).hasSize(2);
        assertThat(page0.total()).isGreaterThanOrEqualTo(3);

        SearchResult page1 = port.search(QuerySpec.builder().q("Bravo review").page(1).size(2).build());
        assertThat(page1.hits()).hasSize(1);
    }

    @Test
    void holdFlagIsSearchableAndReferenceCounted() throws Exception {
        port.index(msg("msg-hold-1", "EMAIL", "Cerberus memo", "Cerberus confidential memo", "c-0007", false));
        refresh();

        // Two overlapping holds cover the message.
        port.applyHold("h-1", List.of("msg-hold-1"), true);
        port.applyHold("h-2", List.of("msg-hold-1"), true);
        refresh();
        assertThat(onHoldCount()).isGreaterThanOrEqualTo(1);

        // Releasing one hold must NOT unprotect the message (the other still covers it).
        port.applyHold("h-1", List.of("msg-hold-1"), false);
        refresh();
        assertThat(onHoldCount()).isGreaterThanOrEqualTo(1);

        // Releasing the last hold unprotects it.
        port.applyHold("h-2", List.of("msg-hold-1"), false);
        refresh();
        assertThat(notOnHoldCount()).isGreaterThanOrEqualTo(1);
        assertThat(onHoldCount()).isZero();
    }

    @Test
    void facetsReturnsAggregations() throws Exception {
        port.index(msg("msg-facet-1", "EMAIL", "Delta facets", "Delta facet body one", "c-0007", true));
        port.index(msg("msg-facet-2", "CHAT", "Delta facets", "Delta facet body two", "c-0012", false));
        refresh();

        FacetResult facets = port.facets(QuerySpec.builder().q("Delta facets").build());
        assertThat(facets.total()).isGreaterThanOrEqualTo(2);
        assertThat(facets.byType()).isNotEmpty();
        assertThat(facets.byCustodian()).isNotEmpty();
        assertThat(facets.hasAttachmentCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void indexBatchBulkUpsertsMultipleDocuments() throws Exception {
        List<IndexedMessage> batch = List.of(
                msg("msg-batch-1", "EMAIL", "Echo batch", "Echo batch one", "c-0007", false),
                msg("msg-batch-2", "EMAIL", "Echo batch", "Echo batch two", "c-0008", false),
                msg("msg-batch-3", "EMAIL", "Echo batch", "Echo batch three", "c-0009", false));
        port.indexBatch(batch);
        refresh();
        long count = port.count(QuerySpec.builder().q("Echo batch").build());
        assertThat(count).isGreaterThanOrEqualTo(3);
    }

    @Test
    void scopeIdsStreamsAllMatchingIdsViaPit() throws Exception {
        port.index(msg("msg-pit-1", "EMAIL", "Foxtrot pit", "Foxtrot pit one", "c-0007", false));
        port.index(msg("msg-pit-2", "EMAIL", "Foxtrot pit", "Foxtrot pit two", "c-0007", false));
        port.index(msg("msg-pit-3", "EMAIL", "Foxtrot pit", "Foxtrot pit three", "c-0007", false));
        refresh();

        List<String> ids = port.scopeIds(QuerySpec.builder().q("Foxtrot pit").build());
        assertThat(ids).contains("msg-pit-1", "msg-pit-2", "msg-pit-3");
    }

    @Test
    void dropAndRecreateIndexClearsAndRebuilds() throws Exception {
        port.index(msg("msg-drop-1", "EMAIL", "Golf drop", "Golf drop body", "c-0007", false));
        refresh();
        assertThat(port.count(QuerySpec.builder().q("Golf drop").build())).isGreaterThanOrEqualTo(1);

        port.dropAndRecreateIndex();
        refresh();
        // After drop + recreate, the index is empty.
        assertThat(port.count(QuerySpec.builder().q("Golf drop").build())).isZero();
    }

    private long onHoldCount() {
        return port.count(QuerySpec.builder().q("Cerberus")
                .filters(new QuerySpec.Filters(null, null, List.of(), List.of(), null, true, null)).build());
    }

    private long notOnHoldCount() {
        return port.count(QuerySpec.builder().q("Cerberus")
                .filters(new QuerySpec.Filters(null, null, List.of(), List.of(), null, false, null)).build());
    }

    private void refresh() throws Exception {
        client.indices().refresh(r -> r.index(INDEX));
    }

    private static IndexedMessage msg(String id, String type, String subject, String body,
                                      String custodianId, boolean hasAttachment) {
        return new IndexedMessage(id, type, subject, body,
                Instant.parse("2026-04-01T10:00:00Z").toString(), custodianId, null,
                "Ada Byron ada@fict.example", List.of(), List.of(), hasAttachment, false, List.of());
    }
}
