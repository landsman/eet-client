/**
 * A client for EET 2.0, the Czech electronic sales registration.
 *
 * <p>Build a {@link cz.insuit.eet.Sale}, wrap it in a {@link cz.insuit.eet.Submission}, send it with {@link
 * cz.insuit.eet.EetClient#send}, and store the {@link cz.insuit.eet.Result} it returns. An uncertain result is sent
 * again with {@link cz.insuit.eet.Submission#resend()} until it is accepted or rejected; keeping that queue is the
 * caller's job, because the caller's database is the record.
 */
@NullMarked
package cz.insuit.eet;

import org.jspecify.annotations.NullMarked;
