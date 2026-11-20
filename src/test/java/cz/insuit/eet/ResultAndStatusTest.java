package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the records and sealed types gave for free: equals, hashCode, toString, and a check of every case. */
class ResultAndStatusTest {

    private static final Result.Exchange EXCHANGE =
            new Result.Exchange(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "", null, null);

    @Test
    void matchCallsTheFunctionForTheKind() {
        assertEquals("a", kind(new Result.Accepted("pok", false, Collections.emptyList(), EXCHANGE)));
        assertEquals("v", kind(new Result.Verified(Collections.emptyList(), EXCHANGE)));
        assertEquals("r", kind(new Result.Rejected(4, "x", Collections.emptyList(), EXCHANGE)));
        assertEquals("u", kind(new Result.Uncertain(Result.Reason.TIMEOUT, "x", EXCHANGE)));

        assertEquals("p", state(new Status.InProcess(Duration.ofSeconds(2))));
        assertEquals("i", state(new Status.Issued()));
        assertEquals("d", state(new Status.Delivering()));
        assertEquals("f", state(new Status.Finished()));
        assertEquals("r", state(new Status.Rejected("x")));
    }

    private static String kind(Result result) {
        return result.match(a -> "a", v -> "v", r -> "r", u -> "u");
    }

    private static String state(Status status) {
        return status.match(p -> "p", i -> "i", d -> "d", f -> "f", r -> "r");
    }

    @Test
    void equalByClassAndFields() {
        assertEquals(new Status.Issued(), new Status.Issued());
        // Both list no fields; only the class tells them apart.
        assertNotEquals(new Status.Issued(), new Status.Delivering());
        assertEquals(new Result.Warning(6, "x"), new Result.Warning(6, "x"));
        assertEquals(new Result.Warning(6, "x").hashCode(), new Result.Warning(6, "x").hashCode());
        assertNotEquals(new Result.Warning(6, "x"), new Result.Warning(7, "x"));
    }

    @Test
    void reflectionFromAnotherPackageReachesThemOnThePublicClass() throws Exception {
        // Declared on package-private Value, Method.invoke from outside the package throws IllegalAccessException.
        for (String name : new String[] {"equals", "hashCode", "toString"}) {
            Class<?>[] parameters = name.equals("equals") ? new Class<?>[] {Object.class} : new Class<?>[0];
            assertEquals(
                    Result.Warning.class,
                    Result.Warning.class.getMethod(name, parameters).getDeclaringClass(),
                    name);
        }
    }

    @Test
    void printsLikeARecord() {
        assertEquals("InProcess[pollAfter=PT2S]", new Status.InProcess(Duration.ofSeconds(2)).toString());
        assertEquals("Finished[]", new Status.Finished().toString());
        // Except an exchange, whose bodies run to kilobytes.
        assertEquals(
                "Exchange[messageId=" + EXCHANGE.messageId() + ", transactionId=null, request=0 chars, response=none]",
                EXCHANGE.toString());
    }

    @Test
    void textFromTheWireStaysOnOneLogLine() {
        Result.Exchange exchange = new Result.Exchange(EXCHANGE.messageId(), "", null, "id\r\nforged");
        Result rejected = new Result.Rejected(
                8, "x\nforged", Collections.singletonList(new Result.Warning(6, "y forged")), exchange);
        assertEquals(
                "Rejected[code=8, message=x\\u000aforged, warnings=[Warning[code=6, message=y\\u2028forged]],"
                        + " exchange=Exchange[messageId=" + EXCHANGE.messageId()
                        + ", transactionId=id\\u000d\\u000aforged, request=0 chars, response=none]]",
                rejected.toString());
    }
}
