package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the FR-5.1/FR-5.2 seed invariant. The scheduled disposition sweep
 * (FR-5.2) runs every 120s against these seeded policies, and the corpus
 * generator back-dates {@code sentAt} across the last
 * {@link RetentionSeeder#CORPUS_BACKDATE_DAYS} days. If a seeded retention is
 * ever shorter than that window, every seeded message becomes eligible on the
 * first tick and the sweep deletes the whole corpus — which is exactly why the
 * schedule used to ship disabled. These tests fail if anyone reintroduces
 * minute-level seeds.
 */
@ExtendWith(MockitoExtension.class)
class RetentionSeederTest {

    @Mock
    private RetentionPolicyRepository policies;

    @InjectMocks
    private RetentionSeeder seeder;

    @Test
    void everySeededRetentionOutlastsTheCorpusBackdateWindow() {
        Duration window = Duration.ofDays(RetentionSeeder.CORPUS_BACKDATE_DAYS);

        for (Map.Entry<String, String> entry : RetentionSeeder.DEFAULTS.entrySet()) {
            Duration seeded = Duration.parse(entry.getValue());
            assertThat(seeded)
                    .as("seeded retention for %s (%s) must exceed the generator's %s back-dating "
                                    + "window, or the scheduled sweep deletes the entire corpus",
                            entry.getKey(), entry.getValue(), window)
                    .isGreaterThan(window);
        }
    }

    @Test
    void seedsTheRealisticDefaultsNamedByFr51() {
        assertThat(RetentionSeeder.DEFAULTS)
                .containsExactlyInAnyOrderEntriesOf(Map.of("EMAIL", "P2555D", "CHAT", "P1095D"));
        assertThat(Duration.parse(RetentionSeeder.DEFAULTS.get("EMAIL")).toDays()).isEqualTo(7 * 365);
        assertThat(Duration.parse(RetentionSeeder.DEFAULTS.get("CHAT")).toDays()).isEqualTo(3 * 365);
    }

    @Test
    void insertsMissingPoliciesOnFirstBoot() {
        when(policies.findById(anyString())).thenReturn(Optional.empty());

        seeder.seed();

        ArgumentCaptor<RetentionPolicyEntity> saved = ArgumentCaptor.forClass(RetentionPolicyEntity.class);
        verify(policies, times(RetentionSeeder.DEFAULTS.size())).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(e -> e.commType, e -> e.duration)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("EMAIL", "P2555D"),
                        org.assertj.core.groups.Tuple.tuple("CHAT", "P1095D"));
        assertThat(saved.getAllValues()).allSatisfy(e -> assertThat(e.updatedAt).isNotNull());
    }

    /** An operator who drops CHAT to PT1M for a demo must not have it reset on restart. */
    @Test
    void neverOverwritesAnOperatorSetPolicy() {
        RetentionPolicyEntity existing = new RetentionPolicyEntity();
        existing.commType = "CHAT";
        existing.duration = "PT1M";
        when(policies.findById("CHAT")).thenReturn(Optional.of(existing));
        when(policies.findById("EMAIL")).thenReturn(Optional.of(existing));

        seeder.seed();

        verify(policies, never()).save(any());
    }
}
