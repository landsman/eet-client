package cz.insuit.eet;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One registered sale: the {@code Data} element of an EET 2.0 message.
 *
 * <p>Every value is checked against the interface's format when the sale is built, so a sale that would be refused
 * for its shape never reaches the network. Amounts are in hal&eacute;&rcaron;e (CZK minor units): {@code 12350} is
 * 123.50 K&ccaron;. A refund or a storno is a negative {@code total}, sent as a sale of its own with its own sequence
 * number, and never refers to the original.
 */
public final class Sale {

    private static final Pattern EIC = Pattern.compile("CZ[0-9]{8,10}");
    private static final Pattern TEXT = Pattern.compile("[0-9a-zA-Z.,:;/#\\-_ ]+");
    // The schema's range is exclusive of 100 000 000 Kč either way.
    private static final long MAX_AMOUNT = 99_999_999_99L;

    private final String eic;
    private final int unitId;
    private final String registerId;
    private final String sequenceNumber;
    private final OffsetDateTime time;
    private final long total;
    private final @Nullable String delegatingEic;
    private final boolean delegatedByMany;
    private final @Nullable Long forLaterDraw;
    private final @Nullable Long draw;

    public Sale(
            String eic,
            int unitId,
            String registerId,
            String sequenceNumber,
            OffsetDateTime time,
            long total,
            @Nullable String delegatingEic,
            boolean delegatedByMany,
            @Nullable Long forLaterDraw,
            @Nullable Long draw) {
        require(EIC.matcher(eic).matches(), "eic must be CZ and 8 to 10 digits", eic);
        require(unitId >= 1 && unitId <= 999_999_999, "unitId must be 1 to 999 999 999", unitId);
        require(
                text(registerId, 20),
                "registerId must be 1 to 20 of 0-9 a-z A-Z . , : ; / # - _ and space",
                registerId);
        require(
                text(sequenceNumber, 25),
                "sequenceNumber must be 1 to 25 of 0-9 a-z A-Z . , : ; / # - _ and space",
                sequenceNumber);
        requireAmount(total, "total");
        if (delegatingEic != null) {
            require(EIC.matcher(delegatingEic).matches(), "delegatingEic must be CZ and 8 to 10 digits", delegatingEic);
            require(!delegatingEic.equals(eic), "delegatingEic must differ from eic", delegatingEic);
        }
        if (forLaterDraw != null) requireAmount(forLaterDraw, "forLaterDraw");
        if (draw != null) requireAmount(draw, "draw");
        this.eic = eic;
        this.unitId = unitId;
        this.registerId = registerId;
        this.sequenceNumber = sequenceNumber;
        this.time = Objects.requireNonNull(time, "time").truncatedTo(ChronoUnit.SECONDS);
        this.total = total;
        this.delegatingEic = delegatingEic;
        this.delegatedByMany = delegatedByMany;
        this.forLaterDraw = forLaterDraw;
        this.draw = draw;
    }

    /** A sale with only the required values. */
    public static Sale of(
            String eic, int unitId, String registerId, String sequenceNumber, OffsetDateTime time, long total) {
        return new Sale(eic, unitId, registerId, sequenceNumber, time, total, null, false, null, null);
    }

    /**
     * The storno of this sale: the same taxpayer, unit and till, every amount negated, at {@code time} and under its
     * own {@code sequenceNumber}. The tax office links nothing: a storno is a sale of its own with a negative amount.
     */
    public Sale storno(String sequenceNumber, OffsetDateTime time) {
        return new Sale(
                eic,
                unitId,
                registerId,
                sequenceNumber,
                time,
                -total,
                delegatingEic,
                delegatedByMany,
                forLaterDraw == null ? null : -forLaterDraw,
                draw == null ? null : -draw);
    }

    /** The same sale, registered by {@link #eic()} on behalf of the one taxpayer who authorised it. */
    public Sale withDelegatingEic(String delegatingEic) {
        return new Sale(
                eic,
                unitId,
                registerId,
                sequenceNumber,
                time,
                total,
                delegatingEic,
                delegatedByMany,
                forLaterDraw,
                draw);
    }

