package com.smarsh.discoveryhub.search.domain.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuerySpecTest {
    @Test void builderUsesUsefulDefaults() {
        QuerySpec spec = QuerySpec.builder().q("wire transfer").build();
        assertThat(spec.q()).isEqualTo("wire transfer");
        assertThat(spec.size()).isEqualTo(25);
        assertThat(spec.sort()).isEqualTo(QuerySpec.Sort.RELEVANCE);
    }
}
