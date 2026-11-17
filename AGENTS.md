# digidoc-mcp

Unofficial read-only MCP server (Java 21, stdio) for inspecting and validating Estonian DigiDoc containers (ASiC-E/BDOC, ASiC-S, legacy DDOC) with DigiDoc4j and the official MCP Java SDK. Public repo (Apache-2.0). README.md is the user and API documentation; keep it in sync when tools, config variables or limits change.

## Commands

- Build the shaded jar: `mvn -B package` (produces `target/digidoc-mcp.jar`).
- Full gate (what CI runs): `mvn -B verify`. Without the network-tagged tests: `mvn -B verify -DskipNetworkTests`; skipping them does not verify live trust validation.
- One test class: `mvn -B test -Dtest=PathPolicyTest`. The stdio smoke test `StdioSmokeIT` runs in failsafe during `verify` against the packaged jar.
- Layout: `src/main/java/ee/paat/digidoc/` (`Main`, `Tools`, `ContainerService`, `ContainerPreflight`, `PathPolicy`, `AppConfig`, `ToolException`, `SmartIdSigning`, `WindowsSmartIdDriver`); fixture sources are listed in `src/test/resources/README.md`.

## Invariants

- Read-only by default: no container creation or CDOC tools. Exactly four tools (`open_container`, `list_signatures`, `extract_file`, `validate_container`) unless `DIGIDOC_MCP_SIGNING=smartid`, which adds Windows-only `sign_with_smartid`. The smoke test asserts the four-tool list with signing unset.
- The Smart-ID personal code is never logged, never placed on a process command line, and never included in a tool or configuration error.
- Stdout carries only MCP JSON-RPC lines; logs go to stderr. Never print to stdout.
- Every `path` and `output_dir` must resolve with `toRealPath()` inside `DIGIDOC_MCP_ROOTS`; extraction names must match a container data file exactly and may not contain separators, `..` or NUL. Do not loosen `PathPolicy`.
- Container preflight (ZIP central directory vs local entries, inflated-byte budget, nested limits, DDOC/DTD refusals) runs before DigiDoc4j parses anything; keep it ahead of any parse and fail closed on ambiguous layouts.
- Inspection (open, list, extract) uses an empty trust source and makes no network calls; only validation loads trust lists. Validation runs on one dedicated worker with a deadline (`DIGIDOC_MCP_VALIDATION_TIMEOUT_SECONDS`, default 120; error `VALIDATION_TIMEOUT`).
- Container metadata is untrusted: diagnostics use generic messages, extracted content is never returned inline.
- Dependency versions are pinned in `pom.xml` (DigiDoc4j, MCP SDK); use stable releases only and record the date and source when bumping.
- Tests use test-signed fixtures in TEST mode; never commit real signed personal documents.
