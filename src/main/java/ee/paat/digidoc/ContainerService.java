package ee.paat.digidoc;

import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import org.digidoc4j.Configuration;
import org.digidoc4j.Container;
import org.digidoc4j.ContainerBuilder;
import org.digidoc4j.ContainerValidationResult;
import org.digidoc4j.DataFile;
import org.digidoc4j.Signature;
import org.digidoc4j.SignatureProfile;
import org.digidoc4j.ValidationResult;
import org.digidoc4j.X509Cert;
import org.digidoc4j.exceptions.DigiDoc4JException;
import org.digidoc4j.impl.asic.tsl.TSLCertificateSourceImpl;
import org.digidoc4j.impl.asic.AsicSignature;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Read-only container operations; extraction is the sole document filesystem write. */
public final class ContainerService {
    private final AppConfig config;
    private final PathPolicy paths;
    private final Configuration digiDoc;
    private final Configuration inspection;

    public ContainerService(AppConfig config) {
        this.config = config;
        this.paths = new PathPolicy(config.roots());
        this.digiDoc = new BundledConfiguration(config.mode());
        this.inspection = new BundledConfiguration(config.mode());
        // Parsing signatures asks for a trust source even without validation. Keep inspection offline.
        inspection.setTSL(new TSLCertificateSourceImpl());
        inspection.setAiaSourceFactory(() -> certificate -> Set.of());
        digiDoc.setConnectionTimeout(15_000);
        digiDoc.setSocketTimeout(30_000);
        digiDoc.setTslFileLoaderFactory(() -> {
            try {
                Files.createDirectories(config.tslCache());
            } catch (IOException e) {
                throw new ToolException("CACHE_UNAVAILABLE", "The TSL cache directory is unavailable.");
            }
            CommonsDataLoader online = new CommonsDataLoader();
            online.setTimeoutConnection(digiDoc.getConnectionTimeout());
            online.setTimeoutConnectionRequest(digiDoc.getConnectionTimeout());
            online.setTimeoutSocket(digiDoc.getSocketTimeout());
            FileCacheDataLoader cache = new FileCacheDataLoader(online);
            cache.setFileCacheDirectory(config.tslCache().toFile());
            cache.setCacheExpirationTime(digiDoc.getTslCacheExpirationTime());
            return cache;
        });
        inspection.setTslFileLoaderFactory(digiDoc.getTslFileLoaderFactory());
    }

    /** DigiDoc normally reads cwd YAML implicitly; the server's only configuration surface is its environment. */
    private static final class BundledConfiguration extends Configuration {
        private BundledConfiguration(Mode mode) { super(mode); }

        @Override
        public Hashtable<String, String> loadConfiguration(String ignored) {
            String resource = isTest() ? "/digidoc4j-test.yaml" : "/digidoc4j.yaml";
            InputStream input = Configuration.class.getResourceAsStream(resource);
            if (input == null) throw new ToolException("INVALID_CONFIG", "Bundled DigiDoc configuration is unavailable.");
            return super.loadConfiguration(input);
        }

        @Override
        public Hashtable<String, String> loadConfiguration(String file, boolean reload) {
            return reload ? loadConfiguration(file) : super.loadConfiguration(file, false);
        }
    }

    public synchronized Map<String, Object> openContainer(String path) {
        return safely(() -> {
            Container container = open(path);
            List<Map<String, Object>> files = new ArrayList<>();
            for (DataFile file : container.getDataFiles()) {
                files.add(object("name", dataName(file), "mime_type", bounded(file.getMediaType()), "size_bytes", file.getFileSize()));
            }
            return object("type", container.getType(), "data_files", files, "signature_count", container.getSignatures().size());
        });
    }

    public synchronized Map<String, Object> listSignatures(String path) {
        return safely(() -> {
            List<Map<String, Object>> signatures = new ArrayList<>();
            for (Signature signature : open(path).getSignatures()) {
                X509Cert cert = signature.getSigningCertificate();
                signatures.add(object("id", bounded(signature.getId()),
                        "signer_common_name", subject(cert, X509Cert.SubjectName.CN),
                        "personal_identification_code", subject(cert, X509Cert.SubjectName.SERIALNUMBER),
                        "claimed_signing_time", instant(signature.getClaimedSigningTime()),
                        "trusted_signing_time", instant(signature.getTrustedSigningTime()),
                        "profile", inspectionProfile(signature),
                        "signature_method", bounded(signature.getSignatureMethod()),
                        "certificate_issuer", cert == null ? null : bounded(cert.getX509Certificate().getIssuerX500Principal().getName())));
            }
            return object("signatures", signatures);
        });
    }