    /** The same sale, registered by {@link #eic()} on behalf of several taxpayers who share the income. */
    public Sale withDelegatedByMany() {
        return new Sale(eic, unitId, registerId, sequenceNumber, time, total, delegatingEic, true, forLaterDraw, draw);
    }

    /** The same sale, of which {@code amount} is topped up onto a limited-network credit. */
    public Sale withForLaterDraw(long amount) {
        return new Sale(
                eic, unitId, registerId, sequenceNumber, time, total, delegatingEic, delegatedByMany, amount, draw);
    }

    /** The same sale, of which {@code amount} is drawn from a limited-network credit. */
    public Sale withDraw(long amount) {
        return new Sale(
                eic,
                unitId,
                registerId,
                sequenceNumber,
                time,
                total,
                delegatingEic,
                delegatedByMany,
                forLaterDraw,
                amount);
    }

    /** The taxpayer's EI&Ccaron; ({@code eic_popl}), which must be the CN of the signing certificate. */
    public String eic() {
        return eic;
    }

    /** The evidence unit number the tax office assigned in DIS+ ({@code id_jednotky}). */
    public int unitId() {
        return unitId;
    }

    /** The cash register within that unit ({@code id_pokl}), 1 to 20 characters. */
    public String registerId() {
        return registerId;
    }

    /** The sale's number in the taxpayer's series ({@code porad_cis}), 1 to 25 characters, typically the receipt. */
    public String sequenceNumber() {
        return sequenceNumber;
    }

    /**
     * When the sale was paid, or when the payment order was issued if that came first ({@code dat_trzby}); truncated
     * to seconds.
     */
    public OffsetDateTime time() {
        return time;
    }

    /** The amount actually received, cash rounding included ({@code celk_trzba}). */
    public long total() {
        return total;
    }

    /** The taxpayer who authorised {@link #eic()} to register for them ({@code eic_poverujiciho}). */
    public @Nullable String delegatingEic() {
        return delegatingEic;
    }

    /** Whether several taxpayers authorised {@link #eic()} ({@code povereni_vice_popl}). */
    public boolean delegatedByMany() {
        return delegatedByMany;
    }

    /** The part of {@link #total()} topped up onto a limited-network credit ({@code urceno_cerp_zuct}). */
    public @Nullable Long forLaterDraw() {
        return forLaterDraw;
    }

    /** The part of {@link #total()} drawn from such a credit ({@code cerp_zuct}). */
    public @Nullable Long draw() {
        return draw;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof Sale)) return false;
        Sale that = (Sale) o;
        return eic.equals(that.eic)
                && unitId == that.unitId
                && registerId.equals(that.registerId)
                && sequenceNumber.equals(that.sequenceNumber)
                && time.equals(that.time)
                && total == that.total
                && Objects.equals(delegatingEic, that.delegatingEic)
                && delegatedByMany == that.delegatedByMany
                && Objects.equals(forLaterDraw, that.forLaterDraw)
                && Objects.equals(draw, that.draw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                eic,
                unitId,
                registerId,
                sequenceNumber,
                time,
                total,
                delegatingEic,
                delegatedByMany,
                forLaterDraw,
                draw);
    }

    @Override
    public String toString() {
        return "Sale[eic=" + eic + ", unitId=" + unitId + ", registerId=" + registerId + ", sequenceNumber="
                + sequenceNumber + ", time=" + time + ", total=" + total + ", delegatingEic=" + delegatingEic
                + ", delegatedByMany=" + delegatedByMany + ", forLaterDraw=" + forLaterDraw + ", draw=" + draw + "]";
    }

    /** An amount in hal&eacute;&rcaron;e as the schema writes it: {@code -5} is {@code -0.05}, never {@code -0.00}. */
    static String amount(long halere) {
        String sign = halere < 0 ? "-" : "";
        long abs = Math.abs(halere);
        return sign + abs / 100 + "." + String.format("%02d", abs % 100);
    }

    private static boolean text(String value, int max) {
        return value.length() <= max && TEXT.matcher(value).matches();
    }

    private static void requireAmount(long value, String name) {
        require(Math.abs(value) <= MAX_AMOUNT, name + " must be within ±99 999 999.99 Kč", value);
    }

    private static void require(boolean ok, String message, Object value) {
        if (!ok) throw new IllegalArgumentException(message + ", was: " + value);
    }
}
