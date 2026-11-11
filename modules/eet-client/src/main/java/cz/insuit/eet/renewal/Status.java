package cz.insuit.eet.renewal;

import java.time.Duration;

/** How a renewal request stands. */
public sealed interface Status {
    /** Not issued yet; ask again after {@code pollAfter}. */
    record InProcess(Duration pollAfter) implements Status {}

    /** Issued and ready for {@link Renewal#claim}. */
    record Issued() implements Status {}

    /** Claimed but not acknowledged; {@link Renewal#claim} returns it again until {@link Renewal#acknowledge}. */
    record Delivering() implements Status {}

    /** Acknowledged, or not claimed within 30 days of issue: nothing more can be downloaded. */
    record Finished() implements Status {}

    /** Refused by the CA, with its reason. */
    record Rejected(String reason) implements Status {}
}
