package ee.paat.digidoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathPolicyTest {
    @TempDir Path temporary;

    @Test void ancestorReplacedWithOutsideSymlinkAfterCanonicalizationIsRefused() throws Exception {
        Path allowed = Files.createDirectories(temporary.resolve("allowed/ancestor/output"));
        Path outside = Files.createDirectories(temporary.resolve("outside/output"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "outside contents");
        var policy = new PathPolicy(List.of(temporary.resolve("allowed").toRealPath()));
        Path canonical = policy.existing(allowed.toString(), true);
        Path ancestor = allowed.getParent();
        Files.move(ancestor, ancestor.resolveSibling("original-ancestor"));
        Files.createSymbolicLink(ancestor, outside.getParent());

        assertThrows(IOException.class, () -> {
            try (var directory = PathPolicy.openDirectory(canonical)) {
                fail("A changed ancestor must not be traversed through its replacement symlink");
            }
        });
        assertEquals("outside contents", Files.readString(sentinel));
    }

    @Test void symlinkResolvingInsideRootCanonicalizesToTheRealDirectory() throws Exception {
        Path allowed = Files.createDirectory(temporary.resolve("allowed"));
        Path target = Files.createDirectory(allowed.resolve("real-output"));
        Path link = Files.createSymbolicLink(allowed.resolve("linked-output"), target);
        Path canonical = new PathPolicy(List.of(allowed.toRealPath())).existing(link.toString(), true);
        assertEquals(target.toRealPath(), canonical);
        try (var directory = PathPolicy.openDirectory(canonical)) {
            assertNotNull(directory);
        }
    }
}
