package ee.paat.digidoc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One Smart-ID signature at a time. DigiDoc4 creates {@code <completeBaseName>.asice}
 * beside the source ({@code FileDialog::createNewFileName} in DigiDoc4-Client de77bcf,
 * Qt {@code QFileInfo::completeBaseName} plus {@code .asice}). An existing target is
 * refused; this server does not open DigiDoc4's Save dialog and does not overwrite.
 */
final class SmartIdSigning {
    static final long DEFAULT_POLL_MILLIS = 500;
    /** Unverified until the Windows spike. Evidence-based default from the issue. */
    private static final String[] INSTALL_RELATIVE = {"RIA", "DigiDoc4", "qdigidoc4.exe"};

    private final AppConfig config;
    private final ContainerService containers;
    private final SigningDriver driver;
    private final WaitClock clock;
    private final Map<String, String> environment;
    private final long pollMillis;
    private final String personalCode;
    private final long timeoutSeconds;
    private final Object signLock = new Object();

    SmartIdSigning(AppConfig config, ContainerService containers, SigningDriver driver) {
        this(config, containers, driver, SYSTEM_CLOCK, System.getenv(), DEFAULT_POLL_MILLIS);
    }

    SmartIdSigning(AppConfig config, ContainerService containers, SigningDriver driver, WaitClock clock,
                   Map<String, String> environment, long pollMillis) {
        if (config.signing() == null || clock == null || environment == null || pollMillis <= 0) {
            throw new IllegalArgumentException();
        }
        this.config = config;
        this.containers = containers;
        this.driver = driver;
        this.clock = clock;
        this.environment = environment;
        this.pollMillis = pollMillis;
        this.personalCode = config.signing().personalCode();
        this.timeoutSeconds = config.signing().timeoutSeconds();
    }

    interface WaitClock {
        long nanoTime();
        void sleep(long millis) throws InterruptedException;
    }

    static final WaitClock SYSTEM_CLOCK = new WaitClock() {
        @Override public long nanoTime() { return System.nanoTime(); }
        @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
    };

