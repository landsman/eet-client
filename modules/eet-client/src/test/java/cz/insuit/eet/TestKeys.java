package cz.insuit.eet;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** The signing key build.gradle.kts has keytool make before the tests, so no private key is committed. */
public final class TestKeys {

    private TestKeys() {}

    public static Signer signer() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of(System.getProperty("eet.testKeystore")))) {
            return Signer.fromPkcs12(in, "test-password".toCharArray());
        }
    }
}
