package com.smarsh.discoveryhub.search.api;

import com.smarsh.discoveryhub.search.domain.model.FacetResult;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SearchService searchService;

    @Test
    void postSearchDelegatesToService() throws Exception {
        when(searchService.search(any(QuerySpec.class))).thenReturn(new SearchResult(1, 0, 25, List.of()));

        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content("""
                        {"q":"wire transfer"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void searchWithNullSpecUsesADefaultBuilderSpec() {
        // Bypasses HTTP: @RequestBody rejects a truly empty body before this
        // method runs, so the null-guard is only reachable via direct call
        // (defensive code for any future non-HTTP caller).
        when(searchService.search(any(QuerySpec.class))).thenReturn(new SearchResult(0, 0, 25, List.of()));

        new SearchController(searchService).search(null);

        var captor = org.mockito.ArgumentCaptor.forClass(QuerySpec.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void getSearchIsAConvenienceFormForTheDemoScript() throws Exception {
        when(searchService.search(any(QuerySpec.class))).thenReturn(new SearchResult(2, 0, 25, List.of()));

        mvc.perform(get("/api/search").param("q", "bahamas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void scopeIdsReturnsTheIdList() throws Exception {
        when(searchService.scopeIds(any(QuerySpec.class))).thenReturn(List.of("m1", "m2"));

        mvc.perform(post("/api/search/scope-ids").contentType(MediaType.APPLICATION_JSON).content("""
                        {"q":"wire"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids[0]").value("m1"))
                .andExpect(jsonPath("$.ids[1]").value("m2"));
    }

    @Test
    void scopeCountReturnsTheCount() throws Exception {
        when(searchService.count(any(QuerySpec.class))).thenReturn(42L);

        mvc.perform(post("/api/search/scope-count").contentType(MediaType.APPLICATION_JSON).content("""
                        {"q":"wire"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(42));
    }

    @Test
    void facetsReturnsAggregatedCounts() throws Exception {
        when(searchService.facets(any(QuerySpec.class)))
                .thenReturn(new FacetResult(10, Map.of("EMAIL", 6L), Map.of("c-1", 10L), 3, 2));

        mvc.perform(get("/api/search/facets").param("q", "wire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.onHoldCount").value(3));
    }
}
