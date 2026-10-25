package io.github.rj318.peerlens.sec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TickerEntryTest {

    @Test
    void padsCikToTenDigits() {
        assertThat(new TickerEntry(19617, "JPM", "JPMORGAN CHASE & CO").paddedCik()).isEqualTo("0000019617");
    }

    @Test
    void stripsStateOfIncorporationSuffix() {
        assertThat(new TickerEntry(1, "A", "BANK OF AMERICA CORP /DE/").displayName()).isEqualTo("BANK OF AMERICA CORP");
        assertThat(new TickerEntry(1, "B", "WELLS FARGO & COMPANY/MN").displayName()).isEqualTo("WELLS FARGO & COMPANY");
        assertThat(new TickerEntry(1, "C", "Apple Inc.").displayName()).isEqualTo("Apple Inc.");
    }
}
