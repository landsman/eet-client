package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Made-up sales sent to the state's playground with its shared test certificate, which stores nothing. Run by
 * {@code make live-test} only: the playground answers some networks and not others. Each response is written to
 * build/live/, which is where the recorded fixtures in src/test/resources came from.
 */
@Tag("live")
class PlaygroundTest {

    private static final String EIC = "CZ00000019";
    private static EetClient eet;

    @BeforeAll
    static void client() throws IOException, GeneralSecurityException {
        Path dir = Path.of(System.getProperty("eet.playground"));
        char[] password = Files.readString(dir.resolve("password_pokladni_cert_playground.txt"))
                .strip()
                .toCharArray();
        try (InputStream p12 = Files.newInputStream(dir.resolve("CA_EET-Playground-" + EIC + ".p12"))) {
            eet = EetClient.builder(Environment.PLAYGROUND, Signer.fromPkcs12(p12, password))
                    .build();
        }
    }

    @Test
    void registersASaleAndAResendGetsAPokOfItsOwn() throws IOException {
        Submission first = Submission.of(sale(11, 12350));
        Result.Accepted accepted = assertInstanceOf(Result.Accepted.class, eet.send(first), "first");
        assertTrue(accepted.test());
        assertTrue(accepted.pok().endsWith("-ff"), accepted.pok());
        assertEquals(List.of(), accepted.warnings());
        assertTrue(accepted.exchange().transactionId() != null);
        record("accepted", accepted);

        Result.Accepted again = assertInstanceOf(Result.Accepted.class, eet.send(first.resend()), "resend");
        assertNotEquals(accepted.pok(), again.pok());
    }

    @Test
    void aVerificationIsCheckedAndNotRegistered() throws IOException {
        Result result = eet.send(Submission.verification(sale(11, -500)));
        record("verified", assertInstanceOf(Result.Verified.class, result));
    }

    @Test
    void anOddUnitNumberIsRegisteredWithAWarning() throws IOException {
        Result.Accepted accepted = assertInstanceOf(Result.Accepted.class, eet.send(Submission.of(sale(5, 100))));
        assertEquals(6, accepted.warnings().get(0).code(), accepted.warnings().toString());
        record("warning", accepted);
    }

    private static Sale sale(int unit, long total) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Europe/Prague"));
        return Sale.of(EIC, unit, "eet-client-test", "live-" + System.nanoTime(), now, total);
    }

    private static void record(String name, Result result) throws IOException {
        Path dir = Files.createDirectories(Path.of("build/live"));
        Files.writeString(dir.resolve(name + "-request.xml"), result.exchange().request());
        String response = result.exchange().response();
        if (response != null) Files.writeString(dir.resolve(name + "-response.xml"), response);
    }
}
