package ee.paat.digidoc;

import java.nio.file.Path;

/**
 * Writes the Smart-ID registry settings, starts DigiDoc4 and drives its UI.
 * Implementations must not put {@code personalCode} on a process command line or in a log.
 * {@link #close()} ends the session started by {@link #drive}.
 */
@FunctionalInterface
interface SigningDriver {
    void drive(Path executable, Path source, String personalCode);

    /** Ends the session from {@link #drive}, including when driving fails. Idempotent. */
    default void close() {}
}
