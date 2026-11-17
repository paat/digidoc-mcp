package ee.paat.digidoc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Windows DigiDoc4 launcher. The personal code is written to the settings
 * helper's stdin and is never placed on a command line or logged.
 * Registry value types are REG_SZ, matching Qt 6 QSettings. Unverified until the Windows spike.
 */
final class WindowsSmartIdDriver implements SigningDriver {
    private static final Logger LOG = LoggerFactory.getLogger(WindowsSmartIdDriver.class);
    private static final long SETTINGS_WAIT_SECONDS = 30;
    private static final long UI_WAIT_SECONDS = 45;

    @Override
    public void drive(Path executable, Path source, String personalCode) {
        try {
            refuseIfDigidoc4Running();
            int settings = runScript("smartid-settings.ps1", false, personalCode.getBytes(StandardCharsets.UTF_8),
                    SETTINGS_WAIT_SECONDS);
            if (settings != 0) {
                LOG.warn("Smart-ID registry update failed with status {}", settings);
                throw new ToolException("OPERATION_FAILED", "Smart-ID settings could not be saved.");
            }
            Process app = new ProcessBuilder(launchCommand(executable, source))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .start();
            app.getOutputStream().close();
            requireAlive(app);
            int ui = runScript("smartid-ui.ps1", true, null, UI_WAIT_SECONDS, Long.toString(app.pid()));
            if (ui != 0) {
                LOG.warn("DigiDoc4 UI automation failed with status {}", ui);
                throw new ToolException("UI_AUTOMATION_FAILED", "DigiDoc4 signing controls could not be used.");
            }
            requireAlive(app);
        } catch (ToolException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolException("OPERATION_FAILED", "Smart-ID signing could not be started.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("SIGN_TIMEOUT", "Signing did not finish before the deadline.");
        }
    }

    /** Refuse when any visible process command ends with {@code qdigidoc4.exe}, ignoring case. */
    static void refuseIfDigidoc4Running() {
        refuseIfDigidoc4Running(ProcessHandle.allProcesses().flatMap(WindowsSmartIdDriver::commandOf));
    }

    static void refuseIfDigidoc4Running(Stream<String> commands) {
        if (commands.anyMatch(WindowsSmartIdDriver::qdigidoc4Command)) {
            throw new ToolException("DIGIDOC4_RUNNING", "Close DigiDoc4 and try again.");
        }
    }

    static boolean qdigidoc4Command(String command) {
        String suffix = "qdigidoc4.exe";
        return command != null && command.length() >= suffix.length()
                && command.regionMatches(true, command.length() - suffix.length(), suffix, 0, suffix.length());
    }

    /** The launched process exited before UI automation finished (single-instance handoff). */
    static void requireAlive(Process process) {
        if (process == null || !process.isAlive()) {
            throw new ToolException("UI_AUTOMATION_FAILED", "DigiDoc4 signing controls could not be used.");
        }
    }

    private static Stream<String> commandOf(ProcessHandle process) {
        return process.info().command().stream();
    }

    /** {@code qdigidoc4.exe -sign <file>}. The personal code is not an argument. */
    static List<String> launchCommand(Path executable, Path source) {
        return List.of(executable.toString(), "-sign", source.toString());
    }

    static List<String> powershell(Path script, boolean sta, String... arguments) {
        var command = new ArrayList<String>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        if (sta) command.add("-STA");
        command.add("-File");
        command.add(script.toString());
        if (arguments != null) command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static int runScript(String name, boolean sta, byte[] stdin, long timeoutSeconds, String... arguments)
            throws IOException, InterruptedException {
        Path script = Files.createTempFile("digidoc-mcp-", ".ps1");
        try (InputStream resource = WindowsSmartIdDriver.class.getResourceAsStream("/ee/paat/digidoc/" + name)) {
            if (resource == null) throw new ToolException("OPERATION_FAILED", "Signing helper is unavailable.");
            Files.copy(resource, script, StandardCopyOption.REPLACE_EXISTING);
            var builder = new ProcessBuilder(powershell(script, sta, arguments))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.PIPE);
            Process process = builder.start();
            try {
                try (OutputStream output = process.getOutputStream()) {
                    if (stdin != null) output.write(stdin);
                }
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return -1;
                return process.exitValue();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