    /**
     * Target path DigiDoc4 uses when the file does not already exist.
     * {@code contract.pdf} → {@code contract.asice}, {@code archive.tar.gz} → {@code archive.tar.asice},
     * {@code README} → {@code README.asice}. A leading dot is not an extension ({@code .gitignore} stays).
     */
    static Path containerTarget(Path source) {
        Path parent = source.getParent();
        if (parent == null) throw new ToolException("INVALID_PATH", "Signing target is unavailable.");
        String base = completeBaseName(source.getFileName().toString());
        String name = base + ".asice";
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0 || name.equals(".asice")) {
            throw new ToolException("INVALID_PATH", "Signing target name is not a single file name.");
        }
        Path target = parent.resolve(name);
        if (!parent.equals(target.getParent())) {
            throw new ToolException("PATH_NOT_ALLOWED", "Signing target is outside the allowed roots.");
        }
        return target;
    }

    /** Qt {@code QFileInfo::completeBaseName}: strip the last dot unless it is the first character. */
    static String completeBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return fileName;
        return fileName.substring(0, dot);
    }

    /**
     * First existing {@code %ProgramFiles%\RIA\DigiDoc4\qdigidoc4.exe}, then
     * {@code %ProgramFiles(x86)%\...}. Unverified until the Windows spike.
     */
    static Path installedExecutable(Map<String, String> env) {
        Path programFiles = candidate(env.get("ProgramFiles"));
        Path programFilesX86 = candidate(env.get("ProgramFiles(x86)"));
        if (programFiles != null && Files.isRegularFile(programFiles)) return programFiles;
        if (programFilesX86 != null && Files.isRegularFile(programFilesX86)) return programFilesX86;
        return null;
    }

    private static Path candidate(String root) {
        if (root == null || root.isBlank()) return null;
        return Path.of(root, INSTALL_RELATIVE);
    }

    Map<String, Object> sign(String path) {
        synchronized (signLock) {
            try {
                return signOne(path);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("SIGN_TIMEOUT", "Signing did not finish before the deadline.");
            }
        }
    }

    private Map<String, Object> signOne(String path) throws InterruptedException {
        PathPolicy paths = new PathPolicy(config.roots());
        Path source = paths.existing(path, false);
        Path target = containerTarget(source);
        if (!inside(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!inside(target)) throw new ToolException("PATH_NOT_ALLOWED", "Signing target is outside the allowed roots.");
            throw new ToolException("TARGET_EXISTS", "Signing target already exists.");
        }
        Path executable = resolveExecutable();
        driver.drive(executable, source, personalCode);
        long deadline = clock.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Snapshot previous = null;
        boolean appeared = false;
        while (clock.nanoTime() < deadline) {
            Snapshot current = snapshot(target);
            if (current != null) appeared = true;
            if (current != null && previous != null && current.equals(previous) && current.size > 0) {
                Path real = null;
                byte[] bytes = null;
                try {
                    real = realInside(target);
                    bytes = readSnapshot(real);
                } catch (ToolException e) {
                    bytes = null;
                }
                if (bytes != null && hasSignatures(bytes)) return finish(real, bytes);
            }
            previous = current;
            clock.sleep(pollMillis);
        }
        if (appeared && !target.equals(source) && !snapshotHasSignature(target)) deleteIncomplete(target);
        throw new ToolException("SIGN_TIMEOUT", "Signing did not finish before the deadline.");
    }

    private Path resolveExecutable() {
        Path configured = config.signing().digidoc4();
        if (configured != null) {
            if (!Files.isRegularFile(configured)) {
                throw new ToolException("DIGIDOC4_NOT_FOUND", "DigiDoc4 executable was not found.");
            }
            return configured;
        }
        Path discovered = installedExecutable(environment);
        if (discovered == null) throw new ToolException("DIGIDOC4_NOT_FOUND", "DigiDoc4 executable was not found.");
        return discovered;
    }

    private Map<String, Object> finish(Path real, byte[] bytes) {
        Map<String, Object> validation = containers.validateContainer(bytes);
        if (!"VALID".equals(validation.get("result")) || !codeMatches(bytes)) {
            throw new ToolException("SIGNATURE_NOT_VALID", "The new signature is not valid for the configured signer.");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("path", real.toString());
        result.put("sha256", sha256(bytes));
        result.put("result", validation.get("result"));
        result.put("signatures", validation.get("signatures"));
        result.put("errors", validation.get("errors"));
        result.put("warnings", validation.get("warnings"));
        return result;
    }

    private boolean codeMatches(byte[] bytes) {
        Object listed = containers.listSignatures(bytes).get("signatures");
        if (!(listed instanceof List<?> signatures)) return false;
        for (Object row : signatures) {
            if (row instanceof Map<?, ?> signature && serialMatches(signature.get("personal_identification_code"), personalCode)) {
                return true;
            }
        }
        return false;
    }

    /** Estonian Smart-ID and ID-card serialNumber is the code or {@code PNOEE-} plus the code (ETSI EN 319 412-1). */
    static boolean serialMatches(Object serial, String code) {
        return serial instanceof String value && (value.equals(code) || value.equals("PNOEE-" + code));
    }

    /** Timeout keeps a target whose snapshot parses with at least one signature. */
    private boolean snapshotHasSignature(Path target) {
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
            return hasSignatures(readSnapshot(realInside(target)));
        } catch (ToolException e) {
            return false;
        }
    }

    private boolean hasSignatures(byte[] bytes) {
        try {
            Object listed = containers.listSignatures(bytes).get("signatures");
            return listed instanceof List<?> signatures && !signatures.isEmpty();
        } catch (ToolException e) {
            return false;
        }
    }

    /** One bounded NOFOLLOW snapshot of the confined target. Callers must already have passed {@link #realInside}. */
    private byte[] readSnapshot(Path real) {
        try (var channel = Files.newByteChannel(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream input = Channels.newInputStream(channel);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (channel.size() > config.maxBytes()) {
                throw new ToolException("SIZE_LIMIT", "Container or extracted data exceeds the configured byte limit.");
            }
            ContainerService.copyBounded(input, output, config.maxBytes(), null);
            return output.toByteArray();
        } catch (IOException e) {
            throw new ToolException("CONTAINER_UNAVAILABLE", "Signed container could not be read.");
        }
    }

    private boolean inside(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return config.roots().stream().anyMatch(absolute::startsWith);
    }

    private Path realInside(Path target) {
        try {
            Path real = target.toRealPath();
            if (config.roots().stream().noneMatch(real::startsWith) || !Files.isRegularFile(real)) {
                throw new ToolException("PATH_NOT_ALLOWED", "Signing target is outside the allowed roots.");
            }
            return real;
        } catch (IOException e) {
            throw new ToolException("INVALID_PATH", "Signing target is unavailable.");
        }
    }

    private static void deleteIncomplete(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new ToolException("SIGN_TIMEOUT", "Signing did not finish before the deadline.");
        }
    }

    private Snapshot snapshot(Path target) {
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) return new Snapshot(-1, attributes.lastModifiedTime());
            return new Snapshot(attributes.size(), attributes.lastModifiedTime());
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record Snapshot(long size, FileTime modified) {}
}