    public synchronized Map<String, Object> extractFile(String path, String fileName, String outputDir, boolean overwrite) {
        return safely(() -> {
            PathPolicy.fileName(fileName);
            Path directory = paths.existing(outputDir, true);
            Container container = open(path);
            List<DataFile> matches = container.getDataFiles().stream().filter(f -> fileName.equals(dataName(f))).toList();
            if (matches.isEmpty()) throw new ToolException("FILE_NOT_FOUND", "No data file has the requested name.");
            if (matches.size() != 1) throw new ToolException("AMBIGUOUS_FILE_NAME", "The container contains duplicate data file names.");
            DataFile file = matches.getFirst();
            try (SecureDirectoryStream<Path> secure = PathPolicy.openDirectory(directory)) {
                Path target = Path.of(fileName);
                BasicFileAttributes existing = attributes(secure, target);
                if (existing != null && (existing.isSymbolicLink() || !existing.isRegularFile())) {
                    throw new ToolException("INVALID_TARGET", "Extraction target must be a regular file, never a symbolic link.");
                }
                if (existing != null && !overwrite) throw new ToolException("TARGET_EXISTS", "Extraction target already exists.");
                Path writing = overwrite ? Path.of(".digidoc-mcp-" + UUID.randomUUID() + ".tmp") : target;
                boolean created = false;
                try {
                    MessageDigest digest = sha256();
                    try (var channel = secure.newByteChannel(writing, Set.of(StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                        created = true;
                        try (InputStream input = file.getStream(); OutputStream output = Channels.newOutputStream(channel)) {
                            copyBounded(input, output, config.maxBytes(), digest);
                        }
                    }
                    if (overwrite) {
                        // Rename replaces the directory entry, never follows an existing symlink or hard link.
                        secure.move(writing, secure, target);
                    }
                    created = false;
                    return object("path", directory.resolve(target).toString(), "sha256", HexFormat.of().formatHex(digest.digest()));
                } finally {
                    if (created) secure.deleteFile(writing);
                }
            } catch (FileAlreadyExistsException e) {
                throw new ToolException("TARGET_EXISTS", "Extraction target already exists.");
            } catch (IOException e) {
                throw new ToolException("EXTRACTION_FAILED", "The data file could not be extracted.");
            }
        });
    }

    public synchronized Map<String, Object> validateContainer(String path) {
        return safely(() -> {
            Container container = open(path, digiDoc);
            ContainerValidationResult report = container.validate();
            List<Map<String, Object>> signatures = new ArrayList<>();
            boolean failed = !report.getContainerErrors().isEmpty();
            boolean indeterminate = false;
            for (Signature signature : container.getSignatures()) {
                String id = signature.getUniqueId();
                String indication = name(report.getIndication(id));
                String subIndication = name(report.getSubIndication(id));
                ValidationResult result = report.getValidationResult(id);
                if (indication == null) indication = result == null ? "INDETERMINATE"
                        : result.isValid() ? "TOTAL_PASSED" : "TOTAL_FAILED";
                if (indication.equals("TOTAL_FAILED") || indication.equals("FAILED")) failed = true;
                else if (!indication.equals("TOTAL_PASSED") && !indication.equals("PASSED")) indeterminate = true;
                signatures.add(object("id", bounded(signature.getId()), "indication", indication,
                        "sub_indication", subIndication,
                        "errors", diagnostics(result == null ? List.of() : result.getErrors(), false),
                        "warnings", diagnostics(result == null ? List.of() : result.getWarnings(), true),
                        "signer", subject(signature.getSigningCertificate(), X509Cert.SubjectName.CN),
                        "signing_time", instant(signature.getTrustedSigningTime() == null
                                ? signature.getClaimedSigningTime() : signature.getTrustedSigningTime()),
                        "profile", profile(signature.getProfile())));
            }
            // Timestamp-only ASiC-S containers must also contribute their cryptographic result.
            for (String id : report.getTimestampIdList()) {
                String indication = name(report.getIndication(id));
                if ("TOTAL_FAILED".equals(indication) || "FAILED".equals(indication)) failed = true;
                else if (!"TOTAL_PASSED".equals(indication) && !"PASSED".equals(indication)) indeterminate = true;
            }
            String result = failed ? "INVALID" : indeterminate ? "INDETERMINATE" : report.isValid() ? "VALID" : "INVALID";
            if (container.getSignatures().isEmpty() && report.getTimestampIdList().isEmpty()) result = "INDETERMINATE";
            return object("result", result, "signatures", signatures,
                    "errors", diagnostics(report.getContainerErrors(), false), "warnings", diagnostics(report.getContainerWarnings(), true));
        });
    }

    private Container open(String value) {
        return open(value, inspection);
    }

    private Container open(String value, Configuration configuration) {
        Path path = paths.existing(value, false);
        try {
            byte[] bytes;
            // Read an immutable bounded snapshot, so later validation and extraction see the same bytes.
            try (SecureDirectoryStream<Path> parent = PathPolicy.openDirectory(path.getParent());
                 var channel = parent.newByteChannel(path.getFileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream input = Channels.newInputStream(channel);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (channel.size() > config.maxBytes()) throw tooLarge();
                copyBounded(input, output, config.maxBytes(), null);
                bytes = output.toByteArray();
            }
            preflightZip(bytes);
            Container container = ContainerBuilder.aContainer().withConfiguration(configuration)
                    .fromStream(new ByteArrayInputStream(bytes)).build();
            if (!Set.of("ASICE", "ASICS", "BDOC", "DDOC").contains(container.getType())) {
                throw new ToolException("UNSUPPORTED_CONTAINER", "Unsupported DigiDoc container type.");
            }
            return container;
        } catch (IOException e) {
            throw new ToolException("CONTAINER_UNAVAILABLE", "Container could not be read.");
        }
    }

    private void preflightZip(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            preflightLegacyXml(bytes);
            return;
        }
        long remaining = config.maxBytes();
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10_000) throw tooLarge();
                if (entry.getSize() > remaining) throw tooLarge();
                remaining -= copyBounded(zip, OutputStream.nullOutputStream(), remaining, null);
                zip.closeEntry();
            }
        }
    }

    /** DDOC's legacy reader can resolve body-less data files from disk; accept embedded bodies only. */
    private static void preflightLegacyXml(byte[] bytes) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        boolean inDataFile = false;
        boolean hasBody = false;
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        throw new ToolException("UNSUPPORTED_CONTAINER", "External XML declarations are not supported.");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT && "DataFile".equals(reader.getLocalName())) {
                        String type = reader.getAttributeValue(null, "ContentType");
                        // The legacy facade strips path components: refuse them instead of exposing aliases.
                        try {
                            PathPolicy.fileName(reader.getAttributeValue(null, "Filename"));
                        } catch (ToolException e) {
                            throw new ToolException("UNSUPPORTED_CONTAINER", "DDOC data file names must be safe single file names.");
                        }
                        if (inDataFile || !("EMBEDDED_BASE64".equals(type) || "EMBEDDED".equals(type))) {
                            throw new ToolException("UNSUPPORTED_CONTAINER", "Only embedded DDOC data files are supported.");
                        }
                        inDataFile = true;
                        hasBody = false;
                    } else if (inDataFile && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                            && !reader.isWhiteSpace()) {
                        hasBody = true;
                    } else if (event == XMLStreamConstants.END_ELEMENT && "DataFile".equals(reader.getLocalName())) {
                        if (!hasBody) throw new ToolException("UNSUPPORTED_CONTAINER", "Empty or external DDOC data files are not supported.");
                        inDataFile = false;
                    }
                }
            } finally { reader.close(); }
        } catch (XMLStreamException e) {
            throw new ToolException("CONTAINER_ERROR", "The container could not be processed.");
        }
    }

    static long copyBounded(InputStream input, OutputStream output, long limit, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[8192];
        long written = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count > limit - written) throw tooLarge();
            output.write(buffer, 0, count);
            if (digest != null) digest.update(buffer, 0, count);
            written += count;
        }
        return written;
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        try {
            return directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
    }

    private static ToolException tooLarge() { return new ToolException("SIZE_LIMIT", "Container or extracted data exceeds the configured byte limit."); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String dataName(DataFile file) { return file.getDocument().getName(); }
    private static String subject(X509Cert cert, X509Cert.SubjectName part) { return cert == null ? null : bounded(cert.getSubjectName(part)); }
    private static String instant(Date date) { return date == null ? null : date.toInstant().toString(); }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static String inspectionProfile(Signature signature) {
        // DSS's LT detection depends on trusted chain completion. Inspection reports the
        // embedded evidence level without downloading trust lists or asserting its validity.
        if (signature instanceof AsicSignature asic) {
            Element element = asic.getOrigin().getDssSignature().getSignatureElement();
            var properties = element.getElementsByTagNameNS("http://uri.etsi.org/01903/v1.3.2#", "UnsignedSignatureProperties");
            if (properties.getLength() > 0) {
                Element unsigned = (Element) properties.item(0);
                if (hasXades(unsigned, "ArchiveTimeStamp")) return "LTA";
                if (hasXades(unsigned, "SignatureTimeStamp") && hasXades(unsigned, "CertificateValues")
                        && hasXades(unsigned, "RevocationValues")) return "LT";
            }
        }
        return profile(signature.getProfile());
    }

    private static boolean hasXades(Element element, String localName) {
        return element.getElementsByTagNameNS("http://uri.etsi.org/01903/v1.3.2#", localName).getLength() > 0
                || element.getElementsByTagNameNS("http://uri.etsi.org/01903/v1.4.1#", localName).getLength() > 0;
    }

    private static String profile(SignatureProfile value) {
        if (value == null) return null;
        return switch (value) { case B_BES, B_EPES -> "B"; case LT_TM -> "LT"; default -> value.name(); };
    }
    private static String bounded(String value) { return value == null ? null : value.substring(0, Math.min(4096, value.length())); }
    private static List<Map<String, Object>> diagnostics(List<DigiDoc4JException> values, boolean warning) {
        if (values == null) return List.of();
        return values.stream().limit(100).map(e -> object("code", e.getClass().getSimpleName() + ":" + e.getErrorCode(),
                "message", warning ? "DigiDoc validation reported a warning." : "DigiDoc validation reported an error.")).toList();
    }
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
    private static Map<String, Object> safely(Supplier<Map<String, Object>> operation) {
        try { return operation.get(); }
        catch (ToolException e) { throw e; }
        catch (RuntimeException e) { throw new ToolException("CONTAINER_ERROR", "The container could not be processed."); }
    }
}
