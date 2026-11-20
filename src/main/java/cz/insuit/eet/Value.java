package cz.insuit.eet;

import java.util.Arrays;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a record would give for free on Java 16+: equals, hashCode and toString over the fields a class lists.
 *
 * <p>The three are not final, so javac copies each into every public subclass: a method this package-private class
 * declares cannot be called by reflection from outside the package, as EL, SpEL, Groovy and Kotlin reflection do.
 */
abstract class Value {

    /** The fields as name, value, name, value, in declaration order. */
    abstract @Nullable Object[] fields();

    // Every subclass is final, so comparing classes is the record's own rule.
    @SuppressWarnings("EqualsGetClass")
    @Override
    public boolean equals(@Nullable Object o) {
        return o != null && o.getClass() == getClass() && Arrays.equals(fields(), ((Value) o).fields());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(fields());
    }

    @Override
    public String toString() {
        @Nullable Object[] fields = fields();
        StringBuilder s = new StringBuilder(getClass().getSimpleName()).append('[');
        for (int i = 0; i < fields.length; i += 2) {
            s.append(i == 0 ? "" : ", ").append(fields[i]).append('=').append(loggable(fields[i + 1]));
        }
        return s.append(']').toString();
    }

    /**
     * {@code value} as text with every character escaped that could start a log line, or hide or reorder part of one:
     * controls, line and paragraph separators, and format characters such as bidi overrides and zero-width ones. A
     * message or a header from the wire then shows on one line, as it came. Escaping twice changes nothing, so a
     * nested value is safe too.
     */
    static String loggable(@Nullable Object value) {
        StringBuilder s = new StringBuilder();
        String.valueOf(value).codePoints().forEach(c -> {
            int type = Character.getType(c);
            if (type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                for (char unit : Character.toChars(c)) s.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
            } else {
                s.appendCodePoint(c);
            }
        });
        return s.toString();
    }
}
