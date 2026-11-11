package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a local stand-in for the service, which answers whatever a test hands it. */
class EetClientTest {

    private static final Pattern MESSAGE_ID = Pattern.compile("uuid_zpravy=\"([^\"]+)\"");

    private HttpServer server;
    // Turns the request body into the response body; "%ID%" in the answer becomes the request's message id.
    private final AtomicReference<UnaryOperator<String>> answer = new AtomicReference<>(r -> "");
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private int status = 200;
    private long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            Matcher id = MESSAGE_ID.matcher(request);
            String body = answer.get().apply(request).replace("%ID%", id.find() ? id.group(1) : "");
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Global-Transaction-Id", "bc626a986ade2e8c00fe06e3");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void anErrorMinusOneIsSentAgainLater() throws Exception {
        answer(odpoved("<eet:Chyba kod=\"-1\" test=\"true\">Docasna technicka chyba</eet:Chyba>"));
        Result.Uncertain result = assertInstanceOf(Result.Uncertain.class, send(Submission.of(SoapTest.sale())));
        assertEquals(Result.Reason.TEMPORARY, result.reason());
        assertEquals("bc626a986ade2e8c00fe06e3", result.exchange().transactionId());
        assertEquals("text/xml; charset=utf-8", contentType.get());
    }

    @Test
    void aSchemaErrorIsRejected() throws Exception {
        answer(odpoved("<eet:Chyba kod=\"3\" test=\"true\">XML zprava nevyhovela kontrole XML schematu</eet:Chyba>"));
        Result.Rejected result = assertInstanceOf(Result.Rejected.class, send(Submission.of(SoapTest.sale())));
        assertEquals(3, result.code());
    }

    @Test
    void anUnsignedConfirmationIsNotTrusted() throws Exception {
        answer(odpoved("<eet:Potvrzeni pok=\"62ebb536-7dec-47a9-8786-986a62bcf140-ff\" test=\"true\"/>"));
        Result.Uncertain result = assertInstanceOf(Result.Uncertain.class, send(Submission.of(SoapTest.sale())));
        assertEquals(Result.Reason.SIGNATURE, result.reason());
    }

    @Test
    void aRecordedConfirmationOfAnotherMessageIsNotThisOnesAnswer() throws Exception {
        String recorded = new String(SoapTest.recorded("accepted-response.xml"), StandardCharsets.UTF_8);
        answer.set(request -> recorded);
        Result.Uncertain result = assertInstanceOf(Result.Uncertain.class, send(Submission.of(SoapTest.sale())));
        assertEquals(Result.Reason.UNEXPECTED_RESPONSE, result.reason());
        assertTrue(result.detail().contains("90a4595c-288d-401d-bc20-08c8bcf1ed96"), result.detail());
    }

    @Test
    void aTestAnswerFromProductionIsNotTrusted() throws Exception {
        answer(odpoved("<eet:Chyba kod=\"0\" test=\"true\">ok</eet:Chyba>"));
        Result result = EetClient.builder(Environment.PRODUCTION, TestKeys.signer())
                .endpoint(
                        URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                                + server.getAddress().getPort() + "/"))
                .build()
                .send(Submission.verification(SoapTest.sale()));
        assertEquals(
                Result.Reason.UNEXPECTED_RESPONSE,
                assertInstanceOf(Result.Uncertain.class, result).reason());
    }

    @Test
    void aPassedVerificationRegistersNothing() throws Exception {
        answer(odpoved("<eet:Chyba kod=\"0\" test=\"true\">Datovou zpravu ... se podarilo zpracovat</eet:Chyba>"));
        assertInstanceOf(Result.Verified.class, send(Submission.verification(SoapTest.sale())));
    }

    @Test
    void noAnswerWithinTheTimeoutIsUncertain() throws Exception {
        delayMillis = 2_500;
        answer(odpoved("<eet:Chyba kod=\"0\" test=\"true\">late</eet:Chyba>"));
        Result.Uncertain result = assertInstanceOf(Result.Uncertain.class, send(Submission.of(SoapTest.sale())));
        assertEquals(Result.Reason.TIMEOUT, result.reason());
        assertTrue(result.exchange().request().contains("prvni_zaslani=\"true\""));
    }

    @Test
    void aPageThatIsNotAnAnswerIsUncertain() throws Exception {
        status = 503;
        answer.set(request -> "<html><body>maintenance</body></html>");
        Result.Uncertain result = assertInstanceOf(Result.Uncertain.class, send(Submission.of(SoapTest.sale())));
        assertEquals(Result.Reason.UNEXPECTED_RESPONSE, result.reason());
        assertTrue(result.detail().startsWith("HTTP 503"), result.detail());
    }

    @Test
    void refusesATimeoutShorterThanTheActAllows() throws Exception {
        EetClient.Builder builder = EetClient.builder(Environment.PLAYGROUND, TestKeys.signer());
        assertThrows(IllegalArgumentException.class, () -> builder.timeout(Duration.ofMillis(1_999)));
    }

    @Test
    void aSaleOfAnotherTaxpayerIsHeldBackUnsent() throws Exception {
        answer.set(r -> {
            throw new AssertionError("nothing should reach the server");
        });
        Sale other = Sale.of("CZ683555118", 11, "1", "2027/1", SoapTest.sale().time(), 100);
        Result.Uncertain result = assertInstanceOf(Result.Uncertain.class, send(Submission.of(other)));
        assertEquals(Result.Reason.CERTIFICATE, result.reason());
        assertEquals("", result.exchange().request());
    }

    @Test
    void anExpiredCertificateHoldsTheSaleBackUnsent() throws Exception {
        Instant afterExpiry = TestKeys.signer().notAfter().plusSeconds(1);
        Result result = EetClient.builder(Environment.PLAYGROUND, TestKeys.signer())
                .endpoint(
                        URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                                + server.getAddress().getPort() + "/"))
                .clock(Clock.fixed(afterExpiry, ZoneOffset.UTC))
                .build()
                .send(Submission.of(SoapTest.sale()));
        Result.Uncertain uncertain = assertInstanceOf(Result.Uncertain.class, result);
        assertEquals(Result.Reason.CERTIFICATE, uncertain.reason());
        assertTrue(uncertain.detail().startsWith("the certificate expired"), uncertain.detail());
    }

    @Test
    void aClosedClientSendsNothingAndSaysSo() throws Exception {
        EetClient client = EetClient.builder(Environment.PLAYGROUND, TestKeys.signer())
                .endpoint(
                        URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                                + server.getAddress().getPort() + "/"))
                .build();
        client.close();
        Result.Uncertain result = assertInstanceOf(Result.Uncertain.class, client.send(Submission.of(SoapTest.sale())));
        assertEquals(Result.Reason.NETWORK, result.reason());
    }

    private Result send(Submission submission) throws Exception {
        return EetClient.builder(Environment.PLAYGROUND, TestKeys.signer())
                .endpoint(
                        URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                                + server.getAddress().getPort() + "/"))
                .build()
                .send(submission);
    }

    private void answer(String body) {
        answer.set(request -> body);
    }

    private static String odpoved(String content) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" \
                xmlns:eet="http://fs.gov.cz/eet/schema/v4"><soapenv:Body><eet:Odpoved>\
                <eet:Hlavicka uuid_zpravy="%ID%" dat_prij="2027-01-09T16:45:41+01:00"/>""" + content + "</eet:Odpoved></soapenv:Body></soapenv:Envelope>";
    }
}
