package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;

/** The promises HttpURLConnection's defaults would break: one call is one attempt, to the address given, over TLS checked as the JDK checks it. */
class HttpTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void anHttpsConnectionIgnoresTheJvmWideTrustSettings() throws Exception {
        SSLSocketFactory factory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        SSLContext other = SSLContext.getInstance("TLS");
        other.init(null, null, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(other.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
        try {
            HttpsURLConnection https =
                    (HttpsURLConnection) Http.open(URI.create("https://127.0.0.1:1/"), "GET", TIMEOUT);
            assertNotSame(other.getSocketFactory(), https.getSSLSocketFactory());
            assertFalse(https.getHostnameVerifier().verify("127.0.0.1", null));
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(factory);
            HttpsURLConnection.setDefaultHostnameVerifier(verifier);
        }
    }

    @Test
    void aPostIsSentOnceEvenWhenTheConnectionDiesUnanswered() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                while (true) {
                    try (Socket socket = listener.accept()) {
                        connections.incrementAndGet();
                        drain(socket);
                    } catch (IOException e) {
                        return; // the listener is closed
                    }
                }
            });
            server.setDaemon(true);
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/");
            assertThrows(IOException.class, () -> Http.exchange(Http.open(uri, "POST", TIMEOUT), new byte[] {1, 2, 3}));
        }
        // Without fixed-length streaming the JDK sends a buffered POST again on a new connection.
        assertEquals(1, connections.get());
    }

    @Test
    void aRedirectIsNotFollowed() throws Exception {
        AtomicInteger elsewhere = new AtomicInteger();
        HttpServer target = server(exchange -> {
            elsewhere.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer redirect = server(exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            URI uri = URI.create("http://127.0.0.1:" + redirect.getAddress().getPort() + "/");
            HttpURLConnection connection = Http.open(uri, "GET", TIMEOUT);
            connection.setRequestProperty("Authorization", "Bearer token");
            Http.exchange(connection, null);
            assertEquals(302, connection.getResponseCode());
            assertEquals(0, elsewhere.get());
        } finally {
            redirect.stop(0);
            target.stop(0);
        }
    }

    private static HttpServer server(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    // Reads the request until the client goes quiet, then the caller closes without a word.
    private static void drain(Socket socket) throws IOException {
        socket.setSoTimeout(200);
        InputStream in = socket.getInputStream();
        try {
            while (in.read() != -1) {}
        } catch (SocketTimeoutException quiet) {
            // all of it has arrived
        }
    }
}
