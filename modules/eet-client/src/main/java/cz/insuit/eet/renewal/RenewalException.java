package cz.insuit.eet.renewal;

import java.io.IOException;
import org.jspecify.annotations.Nullable;

/** An answer other than success: the HTTP status, and the CA's error code and message when it sent them. */
public final class RenewalException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final @Nullable Integer code;

    RenewalException(int status, @Nullable Integer code, String message) {
        super("HTTP " + status + (code == null ? "" : ", code " + code) + ": " + message);
        this.status = status;
        this.code = code;
    }

    /** The HTTP status: 401 a certificate the CA does not accept, 409 a request in the wrong state, 429 too often. */
    public int status() {
        return status;
    }

    /** The CA's own error code, when the body carried one. */
    public @Nullable Integer code() {
        return code;
    }
}
