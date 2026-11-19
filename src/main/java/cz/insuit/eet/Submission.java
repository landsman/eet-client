package cz.insuit.eet;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One attempt to register a {@link Sale}: the sale and the header that goes with this attempt.
 *
 * <p>The send time is not here: {@link EetClient#send} stamps it when the message leaves, because the interface
 * wants the real time of sending and a submission may wait in a queue first.
 */
public final class Submission {
    private final UUID messageId;
    private final boolean firstAttempt;
    private final boolean verification;
    private final Sale sale;

    public Submission(UUID messageId, boolean firstAttempt, boolean verification, Sale sale) {
        this.messageId = messageId;
        this.firstAttempt = firstAttempt;
        this.verification = verification;
        this.sale = sale;
    }

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

    /** This attempt's {@code uuid_zpravy}; new for every attempt, never reused. */
    public UUID messageId() {
        return messageId;
    }

    /** {@code prvni_zaslani}: true for the first attempt at this sale, whatever its outcome. */
    public boolean firstAttempt() {
        return firstAttempt;
    }

    /** {@code overeni}: a dry run the service checks but does not store; it does not register the sale. */
    public boolean verification() {
        return verification;
    }

    /** What is being registered; the same on every attempt. */
    public Sale sale() {
        return sale;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof Submission)) return false;
        Submission that = (Submission) o;
        return messageId.equals(that.messageId)
                && firstAttempt == that.firstAttempt
                && verification == that.verification
                && sale.equals(that.sale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, firstAttempt, verification, sale);
    }

    @Override
    public String toString() {
        return "Submission[messageId=" + messageId + ", firstAttempt=" + firstAttempt + ", verification=" + verification
                + ", sale=" + sale + "]";
    }
}
