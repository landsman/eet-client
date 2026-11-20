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
            @Nullable Object value = fields[i + 1];
            // Only text is escaped, here at the leaf: a nested Value or a list of them has escaped its own, and a
            // second pass would escape the backslashes of the first.
            s.append(i == 0 ? "" : ", ")
                    .append(fields[i])
                    .append('=')
                    .append(value instanceof String ? loggable((String) value) : String.valueOf(value));
        }
        return s.append(']').toString();
    }

    /**
     * {@code text} with every character escaped that could start a log line, hide or reorder part of one, or pass for
     * an escape: controls, line and paragraph separators, format characters such as bidi overrides and zero-width
     * ones, and the backslash. A message or a header from the wire then shows on one line, as it came.
     */
    static String loggable(@Nullable String text) {
        StringBuilder s = new StringBuilder();
        String.valueOf(text).codePoints().forEach(c -> {
            int type = Character.getType(c);
            if (c == '\\'
                    || type == Character.CONTROL
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
