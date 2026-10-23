package ee.paat.digidoc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.List;

final class PathPolicy {
    private final List<Path> roots;

    PathPolicy(List<Path> roots) { this.roots = roots; }

    /** Open every canonical path component without following links, closing ancestor handles. */
    static SecureDirectoryStream<Path> openDirectory(Path canonical) throws IOException {
        DirectoryStream<Path> opened = Files.newDirectoryStream(canonical.getRoot());
        if (!(opened instanceof SecureDirectoryStream<Path> current)) {
            opened.close();
            throw new ToolException("UNSUPPORTED_FILESYSTEM", "Secure directory operations are required.");
        }
        try {
            for (Path component : canonical) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException e) {
            current.close();
            throw e;
        }
    }

    Path existing(String value, boolean directory) {
        if (value == null || value.isBlank()) throw new ToolException("INVALID_ARGUMENT", "A non-empty path is required.");
        try {
            Path real = Path.of(value).toRealPath();
            if (roots.stream().noneMatch(real::startsWith)) {
                throw new ToolException("PATH_NOT_ALLOWED", "Path is outside the allowed roots.");
            }
            if (directory ? !Files.isDirectory(real) : !Files.isRegularFile(real)) {
                throw new ToolException("INVALID_PATH", directory ? "Output directory must exist." : "Container must be a regular file.");
            }
            return real;
        } catch (IOException | InvalidPathException | SecurityException e) {
            throw new ToolException("INVALID_PATH", "Path is unavailable or inaccessible.");
        }
    }

    static void fileName(String value) {
        if (value == null || value.isEmpty() || value.contains("/") || value.contains("\\")
                || value.contains("..") || value.indexOf('\0') >= 0 || value.equals(".")
                || value.matches("^[A-Za-z]:.*")) {
            throw new ToolException("INVALID_FILE_NAME", "File name must be a single safe container entry name.");
        }
        try {
            if (Path.of(value).isAbsolute()) throw new InvalidPathException(value, "absolute");
        } catch (InvalidPathException e) {
            throw new ToolException("INVALID_FILE_NAME", "File name must be a single safe container entry name.");
        }
    }
}
