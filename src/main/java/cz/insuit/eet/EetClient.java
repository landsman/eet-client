package cz.insuit.eet;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Sends sales to EET 2.0 and tells what came of each one. Thread-safe; build one per signer, keep it, and close it
 * when the signer is replaced: it holds an HTTP client with a thread of its own.
 *
 * <pre>{@code
 * EetClient eet = EetClient.builder(Environment.PRODUCTION, Signer.fromPkcs12(p12, password)).build();
 * Result result = eet.send(Submission.of(Sale.of("CZ00000019", 11, "till-1", "2027/000123", paidAt, 12350)));
 * }</pre>
 *
 * <p>One call is one attempt. It never retries on its own, because a retry is a new message the caller has to know
 * about and store: on {@link Result.Uncertain}, send {@link Submission#resend()}.
 */
public final class EetClient implements AutoCloseable {

    // The tax office's response certificate is issued by I.CA (EET_pristupove_provozni_informace, chapter 3.3).
    private static final List<X509Certificate> ICA_ROOTS = certificates("ica-root-rsa-2022.pem");
    private static final List<X509Certificate> ICA_INTERMEDIATES = certificates("ica-public-ca-rsa-2022.pem");
    private static final String TAX_OFFICE = "NTRCZ-72080043";

    private final Environment environment;
    private final URI endpoint;
    private final Signer signer;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient http;
    private final List<X509Certificate> anchors;
    private final List<X509Certificate> intermediates;
    private final String responseSigner;

    private EetClient(Builder b) {
        environment = b.environment;
        endpoint = b.endpoint;
        signer = b.signer;
        timeout = b.timeout;
        clock = b.clock;
        anchors = b.anchors;
        intermediates = b.intermediates;
        responseSigner = b.responseSigner;
        // HTTP/1.1 is what the interface requires. A new connection resolves the name again, which production's two
        // data centres behind DNS rely on; a pooled one is closed by the JDK after its keep-alive.
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /** A builder for a client that sends to {@code environment}, signing with {@code signer}. */
    public static Builder builder(Environment environment, Signer signer) {
        return new Builder(environment, signer);
    }

    /** Releases the HTTP client. A send after this comes back uncertain, as a network failure. */
    @Override
    public void close() {
        http.close();
    }

    /**
     * Signs and sends one attempt, and waits for the answer at most the timeout. A sale this client's certificate
     * cannot sign is not sent: {@link Result.Reason#CERTIFICATE}.
     */
    public Result send(Submission submission) {
        @Nullable String unsignable = unsignable(submission.sale());
        if (unsignable != null) {
            return uncertain(Result.Reason.CERTIFICATE, unsignable, submission, "", null, null);
        }
        byte[] request = Soap.request(submission, OffsetDateTime.now(clock), signer);
        String sent = new String(request, StandardCharsets.UTF_8);
        HttpResponse<byte[]> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .timeout(timeout)
                            .header("Content-Type", "text/xml; charset=utf-8")
                            .header("SOAPAction", Soap.SOAP_ACTION)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(request))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            return uncertain(Result.Reason.TIMEOUT, "no answer within " + timeout, submission, sent, null, null);
        } catch (IOException e) {
            return uncertain(Result.Reason.NETWORK, String.valueOf(e), submission, sent, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return uncertain(Result.Reason.NETWORK, "interrupted while waiting", submission, sent, null, null);
        }
        String received = new String(response.body(), StandardCharsets.UTF_8);
        String transactionId =
                response.headers().firstValue("X-Global-Transaction-Id").orElse(null);
        Result.Exchange exchange = new Result.Exchange(submission.messageId(), sent, received, transactionId);
        return read(submission, response.statusCode(), response.body(), exchange);
    }

    private Result read(Submission submission, int status, byte[] body, Result.Exchange exchange) {
        Soap.Response response;
        try {
            response = Soap.parse(body);
        } catch (Soap.InvalidResponseException e) {
            return new Result.Uncertain(
                    Result.Reason.UNEXPECTED_RESPONSE, "HTTP " + status + ", " + e.getMessage(), exchange);
        }
        // A response without the id is allowed only for a message the service could not read at all.
        String id = response.messageId();
        if (id != null && !id.equalsIgnoreCase(submission.messageId().toString())) {
            return new Result.Uncertain(Result.Reason.UNEXPECTED_RESPONSE, "an answer to message " + id, exchange);
        }
        if (response.test() != (environment == Environment.PLAYGROUND)) {
            return new Result.Uncertain(
                    Result.Reason.UNEXPECTED_RESPONSE,
                    "test=" + response.test() + " from " + environment + " at " + endpoint,
                    exchange);
        }
        String pok = response.pok();
        if (pok != null) {
            if (id == null) {
                return new Result.Uncertain(Result.Reason.UNEXPECTED_RESPONSE, "a POK for no message id", exchange);
            }
            String problem = Soap.verify(response.document(), anchors, intermediates, responseSigner, clock.instant());
            if (problem != null) return new Result.Uncertain(Result.Reason.SIGNATURE, problem, exchange);
            return new Result.Accepted(pok, response.test(), response.warnings(), exchange);
        }
        int code = Objects.requireNonNull(response.errorCode());
        return switch (code) {
            case 0 -> new Result.Verified(response.warnings(), exchange);
            case -1 -> new Result.Uncertain(Result.Reason.TEMPORARY, response.errorText(), exchange);
            default -> new Result.Rejected(code, response.errorText(), response.warnings(), exchange);
        };
    }

    /** Why the certificate cannot sign {@code sale} now, or null when it can. */
    private @Nullable String unsignable(Sale sale) {
        Instant now = clock.instant();
        if (!signer.notAfter().isAfter(now)) {
            return "the certificate expired on " + signer.notAfter();
        }
        Optional<String> eic = signer.eic();
        if (eic.isEmpty()) {
            return "the certificate is not a cash register certificate: its CN is not an EIČ";
        }
        if (!eic.get().equals(sale.eic())) {
            return "the certificate is for " + eic.get() + ", the sale for " + sale.eic();
        }
        return null;
    }

    private static Result.Uncertain uncertain(
            Result.Reason reason,
            String detail,
            Submission submission,
            String sent,
            @Nullable String received,
            @Nullable String transactionId) {
        return new Result.Uncertain(
                reason, detail, new Result.Exchange(submission.messageId(), sent, received, transactionId));
    }

    private static List<X509Certificate> certificates(String resource) {
        try (InputStream in = EetClient.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            return CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
                    .map(X509Certificate.class::cast)
                    .toList();
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    /** Builds an {@link EetClient}. */
    public static final class Builder {

        // § 19 of the act: the cash register waits at least two seconds for an answer.
        private static final Duration MINIMUM_TIMEOUT = Duration.ofSeconds(2);

        private final Environment environment;
        private final Signer signer;
        private URI endpoint;
        // The zone the send time is written in. Any offset is accepted; Prague's is what the interface's examples use.
        private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

        private Duration timeout = MINIMUM_TIMEOUT;
        private Clock clock = Clock.system(PRAGUE);
        private List<X509Certificate> anchors = ICA_ROOTS;
        private List<X509Certificate> intermediates = ICA_INTERMEDIATES;
        private String responseSigner = TAX_OFFICE;

        private Builder(Environment environment, Signer signer) {
            this.environment = environment;
            this.signer = signer;
            this.endpoint = environment.endpoint();
        }

        /**
         * How long to wait for an answer, two seconds by default. The act sets two seconds as the least, and asks for
         * a value that does not hold up the sale.
         */
        public Builder timeout(Duration timeout) {
            if (timeout.compareTo(MINIMUM_TIMEOUT) < 0) {
                throw new IllegalArgumentException("the act requires waiting at least 2 s, was " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /** The clock the send time and the response certificate's validity are read from; its zone is the offset; Prague by default. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Another endpoint, a local stand-in for a test. */
        Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Who must have signed a confirmation, for a test with its own CA. */
        Builder responseTrust(
                List<X509Certificate> anchors, List<X509Certificate> intermediates, String organizationIdentifier) {
            this.anchors = anchors;
            this.intermediates = intermediates;
            this.responseSigner = organizationIdentifier;
            return this;
        }

        public EetClient build() {
            return new EetClient(this);
        }
    }
}
