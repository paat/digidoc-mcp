package ee.paat.digidoc;

import org.digidoc4j.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Immutable, validated startup configuration. */
public record AppConfig(List<Path> roots, long maxBytes, Configuration.Mode mode, Path tslCache,
                        long validationTimeoutSeconds) {
    public static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;

    public static final long DEFAULT_VALIDATION_TIMEOUT_SECONDS = 120;

    public AppConfig(List<Path> roots, long maxBytes, Configuration.Mode mode, Path tslCache) {
        this(roots, maxBytes, mode, tslCache, DEFAULT_VALIDATION_TIMEOUT_SECONDS);
    }

    public AppConfig {
        if (roots == null || roots.isEmpty() || maxBytes <= 0 || mode == null || tslCache == null
                || validationTimeoutSeconds <= 0 || validationTimeoutSeconds > Long.MAX_VALUE / 1_000_000_000L) {
            throw new ToolException("INVALID_CONFIG", "Invalid server configuration.");
        }
        List<Path> canonical = new ArrayList<>();
        try {
            for (Path root : roots) {
                Path real = root.toRealPath();
                if (!Files.isDirectory(real)) throw new IOException();
                canonical.add(real);
            }
        } catch (IOException | RuntimeException e) {
            throw new ToolException("INVALID_CONFIG", "Allowed roots must be existing, accessible directories.");
        }
        roots = List.copyOf(canonical);
        tslCache = tslCache.toAbsolutePath().normalize();
    }

    public static AppConfig fromEnvironment() { return fromEnvironment(System.getenv()); }

    public static AppConfig fromEnvironment(Map<String, String> env) {
        try {
            List<Path> roots = new ArrayList<>();
            String rootSetting = env.get("DIGIDOC_MCP_ROOTS");
            if (rootSetting == null) roots.add(Path.of("."));
            else for (String root : rootSetting.split(Pattern.quote(File.pathSeparator), -1)) {
                if (root.isBlank()) throw new IllegalArgumentException();
                roots.add(Path.of(root));
            }
            long maxBytes = Long.parseLong(env.getOrDefault("DIGIDOC_MCP_MAX_BYTES", Long.toString(DEFAULT_MAX_BYTES)));
            Configuration.Mode mode = switch (env.getOrDefault("DIGIDOC_MCP_MODE", "prod")) {
                case "prod" -> Configuration.Mode.PROD;
                case "test" -> Configuration.Mode.TEST;
                default -> throw new IllegalArgumentException();
            };
            Path cache = Path.of(env.getOrDefault("DIGIDOC_MCP_TSL_CACHE",
                    Path.of(System.getProperty("user.home"), ".cache", "digidoc-mcp", "tsl").toString()));
            long timeout = Long.parseLong(env.getOrDefault("DIGIDOC_MCP_VALIDATION_TIMEOUT_SECONDS",
                    Long.toString(DEFAULT_VALIDATION_TIMEOUT_SECONDS)));
            return new AppConfig(roots, maxBytes, mode, cache, timeout);
        } catch (ToolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolException("INVALID_CONFIG", "Invalid server environment configuration.");
        }
    }
}
