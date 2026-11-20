package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The equals, hashCode and toString the records used to give. */
class ValueTest {

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
    void printsLikeARecord() {
        assertEquals("InProcess[pollAfter=PT2S]", new Status.InProcess(Duration.ofSeconds(2)).toString());
        assertEquals("Finished[]", new Status.Finished().toString());
        UUID id = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertEquals(
                "Exchange[messageId=" + id + ", request=, response=null, transactionId=null]",
                new Result.Exchange(id, "", null, null).toString());
    }
}
