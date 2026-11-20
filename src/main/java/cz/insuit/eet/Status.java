package cz.insuit.eet;

import java.time.Duration;

/**
 * How a renewal request stands: {@link InProcess}, {@link Issued}, {@link Delivering}, {@link Finished} or {@link
 * Rejected}, and nothing else.
 */
public interface Status {
    /** Not issued yet; ask again after {@code pollAfter}. */
    final class InProcess extends Value implements Status {
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
    final class Issued extends Value implements Status {
        @Override
        Object[] fields() {
            return new Object[0];
        }
    }

    /** Claimed but not acknowledged; {@link Renewal#claim} returns it again until {@link Renewal#acknowledge}. */
    final class Delivering extends Value implements Status {
        @Override
        Object[] fields() {
            return new Object[0];
        }
    }

    /** Acknowledged, or not claimed within 30 days of issue: nothing more can be downloaded. */
    final class Finished extends Value implements Status {
        @Override
        Object[] fields() {
            return new Object[0];
        }
    }

    /** Refused by the CA, with its reason. */
    final class Rejected extends Value implements Status {
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
