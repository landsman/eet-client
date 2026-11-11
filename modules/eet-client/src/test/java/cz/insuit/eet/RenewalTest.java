package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The renewal client against a local stand-in for the CA, answering with the specification's own examples. */
class RenewalTest {

    private static final UUID ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    private HttpServer server;
    private final AtomicReference<String> seen = new AtomicReference<>("");
    private final AtomicReference<String> authorization = new AtomicReference<>("");
    private int status = 200;
    private String body = "";
    private Signer signer;
    private Renewal renewal;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.set(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        signer = TestKeys.signer();
        renewal = new Renewal(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                signer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        renewal.close();
        server.stop(0);
    }

    @Test
    void aRequestReturnsItsId() throws IOException {
        answer(201, "{\"reqId\":\"" + ID + "\"}");
        assertEquals(ID, renewal.request());
        assertEquals("POST /api/request/renew", seen.get());
    }

    @Test
    void theTokenIsSignedByTheCertificateBeingRenewedAndLivesUnderFiveMinutes() throws Exception {
        answer(201, "{\"reqId\":\"" + ID + "\"}");
        renewal.request();
        String[] parts = authorization.get().substring("Bearer ".length()).split("\\.", -1);
        String header = decode(parts[0]);
        String payload = decode(parts[1]);
        String thumbprint = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(signer.certificate().getEncoded()));
        assertEquals("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"x5t#S256\":\"" + thumbprint + "\"}", header);
        long iat = number(payload, "iat");
        long exp = number(payload, "exp");
        assertTrue(iat <= NOW.getEpochSecond() && NOW.getEpochSecond() < exp, payload);
        assertTrue(exp - iat <= Duration.ofMinutes(5).toSeconds(), payload);

        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(signer.certificate().getPublicKey());
        rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(rsa.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    void everyStateIsRead() throws IOException {
        answer(200, "{\"reqId\":\"" + ID + "\",\"state\":\"INPROCESS\",\"pollAfterSeconds\":2}");
        assertEquals(new Status.InProcess(Duration.ofSeconds(2)), renewal.status(ID));
        assertEquals("GET /api/request/" + ID + "/status", seen.get());
        answer(200, "{\"reqId\":\"" + ID + "\",\"state\":\"ISSUED\"}");
        assertInstanceOf(Status.Issued.class, renewal.status(ID));
        answer(200, "{\"reqId\":\"" + ID + "\",\"state\":\"DELIVERING\"}");
        assertInstanceOf(Status.Delivering.class, renewal.status(ID));
        answer(200, "{\"reqId\":\"" + ID + "\",\"state\":\"FINISHED\"}");
        assertInstanceOf(Status.Finished.class, renewal.status(ID));
        answer(
                200,
                "{\"reqId\":\"" + ID
                        + "\",\"state\":\"REJECTED\",\"rejectedReason\":\"Selhání kontroly parametrů žádosti\"}");
        assertEquals(new Status.Rejected("Selhání kontroly parametrů žádosti"), renewal.status(ID));
    }

    @Test
    void aClaimCarriesTheFileItsPasswordAndValidity() throws IOException {
        byte[] p12 = {1, 2, 3, 4};
        // The specification's example: sn unquoted, the times with a space rather than a T.
        answer(
                200,
                "{\"pkcs12\":\"" + Base64.getEncoder().encodeToString(p12) + "\",\"password\":\"ABcd1234\","
                        + "\"certificate\":{\"sn\":24961511666821687692,\"validFrom\":\"2026-01-04 11:13:33+00:00\","
                        + "\"validTo\":\"2027-01-04 11:13:33+00:00\",\"state\":\"VALID\"}}");
        Renewed renewed = renewal.claim(ID);
        assertEquals("POST /api/request/" + ID + "/claim-download", seen.get());
        assertArrayEquals(p12, renewed.pkcs12());
        assertArrayEquals("ABcd1234".toCharArray(), renewed.password());
        assertEquals("24961511666821687692", renewed.serialNumber());
        assertEquals(Instant.parse("2027-01-04T11:13:33Z"), renewed.validTo());
        assertTrue(!renewed.toString().contains("ABcd1234"), renewed.toString());
    }

    @Test
    void anAcknowledgementIsANoContent() throws IOException {
        answer(204, "");
        renewal.acknowledge(ID);
        assertEquals("POST /api/request/" + ID + "/ack-download", seen.get());
    }

    @Test
    void theOpenRequestsAreListed() throws IOException {
        answer(200, "{\"count\":1,\"data\":[\"" + ID + "\"]}");
        assertEquals(List.of(ID), renewal.notFinished());
        answer(200, "{\"count\":0}");
        assertEquals(List.of(), renewal.notFinished());
    }

    @Test
    void anErrorCarriesTheStatusAndTheCasCode() {
        answer(409, "{\"code\":5,\"message\":\"Conflict.\",\"additionalInfo\":\"Some more info.\"}");
        RenewalException e = assertThrows(RenewalException.class, () -> renewal.acknowledge(ID));
        assertEquals(409, e.status());
        assertEquals(5, e.code());
        assertEquals("HTTP 409, code 5: Conflict. Some more info.", e.getMessage());

        answer(502, "<html>bad gateway</html>");
        RenewalException html = assertThrows(RenewalException.class, () -> renewal.status(ID));
        assertEquals(502, html.status());
        assertNull(html.code());
    }

    @Test
    void anAnswerOfTheWrongShapeIsAnIoException() {
        answer(200, "{\"reqId\":\"" + ID + "\",\"state\":\"INPROCESS\",\"pollAfterSeconds\":\"soon\"}");
        assertThrows(IOException.class, () -> renewal.status(ID));
        answer(
                200,
                "{\"pkcs12\":\"AQID\",\"password\":\"x\",\"certificate\":{\"validFrom\":\"2026-01-04 11:13:33+00:00\","
                        + "\"validTo\":\"2027-01-04 11:13:33+00:00\"}}");
        assertThrows(IOException.class, () -> renewal.claim(ID));
        answer(
                200,
                "{\"pkcs12\":\"AQID\",\"password\":\"x\",\"certificate\":{\"sn\":1,\"validFrom\":\"yesterday\","
                        + "\"validTo\":\"2027-01-04 11:13:33+00:00\"}}");
        assertThrows(IOException.class, () -> renewal.claim(ID));
    }

    private void answer(int status, String body) {
        this.status = status;
        this.body = body;
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    private static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertTrue(m.find(), json);
        return Long.parseLong(m.group(1));
    }
}
