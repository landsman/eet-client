package cz.insuit.eet;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How a renewal request stands: {@link InProcess}, {@link Issued}, {@link Delivering}, {@link Finished} or {@link
 * Rejected}, and nothing else.
 */
public interface Status {
    /** Not issued yet; ask again after {@code pollAfter}. */
    final class InProcess implements Status {
        private final Duration pollAfter;

        public InProcess(Duration pollAfter) {
            this.pollAfter = pollAfter;
        }

        public Duration pollAfter() {
            return pollAfter;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof InProcess && pollAfter.equals(((InProcess) o).pollAfter);
        }

        @Override
        public int hashCode() {
            return pollAfter.hashCode();
        }

        @Override
        public String toString() {
            return "InProcess[pollAfter=" + pollAfter + "]";
        }
    }

    /** Issued and ready for {@link Renewal#claim}. */
    final class Issued implements Status {
        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Issued;
        }

        @Override
        public int hashCode() {
            return Issued.class.hashCode();
        }

        @Override
        public String toString() {
            return "Issued[]";
        }
    }

    /** Claimed but not acknowledged; {@link Renewal#claim} returns it again until {@link Renewal#acknowledge}. */
    final class Delivering implements Status {
        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Delivering;
        }

        @Override
        public int hashCode() {
            return Delivering.class.hashCode();
        }

        @Override
        public String toString() {
            return "Delivering[]";
        }
    }

    /** Acknowledged, or not claimed within 30 days of issue: nothing more can be downloaded. */
    final class Finished implements Status {
        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Finished;
        }

        @Override
        public int hashCode() {
            return Finished.class.hashCode();
        }

        @Override
        public String toString() {
            return "Finished[]";
        }
    }

    /** Refused by the CA, with its reason. */
    final class Rejected implements Status {
        private final String reason;

        public Rejected(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Rejected && reason.equals(((Rejected) o).reason);
        }

        @Override
        public int hashCode() {
            return reason.hashCode();
        }

        @Override
        public String toString() {
            return "Rejected[reason=" + reason + "]";
        }
    }
}
