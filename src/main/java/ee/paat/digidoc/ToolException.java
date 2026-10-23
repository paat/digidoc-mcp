package ee.paat.digidoc;

/** A safe, stable failure that may be returned to an MCP client. */
public final class ToolException extends RuntimeException {
    private final String code;

    public ToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
