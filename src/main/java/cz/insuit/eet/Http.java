package cz.insuit.eet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * HTTP/1.1 through HttpURLConnection, the client every JDK from 8 on has. An idle connection is kept for its
 * keep-alive and a new one resolves the name again, which production's two data centres behind DNS rely on.
 */
final class Http {

    private Http() {}

    /** A connection that gives up connecting, and then waiting on each read, after {@code timeout}. */
    static HttpURLConnection open(URI uri, String method, Duration timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod(method);
        // A redirect is an unexpected answer, not somewhere to send a signed request or a token.
        connection.setInstanceFollowRedirects(false);
        int millis = (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE);
        connection.setConnectTimeout(millis);
        connection.setReadTimeout(millis);
        return connection;
    }

    /**
     * Sends {@code body}, or none when null, and reads the answer whatever its status: an error's body is evidence
     * too. Read to the end, so the connection can serve the next request.
     */
    static byte[] exchange(HttpURLConnection connection, byte @Nullable [] body) throws IOException {
        if (body != null) {
            connection.setDoOutput(true);
            // Streamed, not buffered, which also stops the JDK from sending a POST twice on a stale connection.
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
        }
        InputStream in = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream();
        if (in == null) return new byte[0];
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = stream.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
