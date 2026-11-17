package ee.paat.digidoc;

import java.nio.file.Path;

/**
 * Writes the Smart-ID registry settings, starts DigiDoc4 and drives its UI.
 * Implementations must not put {@code personalCode} on a process command line or in a log.
 */
@FunctionalInterface
interface SigningDriver {
    void drive(Path executable, Path source, String personalCode);
}
