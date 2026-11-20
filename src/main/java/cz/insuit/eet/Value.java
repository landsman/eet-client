package cz.insuit.eet;

import java.util.Arrays;
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
            s.append(i == 0 ? "" : ", ").append(fields[i]).append('=').append(fields[i + 1]);
        }
        return s.append(']').toString();
    }
}
