package com.smarsh.discoveryhub.generator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustodianRosterTest {

    @Test
    void hasAtLeastTwentyCustodians() {
        assertThat(CustodianRoster.CUSTODIANS).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    void idsAndEmailsAreUnique() {
        assertThat(CustodianRoster.CUSTODIANS).extracting(Custodian::id).doesNotHaveDuplicates();
        assertThat(CustodianRoster.CUSTODIANS).extracting(Custodian::email).doesNotHaveDuplicates();
    }

    @Test
    void everyCustodianHasPopulatedFields() {
        assertThat(CustodianRoster.CUSTODIANS).allSatisfy(c -> {
            assertThat(c.id()).isNotBlank();
            assertThat(c.name()).isNotBlank();
            assertThat(c.email()).isNotBlank();
        });
    }

    @Test
    void rosterIsFixedAndDeterministic() {
        assertThat(CustodianRoster.CUSTODIANS.getFirst().id()).isEqualTo("c-0001");
        assertThat(CustodianRoster.CUSTODIANS.getFirst().name()).isEqualTo("Ada Byron");
        assertThat(CustodianRoster.CUSTODIANS).hasSize(25);
    }
}
