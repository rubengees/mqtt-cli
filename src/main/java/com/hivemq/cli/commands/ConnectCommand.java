package com.hivemq.cli.commands;

import com.hivemq.cli.converters.*;
import com.hivemq.cli.impl.MqttAction;
import com.hivemq.cli.mqtt.MqttClientExecutor;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.MqttVersion;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.datatypes.Mqtt5UserProperties;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PayloadFormatIndicator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pmw.tinylog.Logger;
import picocli.CommandLine;

import javax.inject.Inject;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@CommandLine.Command(name = "con", aliases = "connect", description = "Connects an mqtt client")
public class ConnectCommand extends MqttCommand implements MqttAction {

    final MqttClientExecutor mqttClientExecutor;

    @Inject
    public ConnectCommand(final @NotNull MqttClientExecutor mqttClientExecutor) {

        this.mqttClientExecutor = mqttClientExecutor;

    }

    private static final String DEFAULT_TLS_VERSION = "TLSv1.2";

    @Nullable
    private MqttClientSslConfig sslConfig;

    private final List<X509Certificate> certificates = new ArrayList<>();

    //TODO Implement
    @CommandLine.Option(names = {"-pi", "--prefixIdentifier"}, description = "The prefix of the client Identifier UTF-8 String.")
    private String prefixIdentifier;

    @CommandLine.Option(names = {"-u", "--user"}, description = "The username for the client UTF-8 String.")
    @Nullable
    private String user;

    @CommandLine.Option(names = {"-pw", "--password"}, arity = "0..1", interactive = true, converter = ByteBufferConverter.class, description = "The password for the client UTF-8 String.")
    @Nullable
    private ByteBuffer password;

    @CommandLine.Option(names = {"-k", "--keepAlive"}, converter = UnsignedShortConverter.class, defaultValue = "60", description = "A keep alive of the client (in seconds).")
    private int keepAlive;

    @CommandLine.Option(names = {"-c", "--cleanStart"}, defaultValue = "true", description = "Define a clean start for the connection.")
    private boolean cleanStart;

    @CommandLine.Option(names = {"-wt", "--willTopic"}, description = "The topic of the will message.")
    @Nullable
    private String willTopic;

    @CommandLine.Option(names = {"-wm", "--willMessage"}, converter = ByteBufferConverter.class, description = "The payload of the will message.")
    @Nullable
    private ByteBuffer willMessage;

    @CommandLine.Option(names = {"-wq", "--willQualityOfService"}, converter = MqttQosConverter.class, defaultValue = "AT_MOST_ONCE", description = "Quality of Service for the will message.")
    @Nullable
    private MqttQos willQos;

    @CommandLine.Option(names = {"-wr", "--willRetain"}, defaultValue = "false", description = "Will message as retained message")
    private boolean willRetain;

    @CommandLine.Option(names = {"-we", "--willMessageExpiryInterval"}, defaultValue = "-1", description = "The lifetime of the Will Message in seconds.")
    private void checkWillMessageExpiryInterval(final String value) {
        if (Long.parseLong(value) == -1) {
            willMessageExpiryInterval = -1;
        } else {
            final UnsignedIntConverter converter = new UnsignedIntConverter();
            try {
                willMessageExpiryInterval = converter.convert(value);
            } catch (Exception ex) {
                if (isDebug()) {
                    Logger.debug(ex);
                }
                Logger.error(ex.getMessage());
            }

        }
    }

    private long willMessageExpiryInterval;

    @CommandLine.Option(names = {"-wd", "--willDelayInterval"}, converter = UnsignedIntConverter.class, defaultValue = "0", description = "The Server delays publishing the Client's Will Message until the Will Delay has passed.")
    private long willDelayInterval;

    @CommandLine.Option(names = {"-wp", "--willPayloadFormatIndicator"}, converter = PayloadFormatIndicatorConverter.class, description = "The Payload Format Indicator.")
    @Nullable
    private Mqtt5PayloadFormatIndicator willPayloadFormatIndicator;

