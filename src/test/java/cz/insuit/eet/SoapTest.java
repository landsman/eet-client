package cz.insuit.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.SchemaFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class SoapTest {

    static final Path SPEC = Path.of(System.getProperty("eet.spec"));
    // Inside the playground response certificate's validity (2026-06-08 to 2027-06-08), so the recorded responses
    // keep verifying after it expires.
    private static final Instant RECORDED = Instant.parse("2026-11-10T16:11:45Z");
    private static final OffsetDateTime SENT = OffsetDateTime.parse("2027-01-09T16:45:40+01:00");

    @Test
    void writesARequestTheOfficialSchemaAccepts() throws Exception {
        Sale sale = Sale.of("CZ00000019", 243, "24/A-6/Brno_2", "#135433c/11/2027", SENT.minusSeconds(4), 326450)
                .withDelegatingEic("CZ683555118")
                .withForLaterDraw(100)
                .withDraw(-2500);
        Document doc = parse(Soap.request(Submission.of(sale), SENT, TestKeys.signer()));

        Element trzba =
                (Element) doc.getElementsByTagNameNS(Soap.EET_NS, "Trzba").item(0);
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(SPEC.resolve("EETXMLSchema.xsd").toFile())
                .newValidator()
                .validate(new DOMSource(trzba));

        Element data =
                (Element) trzba.getElementsByTagNameNS(Soap.EET_NS, "Data").item(0);
        assertEquals("2027-01-09T16:45:36+01:00", data.getAttribute("dat_trzby"));
        assertEquals("3264.50", data.getAttribute("celk_trzba"));
        assertEquals("-25.00", data.getAttribute("cerp_zuct"));
        assertEquals("", data.getAttribute("povereni_vice_popl"), "an option that does not apply is left out");
    }

    @Test
    void signsExactlyTheBodyWithTheCertificateItCarries() throws Exception {
        Signer signer = TestKeys.signer();
        Document doc = parse(Soap.request(Submission.of(sale()), SENT, signer));
        assertEquals(signer.certificate(), Soap.signedBy(doc));
    }

    @Test
    void aTamperedRequestNoLongerVerifies() throws Exception {
        String xml = new String(Soap.request(Submission.of(sale()), SENT, TestKeys.signer()), StandardCharsets.UTF_8);
        Document tampered =
                parse(xml.replace("celk_trzba=\"1.00\"", "celk_trzba=\"9.00\"").getBytes(StandardCharsets.UTF_8));
        assertThrows(Soap.InvalidResponseException.class, () -> Soap.signedBy(tampered));
    }

    @Test
    void aResendCarriesTheSameDataUnderANewHeader() throws Exception {
        Submission first = Submission.of(sale());
        Submission resend = first.resend();
        Element a = data(Soap.request(first, SENT, TestKeys.signer()));
        Element b = data(Soap.request(resend, SENT.plusMinutes(5), TestKeys.signer()));
        assertTrue(a.isEqualNode(b));
        assertNotEquals(first.messageId(), resend.messageId());
        assertEquals(false, resend.firstAttempt());
    }

    /** The interface's own signed samples check out the same way ours do, so the two read the format alike. */
    @ParameterizedTest
    @ValueSource(strings = {"CZ00000019", "CZ683555118", "CZ8551015704"})
    void theOfficialSampleRequestsVerify(String eic) throws Exception {
        Document doc = parse(Files.readAllBytes(SPEC.resolve("requests/" + eic + ".eet.v4.req.xml")));
        String subject = Soap.signedBy(doc).getSubjectX500Principal().getName();
        assertTrue(subject.contains("CN=" + eic), subject);
    }

    @Test
    void readsARecordedConfirmationAndTrustsItsSigner() throws Exception {
        Soap.Response response = Soap.parse(recorded("accepted-response.xml"));
        assertEquals("62ebb536-7dec-47a9-8786-986a62bcf140-ff", response.pok());
        assertEquals("90a4595c-288d-401d-bc20-08c8bcf1ed96", response.messageId());
        assertTrue(response.test());
        assertNull(verify(response.document(), "NTRCZ-72080043"));
    }

    @Test
    void readsAWarning() throws Exception {
        Soap.Response response = Soap.parse(recorded("warning-response.xml"));
        assertNotNull(response.pok());
        assertEquals(
                List.of(new Result.Warning(6, "id_jednotky neodpovida formatem pridelenemu c. evidencni jednotky")),
                response.warnings());
    }

    @Test
    void readsAnError() throws Exception {
        Soap.Response response = Soap.parse(recorded("verified-response.xml"));
        assertNull(response.pok());
        assertEquals(0, response.errorCode());
    }

    @Test
    void refusesAConfirmationWithAnotherPok() throws Exception {
        String xml = new String(recorded("accepted-response.xml"), StandardCharsets.UTF_8);
        Document doc = Soap.parse(
                        xml.replace("-986a62bcf140-ff", "-986a62bcf141-ff").getBytes(StandardCharsets.UTF_8))
                .document();
        assertEquals("the signature does not match the Body", verify(doc, "NTRCZ-72080043"));
    }

    @Test
    void refusesAConfirmationFromSomebodyElse() throws Exception {
        Document doc = Soap.parse(recorded("accepted-response.xml")).document();
        String problem = Objects.requireNonNull(verify(doc, "NTRCZ-00000000"));
        assertTrue(problem.startsWith("the signing certificate belongs to NTRCZ-72080043"), problem);
    }

    @Test
    void refusesAConfirmationOutsideItsCertificatesValidity() throws Exception {
        Document doc = Soap.parse(recorded("accepted-response.xml")).document();
        String problem = Soap.verify(
                doc,
                ica("ica-root-rsa-2022.pem"),
                ica("ica-public-ca-rsa-2022.pem"),
                "NTRCZ-72080043",
                Instant.parse("2027-06-09T00:00:00Z"));
        assertTrue(Objects.requireNonNull(problem).startsWith("the signing certificate does not chain"), problem);
    }

    @Test
    void refusesAFaultAndAnythingElse() {
        assertThrows(Soap.InvalidResponseException.class, () -> Soap.parse("""
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
                <soapenv:Fault><faultstring>nope</faultstring></soapenv:Fault></soapenv:Body></soapenv:Envelope>""".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Soap.InvalidResponseException.class, () -> Soap.parse("<html/>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                Soap.InvalidResponseException.class,
                () -> Soap.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    static Sale sale() {
        return Sale.of("CZ00000019", 11, "1", "2027/1", SENT, 100);
    }

    static byte[] recorded(String name) throws IOException {
        try (InputStream in = SoapTest.class.getResourceAsStream("playground/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }

    private static @Nullable String verify(Document doc, String organizationIdentifier) throws Exception {
        return Soap.verify(
                doc, ica("ica-root-rsa-2022.pem"), ica("ica-public-ca-rsa-2022.pem"), organizationIdentifier, RECORDED);
    }

    private static List<X509Certificate> ica(String name) throws Exception {
        try (InputStream in = EetClient.class.getResourceAsStream(name)) {
            return List.of(
                    (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
    }

    private static Element data(byte[] request) throws Exception {
        return (Element)
                parse(request).getElementsByTagNameNS(Soap.EET_NS, "Data").item(0);
    }

    static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
