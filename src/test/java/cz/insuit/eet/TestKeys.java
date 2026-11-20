package cz.insuit.eet;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** The signing key build.gradle.kts has keytool make before the tests, so no private key is committed. */
final class TestKeys {

    private TestKeys() {}

    static Signer signer() throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get(System.getProperty("eet.testKeystore")))) {
            return Signer.fromPkcs12(in, "test-password".toCharArray());
        }
    }
}
