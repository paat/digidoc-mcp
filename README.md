# digidoc-mcp

[![CI](https://github.com/paat/digidoc-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/paat/digidoc-mcp/actions/workflows/ci.yml)

An **unofficial** MCP server for inspecting and validating Estonian DigiDoc containers. It is read-only unless Smart-ID signing is explicitly enabled. This project is **not affiliated with or endorsed by RIA**. It uses [DigiDoc4j](https://github.com/open-eid/digidoc4j) and the [official MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), with MCP JSON-RPC over stdio.

The server opens ASiC-E / BDOC containers, lists signatures, extracts individual data files, and validates signatures. ASiC-S and legacy DDOC are supported where DigiDoc4j supports them. It exposes no container creation or CDOC tools. Signing is off unless `DIGIDOC_MCP_SIGNING=smartid` on Windows. Extraction writes a separate file; it does not modify the source container.

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
| `DIGIDOC_MCP_MAX_BYTES` | `104857600` (100 MiB) | Positive byte limit for a container before parsing, total expansion across nested ZIP/DDOC content, and bytes written in one extraction. |
| `DIGIDOC_MCP_MODE` | `prod` | `prod` selects DigiDoc4j `Configuration.Mode.PROD`; `test` selects `TEST` and its test trust lists. Use `test` only for test-signed containers. |
| `DIGIDOC_MCP_VALIDATION_TIMEOUT_SECONDS` | `120` | Positive whole-second deadline for each validation, including waiting for the validation worker, parsing and trust-list downloads. |
| `DIGIDOC_MCP_TSL_CACHE` | `~/.cache/digidoc-mcp/tsl` | Trust-list cache directory; the default `~` means the Java process user's home directory. |
| `DIGIDOC_MCP_SIGNING` | unset | `smartid` enables Windows Smart-ID signing. Unset or empty keeps the server read-only. Any other value is `INVALID_CONFIG`. |
| `DIGIDOC_MCP_SMARTID_CODE` | required when signing is enabled | 11-digit Estonian personal code (birth date and check digit). Never logged and never included in an error. |
| `DIGIDOC_MCP_DIGIDOC4` | discover `qdigidoc4.exe` | Optional path to DigiDoc4. See Signing. |
| `DIGIDOC_MCP_SIGN_TIMEOUT_SECONDS` | `180` | Positive whole seconds to wait for the signed container. |

Every tool's `path`, and extraction's `output_dir`, must resolve with `toRealPath()` inside an allowed root. Symlinks and `..` cannot be used to escape the roots. `output_dir` must already exist. Relative paths are relative to the server process's working directory; absolute paths are clearer, especially over SSH. All paths refer to the machine running Java.

An extraction name must exactly match a container data file name and must not be absolute or contain `/`, `\`, `..`, or NUL. Existing targets are refused unless `overwrite` is `true`; symlink targets are refused. Extraction is bounded, and an aborted extraction removes its partial file. Before DigiDoc4j parses a container, preflight checks the ZIP central directory against the complete local-entry chain and counts actual inflated bytes. Nested ZIP containers and decoded DDOC bodies share one byte budget and a 10,000-entry cap, with at most two nested container levels. Ambiguous layouts, concatenated/trailing archives, split ZIPs and ZIP64 are refused. Opening, listing, extraction, and path-based validation require a filesystem provider with Java `SecureDirectoryStream` support (the intended runtime for those tools is Linux); unsupported providers return a tool error. Smart-ID signing reads the new container with a direct bounded snapshot, so that read does not need `SecureDirectoryStream`.

Legacy DDOC support is restricted to nonempty embedded data files with safe single file names. Detached/hash-only files, empty embedded bodies, DTDs, and entity references are refused before the legacy parser runs, preventing implicit reads of referenced local files. Nested DDOC checks also cover the legacy reader's acceptance of junk before the XML. ASiC-S XML payloads cannot contain DTDs because its legacy-reader probe can process declarations before identifying a DDOC. Configuration uses DigiDoc4j's bundled defaults plus the environment settings above; working-directory YAML files are not loaded.

The server performs validation locally and does not use SiVa or upload documents to a validation service. Validation can download TSL/LOTL trust lists and certificates through DigiDoc4j's OCSP/AIA mechanisms, depending on the container and cached material. These requests can expose certificate identifiers and contact URLs from certificates; they do not contain the document payload. No signing or timestamp-creation service is invoked. See [DigiDoc4j's network requirements](https://github.com/open-eid/digidoc4j#requirements). First-time validation may take longer while trust lists are cached, and unavailable trust evidence can make a result indeterminate.

Validation runs on one dedicated worker to serialize access to its shared DigiDoc4j configuration and trust lists. The deadline returns the structured tool error `VALIDATION_TIMEOUT` and interrupts the worker; opening, listing and extraction remain available while it runs. If the library ignores interruption, it retains the sole validation worker until it finishes. At most one further validation can wait, with its own deadline; additional requests return `VALIDATION_TIMEOUT` immediately.

Opening, listing, and extraction use an empty trust source to avoid network access during inspection. Only validation loads the configured trust lists. Container metadata is untrusted data, including file names and certificate subject text. Tool results expose this metadata to the MCP client, but never return extracted document contents inline.

## Tools and results

`tools/list` advertises JSON input schemas for exactly four tools when signing is off. `DIGIDOC_MCP_SIGNING=smartid` also advertises `sign_with_smartid`. Successful calls return both MCP `structuredContent` and matching JSON text content; failures return a structured tool error with `isError: true` and an `error` object containing `code` and `message`. Times use ISO-8601 UTC strings, and absent signature metadata is `null`.

| Tool | Input object | Result fields |
| --- | --- | --- |
| `open_container` | `{"path":"/srv/digidoc/input/example.asice"}` | `type`, `data_files` (each with `name`, `mime_type`, `size_bytes`), `signature_count` |
| `list_signatures` | `{"path":"/srv/digidoc/input/example.asice"}` | `signatures`, each with `id`, `signer_common_name`, `personal_identification_code`, `claimed_signing_time`, `trusted_signing_time`, `profile`, `signature_method`, `certificate_issuer` |
| `extract_file` | `{"path":"/srv/digidoc/input/example.asice","file_name":"document.txt","output_dir":"/srv/digidoc/output","overwrite":false}` | `path` (absolute written path), `sha256` (hex digest) |
| `validate_container` | `{"path":"/srv/digidoc/input/example.asice"}` | `result` (`VALID`, `INVALID`, or `INDETERMINATE`), `signatures`, container-level `errors` and `warnings` |
| `sign_with_smartid` | `{"path":"/srv/digidoc/input/contract.pdf"}` (only when signing is enabled) | `path`, `sha256`, and the same validation summary (`result`, `signatures`, `errors`, `warnings`) |

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

## Signing (opt-in, Windows)

Signing is off unless `DIGIDOC_MCP_SIGNING=smartid`. The server then advertises `sign_with_smartid`, which takes `{"path": "<file inside an allowed root>"}` and no other arguments. It signs that one file by driving the person's installed [DigiDoc4](https://github.com/open-eid/DigiDoc4-Client) and RIA Smart-ID, the same way as signing by hand. DigiDoc4 must be installed on the Windows PC where this server runs. The server does not call Smart-ID itself and does not see the PIN. **PIN2 in the Smart-ID app is the user's consent.** Enabling signing on any other operating system fails startup with `INVALID_CONFIG`. One signature runs at a time.

Before it writes settings or starts DigiDoc4, the server returns `DIGIDOC4_RUNNING` when any process command already ends with `qdigidoc4.exe` (case-insensitive). Otherwise, before launch, the server writes `HKCU\Software\RIA\qdigidoc4` (DigiDoc4's `QSettings` organization `RIA`, application `qdigidoc4`):

| Name | Value |
| --- | --- |
| `SmartID` | `DIGIDOC_MCP_SMARTID_CODE` |
| `SmartIDCountry` | `EE` |
| `SmartIDSettings` | `true` |

Those values are written as `REG_SZ`, which is how Qt 6 stores `QString` and `bool` (`true` / `false`). The personal code is passed to that helper on standard input. It is never placed on a process command line and never written to the log or to an error message. The server then starts `qdigidoc4.exe -sign <file>` and passes that process id to a bundled PowerShell script. The script automates only that process: it uses `System.Windows.Automation` to click the container page's add-signature control, select the Smart-ID tab (`tabs`, index 2: ID-card, Mobile-ID, Smart-ID), and click `sign`. It looks up controls by AutomationId (and, for the add-signature button, class name). It does not use screen coordinates or translated labels, and it does not search for another `qdigidoc4` process. If the launched process exits early, including a single-instance handoff, the tool returns `UI_AUTOMATION_FAILED`. DigiDoc4's role/address dialog must stay off (`RoleAddressInfo` is false by default) or that script cannot reach the signing dialog.

The new container is `<directory>/<complete base name>.asice`, the path `FileDialog::createNewFileName` uses when the file is absent and the directory is writable (DigiDoc4-Client `de77bcf`, Qt `QFileInfo::completeBaseName()` plus `.asice`). Examples: `contract.pdf` → `contract.asice`, `notes.tar.gz` → `notes.tar.asice`, `README` → `README.asice`. A leading dot is not an extension (`.gitignore` → `.gitignore.asice`). If that file already exists, the tool returns `TARGET_EXISTS` and does not overwrite it or open DigiDoc4's Save dialog. The input file itself is not modified.

The tool waits until the `.asice` exists, its size and modification time stay unchanged for a short interval, and it contains at least one signature. DigiDoc4 can create an unsigned container before PIN2, and that file is not treated as finished. The wait is bounded by `DIGIDOC_MCP_SIGN_TIMEOUT_SECONDS` (default 180). It then reads one bounded snapshot of the confined target (`NOFOLLOW` direct read, size at most `DIGIDOC_MCP_MAX_BYTES`) and validates those bytes with the same parser, worker, and deadline as `validate_container`. Success requires indication `VALID` and a signature whose `personal_identification_code` equals the configured code or `PNOEE-` followed by that code (ETSI EN 319 412-1). Other country prefixes, such as `PNOLV-`, do not match. The result is the new container's `path`, `sha256` of those same bytes, and that validation summary.

| Code | When |
| --- | --- |
| `SIGNING_DISABLED` | The tool was called while signing is off. |
| `TARGET_EXISTS` | The `.asice` path already exists. Nothing is overwritten. |
| `DIGIDOC4_NOT_FOUND` | `qdigidoc4.exe` is missing. An explicit `DIGIDOC_MCP_DIGIDOC4` path is not replaced with the default location. |
| `DIGIDOC4_RUNNING` | A process whose command ends with `qdigidoc4.exe` is already running. Close DigiDoc4 and try again. |
| `UI_AUTOMATION_FAILED` | The UI script could not find a control, it exited non-zero, or the launched DigiDoc4 process exited early. |
| `SIGN_TIMEOUT` | The container did not finish before the deadline. An incomplete `.asice` created during that call is deleted. |
| `SIGNATURE_NOT_VALID` | The container finished but is not `VALID`, or no signature matches the configured code. The file is left in place. |

`PATH_NOT_ALLOWED` and `INVALID_PATH` are the same confinement errors as the other tools. Configuration errors use `INVALID_CONFIG` and do not include the personal code.

These points are **unverified until the Windows spike** (no Windows DigiDoc4 host was available when this was written):

- Default install path `%ProgramFiles%\RIA\DigiDoc4\qdigidoc4.exe`, then `%ProgramFiles(x86)%\RIA\DigiDoc4\qdigidoc4.exe`.
- Qt exposes widget object names as AutomationId (`tabs`, `sign`).
- The add-signature control is `MainAction`. That button has no object name in DigiDoc4-Client `de77bcf`, so the script tries AutomationId `MainAction` and then ClassName `MainAction`.
- A `QTabBar` tab can be selected with `SelectionItemPattern`, and Smart-ID is child index 2.
- An already running DigiDoc4 is refused with `DIGIDOC4_RUNNING`. The UI script is bound to the process id this server started. If that process exits early, signing returns `UI_AUTOMATION_FAILED`.
- DigiDoc4 honors the registry values with the `REG_SZ` types above.
- PDF and other inputs are wrapped silently when the predicted `.asice` does not exist. If it exists, or the directory is not writable, DigiDoc4 would show a Save dialog; this server refuses an existing target instead.

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

The default suite validates test-signed fixtures in TEST mode, detects tampered data, inspects multiple files and signatures, checks confinement and extraction limits, and launches the packaged jar for an MCP stdio smoke test. Signing tests use a fake DigiDoc4 driver on Linux and are not network-tagged. The smoke test still expects exactly four tools, because it does not set `DIGIDOC_MCP_SIGNING`. The smoke test initializes the server, checks the four advertised tools, calls `open_container`, and rejects any non-JSON stdout line. Network-dependent tests need reachable trust-list services; skipping them does not verify live trust validation. Maven still needs cached dependencies or network access to resolve them.

Fixture sources and attribution are recorded in [src/test/resources/README.md](src/test/resources/README.md).

## License

This project is licensed under [Apache-2.0](LICENSE). The shaded jar bundles digidoc4j and the EU DSS libraries under LGPL-2.1; their licenses apply to those components. See [NOTICE](NOTICE). Test fixtures retain their upstream LGPL-2.1 license.