    @CommandLine.Option(names = {"-wc", "--willContentType"}, description = "A description of Will Message's content.")
    @Nullable
    private String willContentType;

    @CommandLine.Option(names = {"-wrt", "--willResponseTopic"}, description = "The Topic Name for a response message.")
    @Nullable
    private String willResponseTopic;

    @CommandLine.Option(names = {"-wcd", "--willCorrelationData"}, converter = ByteBufferConverter.class, description = "The Correlation Data of the Will Message.")
    @Nullable
    private ByteBuffer willCorrelationData;

    @CommandLine.Option(names = {"-wu", "--willUserProperties"}, converter = UserPropertiesConverter.class, description = "The User Property of the Will Message. Usage: Key=Value, Key1=Value1|Key2=Value2")
    @Nullable
    private Mqtt5UserProperties willUserProperties;

    @CommandLine.Option(names = {"-se", "--sessionExpiryInterval"}, defaultValue = "0", converter = UnsignedIntConverter.class, description = "Session expiry can be disabled by setting it to 4_294_967_295")
    private long sessionExpiryInterval;

    @CommandLine.Option(names = {"-s", "--secure"}, defaultValue = "false", description = "Use default ssl configuration if no other ssl options are specified.")
    private boolean useSsl;

    @CommandLine.Option(names = {"--cafile"}, converter = FileToCertificateConverter.class, description = "Path to a file containing trusted CA certificates to enable encrypted certificate based communication.")
    private void addCAFile(X509Certificate certificate) {
        certificates.add(certificate);
    }

    @CommandLine.Option(names = {"--capath"}, converter = DirectoryToCertificateCollectionConverter.class, description = {"Path to a directory containing certificate files to import to enable encrypted certificate based communication."})
    private void addCACollection(Collection<X509Certificate> certs) {
        certificates.addAll(certs);
    }

    @CommandLine.Option(names = {"--ciphers"}, split = ":", description = "The client supported cipher suites list generated with 'openssl ciphers'.")
    private Collection<String> cipherSuites;

    @CommandLine.Option(names = {"--tls-version"}, description = "The TLS protocol version to use.")
    private Collection<String> supportedTLSVersions;

    @CommandLine.ArgGroup(exclusive = false)
    private ClientSideAuthentication clientSideAuthentication;

    static class ClientSideAuthentication {

        @CommandLine.Option(names = {"--cert"}, required = true, converter = FileToCertificateConverter.class, description = "The Client certificate to use for client-side authentication.")
        @Nullable X509Certificate clientCertificate;

        @CommandLine.Option(names = {"--key"}, required = true, converter = FileToPrivateKeyConverter.class, description = "The path to the client private key for client side authentication.")
        @Nullable PrivateKey clientPrivateKey;
    }

    @Override
    public void run() {

        handleConnectOptions();

        connect();
    }

    void handleConnectOptions() {
        if (useBuiltSslConfig()) {
            try {
                buildSslConfig();
            } catch (Exception e) {
                if (isDebug()) {
                    Logger.debug("Failed to build ssl config: {}", e);
                }
                Logger.error("Failed to build ssl config: {}", e.getMessage());
                return;
            }
        } else {
            sslConfig = null;
        }

        logUnusedOptions();
    }

    private void connect() {
        if (isVerbose()) {
            Logger.trace("Command: {} ", this);
        }

        try {
            mqttClientExecutor.connect(this);
        } catch (final Exception ex) {
            if (isDebug()) {
                Logger.debug(ex);
            }
            Logger.error(ex.getMessage());
        }
    }

