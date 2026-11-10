package cz.insuit.eet;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What came of one {@link Submission}. Store it whole, the {@link #exchange()} included: the signed request and the
 * response are the evidence that the sale was registered.
 */
public sealed interface Result {

    /** The message as sent and the answer as received. */
    Exchange exchange();

    /**
     * The sale is registered. Every accepted attempt gets its own POK, a resend of an already accepted sale
     * included, so keep each one rather than one per sale.
     *
     * @param pok the confirmation code, 39 characters; one from the playground ends in {@code -ff}
     * @param test whether the playground answered, where nothing is stored and the POK is not valid
     */
    record Accepted(String pok, boolean test, List<Warning> warnings, Exchange exchange) implements Result {}

    /** A {@linkplain Submission#verification(Sale) dry run} passed. Nothing was registered. */
    record Verified(List<Warning> warnings, Exchange exchange) implements Result {}

    /**
     * The service refused the message, and sending the same one again will not change that.
     *
     * <p>Code 8, "a technical error or an error in the data", is the exception the interface leaves open: it may
     * pass on a later attempt.
     *
     * @param code the service's error code: 2 encoding, 3 schema, 4 signature, 6 EI&Ccaron;, 7 size, 8 technical
     *     or data
     */
    record Rejected(int code, String message, List<Warning> warnings, Exchange exchange) implements Result {}

    /**
     * Nobody knows whether the sale is registered. Send it again with {@link Submission#resend()}: the service
     * recognises the same sale, so a resend never registers it twice.
     */
    record Uncertain(Reason reason, String detail, Exchange exchange) implements Result {}

    /** Why a result is {@link Uncertain}. */
    enum Reason {
        /** No answer within the timeout. */
        TIMEOUT,
        /** The connection failed, or the thread was interrupted while waiting. */
        NETWORK,
        /** The service answered error -1: a temporary fault, send it later. */
        TEMPORARY,
        /** A confirmation whose signature, or whose signer, does not check out. */
        SIGNATURE,
        /** An answer that is not a valid response to this message: a SOAP fault, another HTTP status, another id. */
        UNEXPECTED_RESPONSE
    }

    /** A warning the service attached; the sale is registered regardless. */
    record Warning(int code, String message) {}

    /**
     * One attempt on the wire.
     *
     * @param messageId the attempt's {@code uuid_zpravy}
     * @param request the signed SOAP request, as sent
     * @param response the response body, as received; null when none arrived
     * @param transactionId the service's {@code X-Global-Transaction-Id}, the handle its support asks for
     */
    record Exchange(
            UUID messageId,
            String request,
            @Nullable String response,
            @Nullable String transactionId) {}
}
