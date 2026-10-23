package ee.paat.digidoc;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LoggingTest {
    @Test void containerTextCannotForgeStderrLines() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(LoggingTest.class.getResource("/logback.xml"));
            var logger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            @SuppressWarnings("unchecked")
            var appender = (ConsoleAppender<ILoggingEvent>) logger.getAppender("STDERR");
            assertEquals("System.err", appender.getTarget());
            var output = new ByteArrayOutputStream();
            appender.setOutputStream(output);

            logger.warn("container\r\nFORGED\rrecord\nend", new RuntimeException("SECRET"));

            String line = output.toString(StandardCharsets.UTF_8);
            assertEquals(1, line.lines().count(), line);
            assertFalse(line.contains("\r"));
            assertTrue(line.contains("container  FORGED record end"), line);
            assertFalse(line.contains("SECRET"));
            output.reset();
            logger.warn("x".repeat(4096) + "\r\nFORGED");
            assertEquals(1, output.toString(StandardCharsets.UTF_8).lines().count());
            assertTrue(output.size() < 2200, "The message must remain bounded");
        } finally { context.stop(); }
    }
}
