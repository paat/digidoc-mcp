# digidoc-mcp

[![CI](https://github.com/paat/digidoc-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/paat/digidoc-mcp/actions/workflows/ci.yml)

An **unofficial** read-only MCP server for inspecting and validating Estonian DigiDoc containers. This project is **not affiliated with or endorsed by RIA**. It uses [DigiDoc4j](https://github.com/open-eid/digidoc4j) and the [official MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), with MCP JSON-RPC over stdio.

The server opens ASiC-E / BDOC containers, lists signatures, extracts individual data files, and validates signatures. ASiC-S and legacy DDOC are supported where DigiDoc4j supports them. It exposes no signing, container creation, or CDOC tools. Extraction writes a separate file; it does not modify the source container.

## Build and run

Install JDK 21 and Maven, then:

```sh
mvn -B package
java -jar target/digidoc-mcp.jar
```

`target/digidoc-mcp.jar` is the executable shaded jar, including its dependencies. It waits for an MCP client on stdin. Stdout contains only newline-delimited MCP JSON-RPC messages; logs go to stderr. Do not merge stderr into stdout when launching it.

Dependency versions are pinned in `pom.xml`: `org.digidoc4j:digidoc4j:6.2.0` and `io.modelcontextprotocol.sdk:mcp:2.1.0`. These were the latest stable releases in Maven Central on 2026-10-23; the newer MCP `2.2.0-M1` is a milestone and was excluded. Release metadata: [DigiDoc4j](https://repo.maven.apache.org/maven2/org/digidoc4j/digidoc4j/maven-metadata.xml), [MCP SDK](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp/maven-metadata.xml).

For example, create an extraction directory and start the server with two allowed roots:

```sh
mkdir -p /srv/digidoc/input /srv/digidoc/output
DIGIDOC_MCP_ROOTS=/srv/digidoc/input:/srv/digidoc/output \
  java -jar target/digidoc-mcp.jar
```

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `DIGIDOC_MCP_ROOTS` | Current working directory | Allowed directories, separated by `File.pathSeparator` (`:` on Linux, `;` on Windows). Roots must exist and are resolved to canonical paths. |
| `DIGIDOC_MCP_MAX_BYTES` | `104857600` (100 MiB) | Positive byte limit for a container before parsing, expanded ZIP content, and bytes written in one extraction. |
| `DIGIDOC_MCP_MODE` | `prod` | `prod` selects DigiDoc4j `Configuration.Mode.PROD`; `test` selects `TEST` and its test trust lists. Use `test` only for test-signed containers. |
| `DIGIDOC_MCP_VALIDATION_TIMEOUT_SECONDS` | `120` | Positive whole-second deadline for each validation, including waiting for the validation worker, parsing and trust-list downloads. |
| `DIGIDOC_MCP_TSL_CACHE` | `~/.cache/digidoc-mcp/tsl` | Trust-list cache directory; the default `~` means the Java process user's home directory. |

Every tool's `path`, and extraction's `output_dir`, must resolve with `toRealPath()` inside an allowed root. Symlinks and `..` cannot be used to escape the roots. `output_dir` must already exist. Relative paths are relative to the server process's working directory; absolute paths are clearer, especially over SSH. All paths refer to the machine running Java.

An extraction name must exactly match a container data file name and must not be absolute or contain `/`, `\`, `..`, or NUL. Existing targets are refused unless `overwrite` is `true`; symlink targets are refused. Extraction is bounded, and an aborted extraction removes its partial file. ZIP expansion is also checked before DigiDoc4j parses a container, with a maximum of 10,000 entries. File access requires a filesystem provider with Java `SecureDirectoryStream` support (the intended runtime is Linux); unsupported providers return a tool error.

Legacy DDOC support is restricted to nonempty embedded data files with safe single file names. Detached/hash-only files, empty embedded bodies, DTDs, and entity references are refused before the legacy parser runs, preventing implicit reads of referenced local files. Configuration uses DigiDoc4j's bundled defaults plus the environment settings above; working-directory YAML files are not loaded.

The server performs validation locally and does not use SiVa or upload documents to a validation service. Validation can download TSL/LOTL trust lists and certificates through DigiDoc4j's OCSP/AIA mechanisms, depending on the container and cached material. These requests can expose certificate identifiers and contact URLs from certificates; they do not contain the document payload. No signing or timestamp-creation service is invoked. See [DigiDoc4j's network requirements](https://github.com/open-eid/digidoc4j#requirements). First-time validation may take longer while trust lists are cached, and unavailable trust evidence can make a result indeterminate.

Validation runs on one dedicated worker to serialize access to its shared DigiDoc4j configuration and trust lists. The deadline returns the structured tool error `VALIDATION_TIMEOUT` and interrupts the worker; opening, listing and extraction remain available while it runs. If the library ignores interruption, it retains the sole validation worker until it finishes. At most one further validation can wait, with its own deadline; additional requests return `VALIDATION_TIMEOUT` immediately.

Opening, listing, and extraction use an empty trust source to avoid network access during inspection. Only validation loads the configured trust lists. Container metadata is untrusted data, including file names and certificate subject text. Tool results expose this metadata to the MCP client, but never return extracted document contents inline.

## Tools and results

`tools/list` advertises JSON input schemas for exactly four tools. Successful calls return both MCP `structuredContent` and matching JSON text content; failures return a structured tool error with `isError: true` and an `error` object containing `code` and `message`. Times use ISO-8601 UTC strings, and absent signature metadata is `null`.

| Tool | Input object | Result fields |
| --- | --- | --- |
| `open_container` | `{"path":"/srv/digidoc/input/example.asice"}` | `type`, `data_files` (each with `name`, `mime_type`, `size_bytes`), `signature_count` |
| `list_signatures` | `{"path":"/srv/digidoc/input/example.asice"}` | `signatures`, each with `id`, `signer_common_name`, `personal_identification_code`, `claimed_signing_time`, `trusted_signing_time`, `profile`, `signature_method`, `certificate_issuer` |
| `extract_file` | `{"path":"/srv/digidoc/input/example.asice","file_name":"document.txt","output_dir":"/srv/digidoc/output","overwrite":false}` | `path` (absolute written path), `sha256` (hex digest) |
| `validate_container` | `{"path":"/srv/digidoc/input/example.asice"}` | `result` (`VALID`, `INVALID`, or `INDETERMINATE`), `signatures`, container-level `errors` and `warnings` |

`overwrite` is optional and defaults to `false`; all other listed inputs are required. Personal identification codes come from the signer's certificate subject `serialNumber`, when present. Profiles are normalized to `B`, `T`, `LT`, or `LTA` where applicable. Listing reports the embedded evidence profile; validation reports DigiDoc4j's assessed profile, which can differ when evidence is incomplete or untrusted. A trusted signing time reports timestamp/OCSP metadata; use `validate_container` to assess that evidence's trustworthiness.

Each validation signature has `id`, `indication`, `sub_indication`, `errors`, `warnings`, `signer`, `signing_time`, and `profile`. Diagnostics are arrays of `{ "code": "...", "message": "..." }` objects. Codes identify the DigiDoc4j exception category and number; messages are generic so library errors cannot disclose paths or inject container text. Opening a container or listing signatures does not establish its validity.

For example, `open_container` returns a payload shaped like:

```json
{
  "type": "ASICE",
  "data_files": [
    {"name": "document.txt", "mime_type": "text/plain", "size_bytes": 12}
  ],
  "signature_count": 1
}
```

## Claude Desktop

In **Settings → Developer → Edit Config**, merge one of the following entries into `mcpServers` in `claude_desktop_config.json`, then restart Claude Desktop. See the [MCP local-server setup guide](https://modelcontextprotocol.io/docs/develop/connect-local-servers).

Local Java process (Linux paths shown; Windows clients can use the SSH example below for the Linux server):

```json
{
  "mcpServers": {
    "digidoc": {
      "command": "java",
      "args": ["-jar", "/opt/digidoc-mcp/digidoc-mcp.jar"],
      "env": {
        "DIGIDOC_MCP_ROOTS": "/srv/digidoc/input:/srv/digidoc/output",
        "DIGIDOC_MCP_MODE": "prod"
      }
    }
  }
}
```

From Windows or another workstation over SSH to a Linux dev container:

```json
{
  "mcpServers": {
    "digidoc": {
      "command": "ssh",
      "args": [
        "-T", "-o", "BatchMode=yes", "dev@your-host",
        "env", "DIGIDOC_MCP_ROOTS=/srv/digidoc/input:/srv/digidoc/output",
        "DIGIDOC_MCP_MODE=prod",
        "java", "-jar", "/opt/digidoc-mcp/digidoc-mcp.jar"
      ]
    }
  }
}
```

Replace the host and paths with your own. Configure SSH authentication and the host key beforehand so launch needs no interactive prompt. `-T` avoids a pseudo-terminal. The remote shell must not print banners or startup messages to stdout. Set server environment variables in the remote command as above; a local `env` block is not automatically forwarded by SSH. These examples use remote paths without spaces; quote the remote command appropriately if your paths contain shell metacharacters.

## Claude Code

Register a local server using [Claude Code's stdio configuration](https://code.claude.com/docs/en/mcp#option-3-add-a-local-stdio-server):

```sh
claude mcp add \
  --env DIGIDOC_MCP_ROOTS=/srv/digidoc/input:/srv/digidoc/output \
  --env DIGIDOC_MCP_MODE=prod \
  --transport stdio digidoc -- \
  java -jar /opt/digidoc-mcp/digidoc-mcp.jar
```

Or register Java on a remote Linux host, still using stdio:

```sh
claude mcp add --transport stdio digidoc -- \
  ssh -T -o BatchMode=yes dev@your-host \
  env DIGIDOC_MCP_ROOTS=/srv/digidoc/input:/srv/digidoc/output \
  DIGIDOC_MCP_MODE=prod \
  java -jar /opt/digidoc-mcp/digidoc-mcp.jar
```

Use one alternative for the `digidoc` name. Run `claude mcp list` or `/mcp` in Claude Code to inspect the connection. The SSH authentication and stdout requirements above also apply here.

## Tests

```sh
mvn -B verify                         # Includes network-dependent validation
mvn -B verify -DskipNetworkTests       # Excludes tests tagged "network"
```

The default suite validates test-signed fixtures in TEST mode, detects tampered data, inspects multiple files and signatures, checks confinement and extraction limits, and launches the packaged jar for an MCP stdio smoke test. The smoke test initializes the server, checks the four advertised tools, calls `open_container`, and rejects any non-JSON stdout line. Network-dependent tests need reachable trust-list services; skipping them does not verify live trust validation. Maven still needs cached dependencies or network access to resolve them.

Fixture sources and attribution are recorded in [src/test/resources/README.md](src/test/resources/README.md).

## License

This project is licensed under [Apache-2.0](LICENSE). The shaded jar bundles digidoc4j and the EU DSS libraries under LGPL-2.1; their licenses apply to those components. See [NOTICE](NOTICE). Test fixtures retain their upstream LGPL-2.1 license.
