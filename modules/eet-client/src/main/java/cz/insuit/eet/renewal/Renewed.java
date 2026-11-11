package cz.insuit.eet.renewal;

import java.time.Instant;

/**
 * The new certificate as the CA hands it out: a PKCS#12 file and its 8-character password, which {@link
 * cz.insuit.eet.Signer#fromPkcs12} opens. Sensitive: neither belongs in a log.
 */
public final class Renewed {
    private final byte[] pkcs12;
    private final char[] password;
    private final String serialNumber;
    private final Instant validFrom;
    private final Instant validTo;

    Renewed(byte[] pkcs12, char[] password, String serialNumber, Instant validFrom, Instant validTo) {
        this.pkcs12 = pkcs12;
        this.password = password;
        this.serialNumber = serialNumber;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    /** The PKCS#12 file, a copy. */
    public byte[] pkcs12() {
        return pkcs12.clone();
    }

    /** Its password, a copy. */
    public char[] password() {
        return password.clone();
    }

    /** The new certificate's serial number, in decimal. */
    public String serialNumber() {
        return serialNumber;
    }

    /** When it starts being valid. */
    public Instant validFrom() {
        return validFrom;
    }

    /** When it stops being valid. */
    public Instant validTo() {
        return validTo;
    }

    @Override
    public String toString() {
        return "Renewed[serialNumber=" + serialNumber + ", validTo=" + validTo + "]";
    }
}
