package com.smarsh.discoveryhub.ingest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class IngestApplicationTest { @Test void scaffoldLoads() { assertThat(IngestApplication.class).isNotNull(); } }
