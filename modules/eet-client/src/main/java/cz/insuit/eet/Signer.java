package cz.insuit.eet;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * A taxpayer's cash register certificate and its private key, which sign every message.
 *
 * <p>The tax office's CA issues it to one taxpayer, with their EI&Ccaron; as the CN, valid for 366 days, as a
 * PKCS#12 file with a password. One certificate serves any number of cash registers.
 */
public final class Signer {

    private final PrivateKey key;
    private final X509Certificate certificate;

    private Signer(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
    }

    /** A signer from a key and its certificate held elsewhere, a vault for example. */
    public static Signer of(PrivateKey key, X509Certificate certificate) {
        return new Signer(key, certificate);
    }

    /**
     * A signer from the PKCS#12 file the CA hands out. Its legacy 3DES protection is read by the JDK as it is; the
     * stream is not closed.
     *
     * @throws GeneralSecurityException when the password is wrong or the file holds no key
     */
    public static Signer fromPkcs12(InputStream pkcs12, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(pkcs12, password);
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias)
                    && store.getKey(alias, password) instanceof PrivateKey key
                    && store.getCertificate(alias) instanceof X509Certificate certificate) {
                return new Signer(key, certificate);
            }
        }
        throw new GeneralSecurityException("the PKCS#12 file holds no private key with a certificate");
    }

    /** The certificate that goes into every message. */
    public X509Certificate certificate() {
        return certificate;
    }

    PrivateKey key() {
        return key;
    }
}
