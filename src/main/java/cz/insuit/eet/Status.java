package cz.insuit.eet;

import java.time.Duration;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How a renewal request stands: {@link InProcess}, {@link Issued}, {@link Delivering}, {@link Finished} or {@link
 * Rejected}, and nothing else.
 */
public abstract class Status extends Value {

    // Only this package can add a state, as with Result.
    Status() {}

    /** As {@link Result#match}: one function per state, so none can be forgotten. */
    public final <T extends @Nullable Object> T match(
            Function<? super InProcess, ? extends T> inProcess,
            Function<? super Issued, ? extends T> issued,
            Function<? super Delivering, ? extends T> delivering,
            Function<? super Finished, ? extends T> finished,
            Function<? super Rejected, ? extends T> rejected) {
        if (this instanceof InProcess) return inProcess.apply((InProcess) this);
        if (this instanceof Issued) return issued.apply((Issued) this);
        if (this instanceof Delivering) return delivering.apply((Delivering) this);
        if (this instanceof Finished) return finished.apply((Finished) this);
        return rejected.apply((Rejected) this);
    }

    /** Not issued yet; ask again after {@code pollAfter}. */
    public static final class InProcess extends Status {
        private final Duration pollAfter;

        public InProcess(Duration pollAfter) {
            this.pollAfter = pollAfter;
        }

        public Duration pollAfter() {
            return pollAfter;
        }

        @Override
        Object[] fields() {
            return new Object[] {"pollAfter", pollAfter};
        }
    }

    /** Issued and ready for {@link Renewal#claim}. */
    public static final class Issued extends Status {
        @Override
        Object[] fields() {
            return new Object[0];
        }
    }

    /** Claimed but not acknowledged; {@link Renewal#claim} returns it again until {@link Renewal#acknowledge}. */
    public static final class Delivering extends Status {
        @Override
        Object[] fields() {
            return new Object[0];
        }
    }

    /** Acknowledged, or not claimed within 30 days of issue: nothing more can be downloaded. */
    public static final class Finished extends Status {
        @Override
        Object[] fields() {
            return new Object[0];
        }
    }

    /** Refused by the CA, with its reason. */
    public static final class Rejected extends Status {
        private final String reason;

        public Rejected(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        @Override
        Object[] fields() {
            return new Object[] {"reason", reason};
        }
    }
}
