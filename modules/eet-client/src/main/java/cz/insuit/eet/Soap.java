package cz.insuit.eet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import javax.xml.XMLConstants;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.spec.ExcC14NParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The SOAP envelope both ways: a signed request out, a parsed and verified response in. Everything here is the JDK's
 * own XML, XML signature and PKIX code; the interface is described in EET_popis_rozhrani, chapters 3 and 5.
 */
final class Soap {

    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String EET_NS = "http://fs.gov.cz/eet/schema/v4";
    static final String WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    static final String WSU_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    static final String DS_NS = XMLSignature.XMLNS;
    static final String SOAP_ACTION = "http://fs.gov.cz/eet/OdeslaniTrzby";
    private static final String X509_TOKEN =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3";
    private static final String BASE64 =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";
    // Always an offset, never Z: the interface describes 25 characters, +01:00 in winter and +02:00 in summer.
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");
    // organizationIdentifier, which X500Principal has no keyword for.
    private static final String ORGANIZATION_IDENTIFIER = "2.5.4.97";

    private Soap() {}

    // ---- request

    /** The request for {@code submission}, sent at {@code sentAt}, signed by {@code signer}. */
    static byte[] request(Submission submission, OffsetDateTime sentAt, Signer signer) {
        Document doc = newDocument();
        Element envelope = doc.createElementNS(SOAP_NS, "soapenv:Envelope");
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:soapenv", SOAP_NS);
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:v4", EET_NS);
        doc.appendChild(envelope);
        Element header = child(envelope, SOAP_NS, "soapenv:Header");
        Element body = child(envelope, SOAP_NS, "soapenv:Body");

        Element trzba = child(body, EET_NS, "v4:Trzba");
        Element hlavicka = child(trzba, EET_NS, "v4:Hlavicka");
        hlavicka.setAttribute("uuid_zpravy", submission.messageId().toString());
        hlavicka.setAttribute("dat_odesl", TIME.format(sentAt));
        hlavicka.setAttribute("prvni_zaslani", String.valueOf(submission.firstAttempt()));
        if (submission.verification()) hlavicka.setAttribute("overeni", "true");

        Sale sale = submission.sale();
        Element data = child(trzba, EET_NS, "v4:Data");
        data.setAttribute("eic_popl", sale.eic());
        String delegating = sale.delegatingEic();
        if (delegating != null) data.setAttribute("eic_poverujiciho", delegating);
        if (sale.delegatedByMany()) data.setAttribute("povereni_vice_popl", "true");
        data.setAttribute("id_jednotky", String.valueOf(sale.unitId()));
        data.setAttribute("id_pokl", sale.registerId());
        data.setAttribute("porad_cis", sale.sequenceNumber());
        data.setAttribute("dat_trzby", TIME.format(sale.time()));
        data.setAttribute("celk_trzba", Sale.amount(sale.total()));
        Long forLaterDraw = sale.forLaterDraw();
        if (forLaterDraw != null) data.setAttribute("urceno_cerp_zuct", Sale.amount(forLaterDraw));
        Long draw = sale.draw();
        if (draw != null) data.setAttribute("cerp_zuct", Sale.amount(draw));

        sign(header, body, signer);
        return serialize(doc);
    }

