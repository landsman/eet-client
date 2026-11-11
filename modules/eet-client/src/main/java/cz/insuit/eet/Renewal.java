package cz.insuit.eet;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Renews a cash register certificate through the CA's API ({@code spec/caeetapi_jwt.yml}), signed with the
 * certificate being renewed. Thread-safe; close it to release its HTTP client.
 *
 * <pre>{@code
 * try (Renewal renewal = Renewal.of(Renewal.Server.PRODUCTION, signer)) {
 *     UUID id = renewal.request();
 *     // later, until it is no longer InProcess:
 *     if (renewal.status(id) instanceof Renewal.Status.Issued) {
 *         Renewal.Renewed renewed = renewal.claim(id);
 *         // store renewed.pkcs12() and renewed.password() first, then:
 *         renewal.acknowledge(id);
 *     }
 * }
 * }</pre>
 *
 * <p>The certificate must still be valid: an expired or revoked one can only be replaced by the taxpayer in the
 * portal. The CA recommends renewing two to three weeks before expiry. Store the request id before polling; {@link
 * #notFinished()} finds a lost one.
 */
public final class Renewal implements AutoCloseable {

    /** Where the CA's API runs. There is none for the playground: its certificates cannot be renewed. */
    public enum Server {
        /** The CA's trial environment, {@code zkušební} in the specification. */
        TRIAL("https://ca.zkus.caeet.gov.cz/api"),
        /** The real CA, which renews production certificates. */
        PRODUCTION("https://ca.caeet.gov.cz/api");

        private final URI base;

        Server(String base) {
            this.base = URI.create(base);
        }

        /** The API's base URL. */
        public URI base() {
            return base;
        }
    }

    /** How a renewal request stands. */
    public sealed interface Status {
        /** Not issued yet; ask again after {@code pollAfter}. */
        record InProcess(Duration pollAfter) implements Status {}

        /** Issued and ready for {@link #claim}. */
        record Issued() implements Status {}

        /** Claimed but not acknowledged; {@link #claim} returns it again until {@link #acknowledge}. */
        record Delivering() implements Status {}

        /** Acknowledged, or not claimed within 30 days of issue: nothing more can be downloaded. */
        record Finished() implements Status {}

        /** Refused by the CA, with its reason. */
        record Rejected(String reason) implements Status {}
    }

    /**
     * The new certificate as the CA hands it out: a PKCS#12 file and its 8-character password, which {@link
     * Signer#fromPkcs12} opens. Sensitive: neither belongs in a log.
     */
    public static final class Renewed {
        private final byte[] pkcs12;
        private final char[] password;
        private final String serialNumber;
        private final Instant validFrom;
        private final Instant validTo;

        Renewed(byte[] pkcs12, char[] password, String serialNumber, Instant validFrom, Instant validTo) {
            this.pkcs12 = pkcs12;
            this.password = password;
            this.serialNumber = serialNumber;
            this.validFrom = validFrom;
            this.validTo = validTo;
        }

        /** The PKCS#12 file, a copy. */
        public byte[] pkcs12() {
            return pkcs12.clone();
        }

        /** Its password, a copy. */
        public char[] password() {
            return password.clone();
        }

        /** The new certificate's serial number, in decimal. */
        public String serialNumber() {
            return serialNumber;
        }

        /** When it starts being valid. */
        public Instant validFrom() {
            return validFrom;
        }

        /** When it stops being valid. */
        public Instant validTo() {
            return validTo;
        }

        @Override
        public String toString() {
            return "Renewed[serialNumber=" + serialNumber + ", validTo=" + validTo + "]";
        }
    }

    /** An answer other than success: the HTTP status, and the CA's error code and message when it sent them. */
    public static final class RenewalException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final @Nullable Integer code;

        RenewalException(int status, @Nullable Integer code, String message) {
            super("HTTP " + status + (code == null ? "" : ", code " + code) + ": " + message);
            this.status = status;
            this.code = code;
        }

        /** The HTTP status: 401 a certificate the CA does not accept, 409 a request in the wrong state, 429 too often. */
        public int status() {
            return status;
        }

        /** The CA's own error code, when the body carried one. */
        public @Nullable Integer code() {
            return code;
        }
    }

    // The CA refuses a token whose exp is more than 5 minutes after iat (CAEET_postupy_zadost_certifikat_v2, p. 5).
    // iat sits a little in the past, so a CA clock behind ours does not see a token from the future.
    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Duration LIFETIME = Duration.ofSeconds(90);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final JsonFactory JSON = new JsonFactory();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final URI base;
    private final Signer signer;
    private final Clock clock;
    private final HttpClient http;
    private final String thumbprint;

    Renewal(URI base, Signer signer, Clock clock) {
        this.base = base;
        this.signer = signer;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        try {
            this.thumbprint = B64URL.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(signer.certificate().getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot hash the certificate", e);
        }
    }

    /** A client for {@code server}, renewing the certificate of {@code signer}. */
    public static Renewal of(Server server, Signer signer) {
        return new Renewal(server.base(), signer, Clock.systemUTC());
    }

    @Override
    public void close() {
        http.close();
    }

    /** Asks for a successor certificate, and returns the request's id. */
    public UUID request() throws IOException {
        return UUID.fromString(string(object(call("POST", "/request/renew", 201)), "reqId"));
    }

    /** How the request {@code id} stands. */
    public Status status(UUID id) throws IOException {
        Map<String, Object> body = object(call("GET", "/request/" + id + "/status", 200));
        return switch (string(body, "state")) {
            case "INPROCESS" ->
                new Status.InProcess(Duration.ofSeconds(((Number) required(body, "pollAfterSeconds")).longValue()));
            case "ISSUED" -> new Status.Issued();
            case "DELIVERING" -> new Status.Delivering();
            case "FINISHED" -> new Status.Finished();
            case "REJECTED" -> new Status.Rejected(string(body, "rejectedReason"));
            default -> throw new IOException("unknown state " + body.get("state"));
        };
    }

    /** Downloads the issued certificate. Store it before {@link #acknowledge}: after that the CA deletes its copy. */
    public Renewed claim(UUID id) throws IOException {
        Map<String, Object> body = object(call("POST", "/request/" + id + "/claim-download", 200));
        if (!(body.get("certificate") instanceof Map<?, ?> certificate)) {
            throw new IOException("no certificate in the answer");
        }
        return new Renewed(
                Base64.getMimeDecoder().decode(string(body, "pkcs12")),
                string(body, "password").toCharArray(),
                String.valueOf(certificate.get("sn")),
                instant(certificate.get("validFrom")),
                instant(certificate.get("validTo")));
    }

    /** Confirms the certificate was stored; the CA then deletes its copy and the request is finished. */
    public void acknowledge(UUID id) throws IOException {
        call("POST", "/request/" + id + "/ack-download", 204);
    }

    /** The requests for this certificate's EIČ that are in process, issued or delivering. */
    public List<UUID> notFinished() throws IOException {
        Object data = object(call("GET", "/request/not-finished", 200)).get("data");
        List<UUID> ids = new ArrayList<>();
        if (data instanceof List<?> list) list.forEach(id -> ids.add(UUID.fromString(String.valueOf(id))));
        return ids;
    }

    private byte[] call(String method, String path, int expected) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the CA", e);
        }
        if (response.statusCode() == expected) return response.body();
        @Nullable Integer code = null;
        String message = new String(response.body(), StandardCharsets.UTF_8);
        try {
            Map<String, Object> error = object(response.body());
            if (error.get("code") instanceof Number n) code = n.intValue();
            message = error.get("message") + (error.get("additionalInfo") instanceof String info ? " " + info : "");
        } catch (IOException notJson) {
            // the body as it came, read above
        }
        throw new RenewalException(response.statusCode(), code, message);
    }

    /** A JWS signed with the certificate being renewed, which the CA finds by its SHA-256 thumbprint. */
    String token() {
        Instant now = clock.instant();
        String header = json(g -> {
            g.writeStringField("alg", "RS256");
            g.writeStringField("typ", "JWT");
            g.writeStringField("x5t#S256", thumbprint);
        });
        String payload = json(g -> {
            g.writeNumberField("iat", now.minus(SKEW).getEpochSecond());
            g.writeNumberField("exp", now.minus(SKEW).plus(LIFETIME).getEpochSecond());
        });
        String signed = B64URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + B64URL.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(signer.key());
            rsa.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + B64URL.encodeToString(rsa.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign with the certificate's key", e);
        }
    }

    private interface Fields {
        void write(JsonGenerator g) throws IOException;
    }

    private static String json(Fields fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            fields.write(g);
            g.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> object(byte[] body) throws IOException {
        try (JsonParser p = JSON.createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a JSON object");
            return readObject(p);
        }
    }

    // Jackson tokenises; this only turns its tokens into maps, lists, strings and numbers.
    private static Map<String, Object> readObject(JsonParser p) throws IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            p.nextToken();
            @Nullable Object value = readValue(p);
            if (value != null) fields.put(name, value);
        }
        return fields;
    }

    private static @Nullable Object readValue(JsonParser p) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.START_OBJECT) return readObject(p);
        if (token == JsonToken.START_ARRAY) {
            List<Object> items = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                @Nullable Object item = readValue(p);
                if (item != null) items.add(item);
            }
            return items;
        }
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) return p.getNumberValue();
        if (token == JsonToken.VALUE_NULL) return null;
        return p.getText();
    }

    private static Object required(Map<String, Object> body, String field) throws IOException {
        Object value = body.get(field);
        if (value == null) throw new IOException("no " + field + " in the answer");
        return value;
    }

    private static String string(Map<String, Object> body, String field) throws IOException {
        return String.valueOf(required(body, field));
    }

    // The specification says date-time, its examples write "2027-01-04 11:13:33+00:00"; both are read.
    private static Instant instant(@Nullable Object value) throws IOException {
        if (value == null) throw new IOException("no validity in the answer");
        return OffsetDateTime.parse(String.valueOf(value).replace(' ', 'T')).toInstant();
    }
}