    private void logUnusedOptions() {
        if (getVersion() == MqttVersion.MQTT_3_1_1) {
            if (sessionExpiryInterval != 0) {
                Logger.warn("Session Expiry was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
            if (willMessageExpiryInterval != -1) {
                Logger.warn("Will Message Expiry was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
            if (willPayloadFormatIndicator != null) {
                Logger.warn("Will Payload Format was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
            if (willDelayInterval != 0) {
                Logger.warn("Will Delay Interval was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
            if (willContentType != null) {
                Logger.warn("Will Content Type was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
            if (willResponseTopic != null) {
                Logger.warn("Will Response Topic was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
            if (willCorrelationData != null) {
                Logger.warn("Will Correlation Data was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
            if (willUserProperties != null) {
                Logger.warn("Will User Properties was set but is unused in MQTT Version {}", MqttVersion.MQTT_3_1_1);
            }
        }
    }

    private boolean useBuiltSslConfig() {
        return !certificates.isEmpty() ||
                cipherSuites != null ||
                supportedTLSVersions != null ||
                clientSideAuthentication != null ||
                useSsl;
    }

    @Override
    public Class getType() {
        return ConnectCommand.class;
    }

    @Override
    public String getKey() {
        return "client {" +
                "version=" + getVersion() +
                ", host='" + getHost() + '\'' +
                ", port=" + getPort() +
                ", identifier='" + getIdentifier() + '\'' +
                '}';
    }

    private void buildSslConfig() throws Exception {
        // use ssl Port if the user forgot to set it
        if (getPort() == MqttClient.DEFAULT_SERVER_PORT) setPort(MqttClient.DEFAULT_SERVER_PORT_SSL);

        // build trustManagerFactory for server side authentication and to enable tls
        TrustManagerFactory trustManagerFactory = null;
        if (!certificates.isEmpty()) {
            trustManagerFactory = buildTrustManagerFactory(certificates);
        }


        // build keyManagerFactory if clientSideAuthentication is used
        KeyManagerFactory keyManagerFactory = null;
        if (clientSideAuthentication != null) {
            keyManagerFactory = buildKeyManagerFactory(clientSideAuthentication.clientCertificate, clientSideAuthentication.clientPrivateKey);
        }

        // default to tlsv.2
        if (supportedTLSVersions == null) {
            supportedTLSVersions = new ArrayList<>();
            supportedTLSVersions.add(DEFAULT_TLS_VERSION);
        }

        sslConfig = MqttClientSslConfig.builder()
                .trustManagerFactory(trustManagerFactory)
                .keyManagerFactory(keyManagerFactory)
                .cipherSuites(cipherSuites)
                .protocols(supportedTLSVersions)
                .build();
    }

    public String createIdentifier() {
        if (getIdentifier() == null) {
            this.setIdentifier("hmqClient" + this.getVersion() + "-" + UUID.randomUUID().toString());
        }
        return getIdentifier();
    }


    private TrustManagerFactory buildTrustManagerFactory(final @NotNull Collection<X509Certificate> certCollection) throws Exception {

        final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);

        // add all certificates of the collection to the KeyStore
        int i = 1;
        for (final X509Certificate cert : certCollection) {
            final String alias = Integer.toString(i);
            ks.setCertificateEntry(alias, cert);
            i++;
        }

        final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

        trustManagerFactory.init(ks);

        return trustManagerFactory;
    }

    private KeyManagerFactory buildKeyManagerFactory(final @NotNull X509Certificate cert, final @NotNull PrivateKey key) throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException, UnrecoverableKeyException {

        final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

        ks.load(null, null);

        final Certificate[] certChain = new Certificate[1];
        certChain[0] = cert;
        ks.setKeyEntry("mykey", key, null, certChain);

        final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

        keyManagerFactory.init(ks, null);

        return keyManagerFactory;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    @Override
    public String toString() {

        return "Connect{" +
                "key=" + getKey() +
                ", " + connectOptions() +
                '}';
    }

    public String connectOptions() {
        return "prefixIdentifier='" + prefixIdentifier + '\'' +
                ", user='" + user + '\'' +
                ", keepAlive=" + keepAlive +
                ", cleanStart=" + cleanStart +
                ", willTopic='" + willTopic + '\'' +
                ", willQos=" + willQos +
                ", willMessage='" + willMessage + '\'' +
                ", willRetain=" + willRetain +
                ", willMessageExpiryInterval=" + willMessageExpiryInterval +
                ", willDelayInterval=" + willDelayInterval +
                ", willPayloadFormatIndicator=" + willPayloadFormatIndicator +
                ", willContentType='" + willContentType + '\'' +
                ", willResponseTopic='" + willResponseTopic + '\'' +
                ", willCorrelationData=" + willCorrelationData +
                ", willUserProperties=" + willUserProperties +
                ", sessionExpiryInterval=" + sessionExpiryInterval +
                ", useSsl=" + useSsl +
                ", sslConfig=" + sslConfig;
    }


    // GETTER AND SETTER

    public void setUseSsl(final boolean useSsl) {
        this.useSsl = useSsl;
    }

    public String getPrefixIdentifier() {
        return prefixIdentifier;
    }

    public void setPrefixIdentifier(final String prefixIdentifier) {
        this.prefixIdentifier = prefixIdentifier;
    }

    public String getUser() {
        return user;
    }

    public void setUser(final String user) {
        this.user = user;
    }

    public ByteBuffer getPassword() {
        return password;
    }

    public void setPassword(final ByteBuffer password) {
        this.password = password;
    }

    public int getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(final int keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isCleanStart() {
        return cleanStart;
    }

    public void setCleanStart(final boolean cleanStart) {
        this.cleanStart = cleanStart;
    }

    public long getWillMessageExpiryInterval() {
        return willMessageExpiryInterval;
    }

    public void setWillMessageExpiryInterval(final long willMessageExpiryInterval) {
        this.willMessageExpiryInterval = willMessageExpiryInterval;
    }

    public long getWillDelayInterval() {
        return willDelayInterval;
    }

    public void setWillDelayInterval(final long willDelayInterval) {
        this.willDelayInterval = willDelayInterval;
    }

    public Mqtt5PayloadFormatIndicator getWillPayloadFormatIndicator() {
        return willPayloadFormatIndicator;
    }

    public void setWillPayloadFormatIndicator(final Mqtt5PayloadFormatIndicator willPayloadFormatIndicator) {
        this.willPayloadFormatIndicator = willPayloadFormatIndicator;
    }

    public String getWillContentType() {
        return willContentType;
    }

    public void setWillContentType(final String willContentType) {
        this.willContentType = willContentType;
    }

    public String getWillResponseTopic() {
        return willResponseTopic;
    }

    public void setWillResponseTopic(final String willResponseTopic) {
        this.willResponseTopic = willResponseTopic;
    }

    public ByteBuffer getWillCorrelationData() {
        return willCorrelationData;
    }

    public void setWillCorrelationData(final ByteBuffer willCorrelationData) {
        this.willCorrelationData = willCorrelationData;
    }

    public Mqtt5UserProperties getWillUserProperties() {
        return willUserProperties;
    }

    public void setWillUserProperties(final Mqtt5UserProperties willUserProperties) {
        this.willUserProperties = willUserProperties;
    }

    public ByteBuffer getWillMessage() {
        return willMessage;
    }

    public void setWillMessage(final ByteBuffer willMessage) {
        this.willMessage = willMessage;
    }

    public MqttQos getWillQos() {
        return willQos;
    }

    public void setWillQos(final MqttQos willQos) {
        this.willQos = willQos;
    }

    public boolean isWillRetain() {
        return willRetain;
    }

    public void setWillRetain(final boolean willRetain) {
        this.willRetain = willRetain;
    }

    public long getSessionExpiryInterval() {
        return sessionExpiryInterval;
    }

    public void setSessionExpiryInterval(final long sessionExpiryInterval) {
        this.sessionExpiryInterval = sessionExpiryInterval;
    }

    public String getWillTopic() {
        return willTopic;
    }

    public void setWillTopic(final String willTopic) {
        this.willTopic = willTopic;
    }

    public MqttClientSslConfig getSslConfig() {
        return sslConfig;
    }

    public void setSslConfig(@Nullable MqttClientSslConfig sslConfig) {
        this.sslConfig = sslConfig;
    }

}
