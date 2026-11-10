package cz.insuit.eet;

import java.util.UUID;

/**
 * One attempt to register a {@link Sale}: the sale and the header that goes with this attempt.
 *
 * <p>The send time is not here: {@link EetClient#send} stamps it when the message leaves, because the interface
 * wants the real time of sending and a submission may wait in a queue first.
 *
 * @param messageId this attempt's {@code uuid_zpravy}; new for every attempt, never reused
 * @param firstAttempt {@code prvni_zaslani}: true for the first attempt at this sale, whatever its outcome
 * @param verification {@code overeni}: a dry run the service checks but does not store; it does not register the
 *     sale
 * @param sale what is being registered; the same on every attempt
 */
public record Submission(UUID messageId, boolean firstAttempt, boolean verification, Sale sale) {

    /** The first attempt at registering {@code sale}. */
    public static Submission of(Sale sale) {
        return new Submission(UUID.randomUUID(), true, false, sale);
    }

    /** A dry run of {@code sale}: checked by the service, not stored, and answered without a POK. */
    public static Submission verification(Sale sale) {
        return new Submission(UUID.randomUUID(), true, true, sale);
    }

    /**
     * The next attempt at the same sale, after an {@link Result.Uncertain} one: a new message id, not the first
     * attempt, the sale unchanged. The law wants it sent without undue delay once the cause is gone, within 48 hours
     * of the sale, and still after that.
     */
    public Submission resend() {
        return new Submission(UUID.randomUUID(), false, verification, sale);
    }
}
