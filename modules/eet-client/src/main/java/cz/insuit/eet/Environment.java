package cz.insuit.eet;

import java.net.URI;

/** Where messages go. */
public enum Environment {
    /**
     * The state's test service. Nothing is stored and a POK is not valid. It is reached only from some networks:
     * a Czech one got through on 2026-10-25, a cloud CI runner timed out.
     */
    PLAYGROUND("https://pg.trzbyeet.gov.cz/eet/services/EETServiceSOAP/v4"),
    /** The real service, from 2027-01-01. */
    PRODUCTION("https://trzbyeet.gov.cz/eet/services/EETServiceSOAP/v4");

    private final URI endpoint;

    Environment(String endpoint) {
        this.endpoint = URI.create(endpoint);
    }

    /** The SOAP endpoint. */
    public URI endpoint() {
        return endpoint;
    }
}
