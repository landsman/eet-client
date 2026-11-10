package cz.insuit.eet;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One registered sale: the {@code Data} element of an EET 2.0 message.
 *
 * <p>Every value is checked against the interface's format when the record is built, so a sale that would be
 * refused for its shape never reaches the network. Amounts are in hal&eacute;&rcaron;e (CZK minor units): {@code
 * 12350} is 123.50 K&ccaron;. A refund or a storno is a negative {@code total}, sent as a sale of its own with its
 * own sequence number, and never refers to the original.
 *
 * @param eic the taxpayer's EI&Ccaron; ({@code eic_popl}), which must be the CN of the signing certificate
 * @param unitId the evidence unit number the tax office assigned in DIS+ ({@code id_jednotky})
 * @param registerId the cash register within that unit ({@code id_pokl}), 1 to 20 characters
 * @param sequenceNumber the sale's number in the taxpayer's series ({@code porad_cis}), 1 to 25 characters,
 *     typically the receipt number
 * @param time when the sale was paid, or when the payment order was issued if that came first ({@code dat_trzby});
 *     truncated to seconds
 * @param total the amount actually received, cash rounding included ({@code celk_trzba})
 * @param delegatingEic the taxpayer who authorised {@code eic} to register for them ({@code eic_poverujiciho})
 * @param delegatedByMany whether several taxpayers authorised {@code eic} ({@code povereni_vice_popl})
 * @param forLaterDraw the part of {@code total} topped up onto a limited-network credit ({@code urceno_cerp_zuct})
 * @param draw the part of {@code total} drawn from such a credit ({@code cerp_zuct})
 */
public record Sale(
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

    private static final Pattern EIC = Pattern.compile("CZ[0-9]{8,10}");
    private static final Pattern TEXT = Pattern.compile("[0-9a-zA-Z.,:;/#\\-_ ]+");
    // The schema's range is exclusive of 100 000 000 Kč either way.
    private static final long MAX_AMOUNT = 99_999_999_99L;

    public Sale {
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
        time = Objects.requireNonNull(time, "time").truncatedTo(ChronoUnit.SECONDS);
        requireAmount(total, "total");
        if (delegatingEic != null) {
            require(EIC.matcher(delegatingEic).matches(), "delegatingEic must be CZ and 8 to 10 digits", delegatingEic);
            require(!delegatingEic.equals(eic), "delegatingEic must differ from eic", delegatingEic);
        }
        if (forLaterDraw != null) requireAmount(forLaterDraw, "forLaterDraw");
        if (draw != null) requireAmount(draw, "draw");
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

    /** The same sale, registered by {@link #eic} on behalf of the one taxpayer who authorised it. */
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

    /** The same sale, registered by {@link #eic} on behalf of several taxpayers who share the income. */
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