    /**
     * Signs {@code body} the way chapter 5.2 of the interface wants it: one WS-Security header holding the
     * certificate as a BinarySecurityToken and an XML signature over the Body alone, exclusive canonicalisation,
     * SHA-256 and RSA-SHA256. Nothing else is signed and nothing else is added: the service may take extra parts for
     * an attack. The responses are signed the same way, which is why the tests can reuse this.
     */
    static void sign(Element header, Element body, Signer signer) {
        Document doc = header.getOwnerDocument();
        String bodyId = "id-" + UUID.randomUUID();
        String tokenId = "X509-" + UUID.randomUUID();
        body.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsu", WSU_NS);
        body.setAttributeNS(WSU_NS, "wsu:Id", bodyId);
        body.setIdAttributeNS(WSU_NS, "Id", true);

        Element security = child(header, WSSE_NS, "wsse:Security");
        security.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsse", WSSE_NS);
        security.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsu", WSU_NS);
        Element token = child(security, WSSE_NS, "wsse:BinarySecurityToken");
        token.setAttribute("EncodingType", BASE64);
        token.setAttribute("ValueType", X509_TOKEN);
        token.setAttributeNS(WSU_NS, "wsu:Id", tokenId);
        try {
            token.setTextContent(
                    Base64.getEncoder().encodeToString(signer.certificate().getEncoded()));

            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            Reference reference = factory.newReference(
                    "#" + bodyId,
                    factory.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(factory.newTransform(CanonicalizationMethod.EXCLUSIVE, new ExcC14NParameterSpec())),
                    null,
                    null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, new ExcC14NParameterSpec()),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                    List.of(reference));

            Element tokenReference = doc.createElementNS(WSSE_NS, "wsse:SecurityTokenReference");
            Element tokenPointer = child(tokenReference, WSSE_NS, "wsse:Reference");
            tokenPointer.setAttribute("URI", "#" + tokenId);
            tokenPointer.setAttribute("ValueType", X509_TOKEN);
            KeyInfo keyInfo = factory.getKeyInfoFactory().newKeyInfo(List.of(new DOMStructure(tokenReference)));

            DOMSignContext context = new DOMSignContext(signer.key(), security);
            context.setDefaultNamespacePrefix("ds");
            factory.newXMLSignature(signedInfo, keyInfo).sign(context);
        } catch (GeneralSecurityException | MarshalException | XMLSignatureException e) {
            throw new IllegalStateException("could not sign the message", e);
        }
    }

    // ---- response

    /** A response as parsed: exactly one of {@code pok} and {@code errorCode} is set. */
    record Response(
            @Nullable String messageId,
            @Nullable String pok,
            boolean test,
            @Nullable Integer errorCode,
            String errorText,
            List<Result.Warning> warnings,
            Document document) {}

    /** Parses a response body; throws when it is not an {@code Odpoved}, a SOAP fault included. */
    static Response parse(byte[] bytes) throws InvalidResponseException {
        Document doc;
        try {
            doc = parser().parse(new ByteArrayInputStream(bytes));
        } catch (SAXException | IOException e) {
            throw new InvalidResponseException("not XML: " + e.getMessage());
        }
        Element body = only(doc.getDocumentElement(), SOAP_NS, "Body");
        if (body == null) throw new InvalidResponseException("no SOAP Body");
        Element fault = only(body, SOAP_NS, "Fault");
        if (fault != null)
            throw new InvalidResponseException(
                    "SOAP fault: " + fault.getTextContent().strip());
        Element odpoved = only(body, EET_NS, "Odpoved");
        if (odpoved == null) throw new InvalidResponseException("no Odpoved in the Body");

        Element hlavicka = only(odpoved, EET_NS, "Hlavicka");
        String messageId = hlavicka == null ? null : attribute(hlavicka, "uuid_zpravy");
        List<Result.Warning> warnings = new ArrayList<>();
        for (Element w : children(odpoved, EET_NS, "Varovani")) {
            warnings.add(new Result.Warning(
                    integer(attribute(w, "kod_varov")), w.getTextContent().strip()));
        }
        Element potvrzeni = only(odpoved, EET_NS, "Potvrzeni");
        String pok = potvrzeni == null ? null : attribute(potvrzeni, "pok");
        if (potvrzeni != null && pok != null) {
            return new Response(messageId, pok, isTrue(potvrzeni, "test"), null, "", warnings, doc);
        }
        Element chyba = only(odpoved, EET_NS, "Chyba");
        String code = chyba == null ? null : attribute(chyba, "kod");
        if (chyba != null && code != null) {
            return new Response(
                    messageId,
                    null,
                    isTrue(chyba, "test"),
                    integer(code),
                    chyba.getTextContent().strip(),
                    warnings,
                    doc);
        }
        throw new InvalidResponseException("Odpoved has neither Potvrzeni with a pok nor Chyba with a kod");
    }

    /**
     * Checks that a confirmation is signed over its Body by a certificate that chains to {@code anchors} through
     * {@code intermediates}, was valid at {@code at}, and belongs to {@code organizationIdentifier}: the tax office
     * (GF&Rcaron;, NTRCZ-72080043), whose certificate I.CA issues. Revocation is not checked, so verifying makes no
     * network call.
     *
     * @return null when it checks out, otherwise why not
     */
    static @Nullable String verify(
            Document doc,
            List<X509Certificate> anchors,
            List<X509Certificate> intermediates,
            String organizationIdentifier,
            Instant at) {
        X509Certificate signer;
        try {
            signer = signedBy(doc);
        } catch (InvalidResponseException e) {
            return e.getMessage();
        }

        try {
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(signer);
            PKIXBuilderParameters params = new PKIXBuilderParameters(
                    anchors.stream().map(a -> new TrustAnchor(a, null)).collect(Collectors.toSet()), target);
            List<X509Certificate> known = new ArrayList<>(intermediates);
            known.add(signer);
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(known)));
            params.setRevocationEnabled(false);
            params.setDate(Date.from(at));
            CertPathBuilder.getInstance("PKIX").build(params);
        } catch (GeneralSecurityException e) {
            return "the signing certificate does not chain to a trusted issuer: " + e.getMessage();
        }

        String owner = organizationIdentifier(signer.getSubjectX500Principal());
        if (!organizationIdentifier.equals(owner)) {
            return "the signing certificate belongs to " + owner + ", not " + organizationIdentifier;
        }
        return null;
    }

    /**
     * The certificate in the WS-Security header, once its signature is checked to cover exactly the Body and to
     * match it. Says nothing about who the certificate belongs to.
     */
    static X509Certificate signedBy(Document doc) throws InvalidResponseException {
        Element root = doc.getDocumentElement();
        Element header = only(root, SOAP_NS, "Header");
        Element security = header == null ? null : only(header, WSSE_NS, "Security");
        Element token = security == null ? null : only(security, WSSE_NS, "BinarySecurityToken");
        Element signature = security == null ? null : only(security, DS_NS, "Signature");
        Element body = only(root, SOAP_NS, "Body");
        if (token == null || signature == null || body == null)
            throw new InvalidResponseException("no WS-Security signature");

        X509Certificate signer;
        try {
            byte[] der = Base64.getMimeDecoder().decode(token.getTextContent());
            signer = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new InvalidResponseException("the BinarySecurityToken is not a certificate: " + e.getMessage());
        }

        String bodyId = body.getAttributeNS(WSU_NS, "Id");
        if (bodyId.isEmpty()) throw new InvalidResponseException("the Body has no wsu:Id");
        body.setIdAttributeNS(WSU_NS, "Id", true);
        try {
            DOMValidateContext context = new DOMValidateContext(signer.getPublicKey(), signature);
            XMLSignature xmlSignature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            // Exactly the Body, by its own id: a valid signature over some other element proves nothing about it.
            List<?> references = xmlSignature.getSignedInfo().getReferences();
            if (references.size() != 1 || !("#" + bodyId).equals(((Reference) references.get(0)).getURI())) {
                throw new InvalidResponseException("the signature does not cover exactly the Body");
            }
            if (!xmlSignature.validate(context)) {
                throw new InvalidResponseException("the signature does not match the Body");
            }
        } catch (MarshalException | XMLSignatureException e) {
            throw new InvalidResponseException("the signature cannot be read: " + e.getMessage());
        }
        return signer;
    }

    /** Thrown for a body that is not an EET response. */
    static final class InvalidResponseException extends Exception {
        InvalidResponseException(String message) {
            super(message);
        }
    }

    // ---- XML plumbing

    static Document newDocument() {
        return parser().newDocument();
    }

    static byte[] serialize(Document doc) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // It only writes our own document, but nothing it is ever handed may fetch a DTD or a stylesheet.
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException e) {
            throw new IllegalStateException("could not write the XML", e);
        }
    }

    /** A namespace-aware parser that refuses a DOCTYPE, so no entity or external reference is ever resolved. */
    private static DocumentBuilder parser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the JDK's XML parser is missing a feature", e);
        }
    }

    private static Element child(Element parent, String namespace, String qualifiedName) {
        Element child = parent.getOwnerDocument().createElementNS(namespace, qualifiedName);
        parent.appendChild(child);
        return child;
    }

    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e
                    && namespace.equals(e.getNamespaceURI())
                    && localName.equals(e.getLocalName())) {
                found.add(e);
            }
        }
        return found;
    }

    private static @Nullable Element only(Node parent, String namespace, String localName) {
        if (!(parent instanceof Element element)) return null;
        List<Element> found = children(element, namespace, localName);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static @Nullable String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static boolean isTrue(Element element, String name) {
        String value = element.getAttribute(name);
        return value.equals("true") || value.equals("1");
    }

    private static int integer(@Nullable String value) throws InvalidResponseException {
        try {
            return Integer.parseInt(value == null ? "" : value.strip());
        } catch (NumberFormatException e) {
            throw new InvalidResponseException("not a number: " + value);
        }
    }

    private static @Nullable String organizationIdentifier(X500Principal subject) {
        try {
            String name = subject.getName(X500Principal.RFC2253, Map.of(ORGANIZATION_IDENTIFIER, "ORGID"));
            for (Rdn rdn : new LdapName(name).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("ORGID")) return String.valueOf(rdn.getValue());
            }
            return null;
        } catch (InvalidNameException e) {
            return null;
        }
    }
}
