package com.blazemeter.jmeter.blazor.recorder;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Decrypts the browser's HTTPS tunnel. The certificate authority is written
 * to disk so the browser can trust it once. Inside the tunnel the recorder
 * offers only HTTP/1.1, which is enough to see a Blazor circuit even when
 * the site itself prefers HTTP/2.
 */
final class RecordingTls {

    private static final char[] PASSWORD = "blazor-recorder".toCharArray();
    private static final long DAY = 24L * 60 * 60 * 1000;

    private Path directory;
    private final ConcurrentHashMap<String, SSLContext> servers = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private PrivateKey caKey;
    private X509Certificate caCertificate;

    RecordingTls(Path directory) {
        this.directory = directory;
    }

    Path certificateFile() {
        return directory.resolve("ca.crt");
    }

    void setDirectory(Path directory) {
        synchronized (this) {
            this.directory = directory;
            caKey = null;
            caCertificate = null;
            servers.clear();
        }
    }

    /** Writes the authority the browser has to trust. */
    void ensureCertificate() throws Exception {
        load();
    }

    /** Server context for tests that need a site certificate. */
    SSLContext serverContext(String host) throws Exception {
        return context(host);
    }

    /**
     * Browser-facing socket. The certificate names {@code host}.
     */
    SSLSocket accept(Socket client, InputStream buffered, String host) throws Exception {
        SSLSocket socket = (SSLSocket) context(host).getSocketFactory().createSocket(client, buffered, true);
        socket.setUseClientMode(false);
        http1(socket);
        handshake(socket);
        return socket;
    }

    /**
     * Origin-facing socket layered on a TCP connection that is already open.
     */
    SSLSocket connect(Socket tcp, String host, int port, boolean trustAll) throws Exception {
        SSLContext context = trustAll ? trustAll() : SSLContext.getDefault();
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(tcp, bare(host), port, true);
        socket.setUseClientMode(true);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setApplicationProtocols(new String[] {"http/1.1"});
        if (!trustAll) {
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
        }
        socket.setSSLParameters(parameters);
        handshake(socket);
        return socket;
    }

    private SSLContext context(String host) throws Exception {
        load();
        return servers.computeIfAbsent(host, this::leafContext);
    }

    private SSLContext leafContext(String host) {
        try {
            KeyPair leaf = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            X509Certificate certificate = sign(leaf, host, false);
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("leaf", leaf.getPrivate(), PASSWORD, new Certificate[] {certificate, caCertificate});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, random);
            return context;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not mint a certificate for " + host, ex);
        }
    }

    private void load() throws Exception {
        if (caCertificate != null) {
            return;
        }
        synchronized (this) {
            if (caCertificate != null) {
                return;
            }
            Files.createDirectories(directory);
            Path store = directory.resolve("ca.p12");
            if (Files.isRegularFile(store)) {
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(store)) {
                    keyStore.load(in, PASSWORD);
                }
                caKey = (PrivateKey) keyStore.getKey("ca", PASSWORD);
                caCertificate = (X509Certificate) keyStore.getCertificate("ca");
                return;
            }
            KeyPairGenerator generated = KeyPairGenerator.getInstance("RSA");
            generated.initialize(2048);
            KeyPair pair = generated.generateKeyPair();
            caKey = pair.getPrivate();
            caCertificate = sign(pair, "Blazor Recorder CA", true);
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("ca", caKey, PASSWORD, new Certificate[] {caCertificate});
            try (OutputStream out = Files.newOutputStream(store)) {
                keyStore.store(out, PASSWORD);
            }
            Files.writeString(certificateFile(), pem(caCertificate));
        }
    }

    private X509Certificate sign(KeyPair pair, String host, boolean authority) throws Exception {
        X500Name subject = new X500Name(authority ? "CN=Blazor Recorder CA" : "CN=Blazor Recorder");
        X500Name issuer = authority ? subject : new X500Name(caCertificate.getSubjectX500Principal().getName());
        Date now = new Date();
        Date until = new Date(now.getTime() + (authority ? 3650 : 397) * DAY);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer,
                new BigInteger(64, random), now, until, subject, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(authority));
        if (authority) {
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        } else {
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(name(bare(host))));
        }
        PrivateKey signerKey = authority ? pair.getPrivate() : caKey;
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(signerKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static GeneralName name(String host) {
        if (isAddress(host)) {
            return new GeneralName(GeneralName.iPAddress, host);
        }
        return new GeneralName(GeneralName.dNSName, host);
    }

    private static boolean isAddress(String host) {
        String bare = bare(host);
        if (bare.indexOf(':') < 0 && !bare.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return false;
        }
        try {
            InetAddress.getByName(bare);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String bare(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static String pem(X509Certificate certificate) throws Exception {
        String body = java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }

    private static void http1(SSLSocket socket) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setApplicationProtocols(new String[] {"http/1.1"});
        socket.setSSLParameters(parameters);
    }

    private static void handshake(SSLSocket socket) throws Exception {
        socket.setSoTimeout(15_000);
        socket.startHandshake();
        socket.setSoTimeout(0);
    }

    private static SSLContext trustAll() throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust, null);
        return context;
    }
}
