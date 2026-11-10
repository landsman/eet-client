package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SaleTest {

    private static final OffsetDateTime TIME = OffsetDateTime.parse("2027-01-09T16:45:36.789+01:00");

    @ParameterizedTest
    @CsvSource({"0,0.00", "5,0.05", "-5,-0.05", "100,1.00", "-12350,-123.50", "9999999999,99999999.99"})
    void writesAnAmountAsTheSchemaDoes(long halere, String written) {
        assertEquals(written, Sale.amount(halere));
    }

    @Test
    void dropsFractionsOfASecond() {
        assertEquals(TIME.withNano(0), sale("1", 1).time());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "pokladna-č1", "123456789012345678901", "a\tb"})
    void refusesARegisterIdTheSchemaWouldRefuse(String registerId) {
        assertThrows(IllegalArgumentException.class, () -> sale(registerId, 1));
    }

    @Test
    void refusesAnAmountOfAHundredMillion() {
        assertThrows(IllegalArgumentException.class, () -> sale("1", 10_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> sale("1", -10_000_000_000L));
    }

    @Test
    void refusesABadEicAndADelegationToItself() {
        assertThrows(IllegalArgumentException.class, () -> Sale.of("SK1234567890", 11, "1", "1", TIME, 1));
        assertThrows(IllegalArgumentException.class, () -> sale("1", 1).withDelegatingEic("CZ00000019"));
    }

    @Test
    void refusesAUnitTheTaxOfficeCouldNotHaveAssigned() {
        assertThrows(IllegalArgumentException.class, () -> Sale.of("CZ00000019", 0, "1", "1", TIME, 1));
        assertThrows(IllegalArgumentException.class, () -> Sale.of("CZ00000019", 1_000_000_000, "1", "1", TIME, 1));
    }

    private static Sale sale(String registerId, long total) {
        return Sale.of("CZ00000019", 11, registerId, "2027/1", TIME, total);
    }
}
