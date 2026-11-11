package cz.insuit.eet.renewal;

import java.net.URI;

/** Where the CA's API runs. There is none for the playground: its certificates cannot be renewed. */
public enum Server {
    /** The CA's trial environment, {@code zkušební} in the specification. */
    TRIAL("https://ca.zkus.caeet.gov.cz/api"),
    /** The real CA, which renews production certificates. */
    PRODUCTION("https://ca.caeet.gov.cz/api");

    private final URI base;

    Server(String base) {
        this.base = URI.create(base);
    }

    /** The API's base URL. */
    public URI base() {
        return base;
    }
}
