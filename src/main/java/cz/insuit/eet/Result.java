package cz.insuit.eet;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
     * The sale is registered. Every accepted attempt gets its own POK, a resend of an already accepted sale
     * included, so keep each one rather than one per sale.
     */
    final class Accepted implements Result {
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
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Accepted)) return false;
            Accepted that = (Accepted) o;
            return pok.equals(that.pok)
                    && test == that.test
                    && warnings.equals(that.warnings)
                    && exchange.equals(that.exchange);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pok, test, warnings, exchange);
        }

        @Override
        public String toString() {
            return "Accepted[pok=" + pok + ", test=" + test + ", warnings=" + warnings + ", exchange=" + exchange + "]";
        }
    }

    /** A {@linkplain Submission#verification(Sale) dry run} passed. Nothing was registered. */
    final class Verified implements Result {
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
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Verified)) return false;
            Verified that = (Verified) o;
            return warnings.equals(that.warnings) && exchange.equals(that.exchange);
        }

        @Override
        public int hashCode() {
            return Objects.hash(warnings, exchange);
        }

        @Override
        public String toString() {
            return "Verified[warnings=" + warnings + ", exchange=" + exchange + "]";
        }
    }

    /**
     * The service refused the message, and sending the same one again will not change that.
     *
     * <p>Code 8, "a technical error or an error in the data", is the exception the interface leaves open: it may
     * pass on a later attempt.
     */
    final class Rejected implements Result {
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
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Rejected)) return false;
            Rejected that = (Rejected) o;
            return code == that.code
                    && message.equals(that.message)
                    && warnings.equals(that.warnings)
                    && exchange.equals(that.exchange);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, message, warnings, exchange);
        }

        @Override
        public String toString() {
            return "Rejected[code=" + code + ", message=" + message + ", warnings=" + warnings + ", exchange="
                    + exchange + "]";
        }
    }

    /**
     * Nobody knows whether the sale is registered. Send it again with {@link Submission#resend()}: the service
     * recognises the same sale, so a resend never registers it twice.
     */
    final class Uncertain implements Result {
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
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Uncertain)) return false;
            Uncertain that = (Uncertain) o;
            return reason == that.reason && detail.equals(that.detail) && exchange.equals(that.exchange);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason, detail, exchange);
        }

        @Override
        public String toString() {
            return "Uncertain[reason=" + reason + ", detail=" + detail + ", exchange=" + exchange + "]";
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
    final class Warning {
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
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Warning)) return false;
            Warning that = (Warning) o;
            return code == that.code && message.equals(that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, message);
        }

        @Override
        public String toString() {
            return "Warning[code=" + code + ", message=" + message + "]";
        }
    }

    /** One attempt on the wire. */
    final class Exchange {
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
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Exchange)) return false;
            Exchange that = (Exchange) o;
            return messageId.equals(that.messageId)
                    && request.equals(that.request)
                    && Objects.equals(response, that.response)
                    && Objects.equals(transactionId, that.transactionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, request, response, transactionId);
        }

        @Override
        public String toString() {
            return "Exchange[messageId=" + messageId + ", request=" + request + ", response=" + response
                    + ", transactionId=" + transactionId + "]";
        }
    }
}
