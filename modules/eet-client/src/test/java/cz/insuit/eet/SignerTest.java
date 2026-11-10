package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignerTest {

    @Test
    void theEicIsTheCertificatesCn() throws Exception {
        assertEquals(Optional.of("CZ00000019"), TestKeys.signer().eic());
    }

    @Test
    void notAfterIsWhenTheCertificateExpires() throws Exception {
        Instant notAfter = TestKeys.signer().notAfter();
        assertEquals(TestKeys.signer().certificate().getNotAfter().toInstant(), notAfter);
        assertTrue(notAfter.isAfter(Instant.now().plus(Duration.ofDays(300))), "keytool makes it for ten years");
    }
}
