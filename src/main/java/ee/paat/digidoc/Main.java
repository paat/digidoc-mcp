package ee.paat.digidoc;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/** The original stdout stream belongs exclusively to the MCP transport. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        var protocolOutput = System.out;
        // Also catches libraries that print directly instead of using a logger.
        System.setOut(System.err);
        try {
            var mapper = new JacksonMcpJsonMapper(JsonMapper.shared());
            var service = new ContainerService(AppConfig.fromEnvironment());
            var transport = new StdioServerTransportProvider(mapper, System.in, protocolOutput);
            var server = McpServer.sync(transport)
                    .serverInfo("digidoc-mcp", "0.1.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(new Tools(service, mapper).specifications())
                    .build();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "digidoc-shutdown"));
            // The SDK owns the input/output threads and terminates them on stdin EOF.
        } catch (Exception exception) {
            System.err.println("DigiDoc MCP could not start. Check configuration and filesystem permissions.");
            System.exit(1);
        }
    }
}
