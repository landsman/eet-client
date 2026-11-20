package cz.insuit.eet;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What came of one {@link Submission}: {@link Accepted}, {@link Verified}, {@link Rejected} or {@link Uncertain},
 * and nothing else. Store it whole, the {@link #exchange()} included: the signed request and the response are the
 * evidence that the sale was registered.
 */
public interface Result {

    /** The message as sent and the answer as received. */
    Exchange exchange();

    /**
     * Hands this result to the function for its kind and returns what that one returns. Every kind needs a function,
     * so none can be forgotten, and a kind added later breaks the build instead of slipping past: what a {@code
     * switch} over a sealed type checks on Java 17 and later.
     */
    default <T extends @Nullable Object> T match(
            Function<? super Accepted, ? extends T> accepted,
            Function<? super Verified, ? extends T> verified,
            Function<? super Rejected, ? extends T> rejected,
            Function<? super Uncertain, ? extends T> uncertain) {
        if (this instanceof Accepted) return accepted.apply((Accepted) this);
        if (this instanceof Verified) return verified.apply((Verified) this);
        if (this instanceof Rejected) return rejected.apply((Rejected) this);
        return uncertain.apply((Uncertain) this);
    }

    /**
     * The sale is registered. Every accepted attempt gets its own POK, a resend of an already accepted sale
     * included, so keep each one rather than one per sale.
     */
    final class Accepted extends Value implements Result {
        private final String pok;
        private final boolean test;
        private final List<Warning> warnings;
        private final Exchange exchange;

        public Accepted(String pok, boolean test, List<Warning> warnings, Exchange exchange) {
            this.pok = pok;
            this.test = test;
            this.warnings = warnings;
            this.exchange = exchange;
        }

        /** The confirmation code, 39 characters; one from the playground ends in {@code -ff}. */
        public String pok() {
            return pok;
        }

        /** Whether the playground answered, where nothing is stored and the POK is not valid. */
        public boolean test() {
            return test;
        }

        public List<Warning> warnings() {
            return warnings;
        }

        @Override
        public Exchange exchange() {
            return exchange;
        }

        @Override
        Object[] fields() {
            return new Object[] {"pok", pok, "test", test, "warnings", warnings, "exchange", exchange};
        }
    }

    /** A {@linkplain Submission#verification(Sale) dry run} passed. Nothing was registered. */
    final class Verified extends Value implements Result {
        private final List<Warning> warnings;
        private final Exchange exchange;

        public Verified(List<Warning> warnings, Exchange exchange) {
            this.warnings = warnings;
            this.exchange = exchange;
        }

        public List<Warning> warnings() {
            return warnings;
        }

        @Override
        public Exchange exchange() {
            return exchange;
        }

        @Override
        Object[] fields() {
            return new Object[] {"warnings", warnings, "exchange", exchange};
        }
    }

    /**
     * The service refused the message, and sending the same one again will not change that.
     *
     * <p>Code 8, "a technical error or an error in the data", is the exception the interface leaves open: it may
     * pass on a later attempt.
     */
    final class Rejected extends Value implements Result {
        private final int code;
        private final String message;
        private final List<Warning> warnings;
        private final Exchange exchange;

        public Rejected(int code, String message, List<Warning> warnings, Exchange exchange) {
            this.code = code;
            this.message = message;
            this.warnings = warnings;
            this.exchange = exchange;
        }

        /** The service's error code: 2 encoding, 3 schema, 4 signature, 6 EI&Ccaron;, 7 size, 8 technical or data. */
        public int code() {
            return code;
        }

        public String message() {
            return message;
        }

        public List<Warning> warnings() {
            return warnings;
        }

        @Override
        public Exchange exchange() {
            return exchange;
        }

        @Override
        Object[] fields() {
            return new Object[] {"code", code, "message", message, "warnings", warnings, "exchange", exchange};
        }
    }

    /**
     * Nobody knows whether the sale is registered. Send it again with {@link Submission#resend()}: the service
     * recognises the same sale, so a resend never registers it twice.
     */
    final class Uncertain extends Value implements Result {
        private final Reason reason;
        private final String detail;
        private final Exchange exchange;

        public Uncertain(Reason reason, String detail, Exchange exchange) {
            this.reason = reason;
            this.detail = detail;
            this.exchange = exchange;
        }

        public Reason reason() {
            return reason;
        }

        public String detail() {
            return detail;
        }

        @Override
        public Exchange exchange() {
            return exchange;
        }

        @Override
        Object[] fields() {
            return new Object[] {"reason", reason, "detail", detail, "exchange", exchange};
        }
    }

    /** Why a result is {@link Uncertain}. */
    enum Reason {
        /** No answer within the timeout. */
        TIMEOUT,
        /** The connection failed, or the client was closed. */
        NETWORK,
        /** The service answered error -1: a temporary fault, send it later. */
        TEMPORARY,
        /** A confirmation whose signature, or whose signer, does not check out. */
        SIGNATURE,
        /** An answer that is not a valid response to this message: a SOAP fault, another HTTP status, another id. */
        UNEXPECTED_RESPONSE,
        /**
         * Nothing was sent: the certificate has expired, is not a cash register certificate, or belongs to another
         * taxpayer than the sale. The tax office would refuse it for good (error 4), so it is held back instead; send
         * it again once a valid certificate signs it. The exchange's request is empty.
         */
        CERTIFICATE
    }

    /** A warning the service attached; the sale is registered regardless. */
    final class Warning extends Value {
        private final int code;
        private final String message;

        public Warning(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int code() {
            return code;
        }

        public String message() {
            return message;
        }

        @Override
        Object[] fields() {
            return new Object[] {"code", code, "message", message};
        }
    }

    /** One attempt on the wire. */
    final class Exchange extends Value {
        private final UUID messageId;
        private final String request;
        private final @Nullable String response;
        private final @Nullable String transactionId;

        public Exchange(UUID messageId, String request, @Nullable String response, @Nullable String transactionId) {
            this.messageId = messageId;
            this.request = request;
            this.response = response;
            this.transactionId = transactionId;
        }

        /** The attempt's {@code uuid_zpravy}. */
        public UUID messageId() {
            return messageId;
        }

        /** The signed SOAP request, as sent. */
        public String request() {
            return request;
        }

        /** The response body, as received; null when none arrived. */
        public @Nullable String response() {
            return response;
        }

        /** The service's {@code X-Global-Transaction-Id}, the handle its support asks for. */
        public @Nullable String transactionId() {
            return transactionId;
        }

        @Override
        @Nullable
        Object[] fields() {
            return new @Nullable Object[] {
                "messageId", messageId, "request", request, "response", response, "transactionId", transactionId
            };
        }

        /** The ids and the bodies' lengths, so a log line stays findable; the bodies are one accessor away. */
        @Override
        public String toString() {
            return "Exchange[messageId=" + messageId + ", transactionId=" + transactionId + ", request="
                    + request.length() + " chars, response="
                    + (response == null ? "none" : response.length() + " chars")
                    + "]";
        }
    }
}
