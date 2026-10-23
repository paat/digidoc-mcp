# Issue #1 implementation evidence

Environment: Linux DEV workspace, Java 21.0.12.1, Maven 3.8.7, service user `dev`.
Date: 2026-10-23. Branch: `feat/1-readonly-mvp`.

## Design and dependencies

The official synchronous MCP SDK owns stdio. `Main` reserves the original stdout for
the transport and redirects ordinary stdout to stderr. `Tools` declares input/output
schemas and converts failures to structured tool errors. `ContainerService` uses
DigiDoc4j for parsing and validation, with bounded input snapshots and secure
directory handles for filesystem access. Inspection has an empty trust source and
disabled AIA fetching; validation has the configured trust source and cache.

Maven Central metadata resolved the latest stable dependencies to **DigiDoc4j 6.2.0**
and **MCP SDK 2.1.0**, pinned by `digidoc4j.version` and `mcp.version` in `pom.xml`.
MCP 2.2.0-M1 was excluded as a milestone.

Primary sources opened:

- [DigiDoc4j Maven metadata](https://repo.maven.apache.org/maven2/org/digidoc4j/digidoc4j/maven-metadata.xml)
- [MCP Maven metadata](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp/maven-metadata.xml)
- [DigiDoc4j source/tests at 61978f4](https://github.com/open-eid/digidoc4j/tree/61978f47a9fa595a01fe8b288a53fa1f8138e893)
- [MCP SDK 2.1.0 server documentation](https://github.com/modelcontextprotocol/java-sdk/blob/v2.1.0/docs/server.md)

## Final suite output

`mvn -B verify` exited 0:

```text
ValidationNetworkTest  Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
ContainerServiceTest   Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
ToolsTest              Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
PathPolicyTest         Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
AppConfigTest          Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
Surefire total         Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
StdioSmokeIT           Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Combined total: **42 run, 0 failures, 0 errors, 0 skipped**.

`mvn -B verify -DskipNetworkTests` exited 0:

```text
ContainerServiceTest   Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
ToolsTest              Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
PathPolicyTest         Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
AppConfigTest          Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
Surefire total         Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
StdioSmokeIT           Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Combined total: **40 run, 0 failures, 0 errors, 0 skipped**. The two `network`
tests are excluded by the profile, so Maven does not count them as skipped.

## Manual stdio check

Started `java -jar target/digidoc-mcp.jar` as a Bash coprocess. Used `printf '%s\n'`
to send `initialize`, read its response, then sent `notifications/initialized` and
`tools/list`. Kept stdin open until both responses arrived, as an MCP client does,
then closed stdin and waited for normal process exit. Python `json.loads` parsed
every complete stdout line and checked the response IDs and tool count.

```text
stdout: 2 lines; JSON-RPC objects: 2; non-JSON lines: 0
response ids: [1, 2]
tools: open_container, list_signatures, extract_file, validate_container
stderr bytes: 0
```

The initialization stdout frame was:

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"logging":{},"tools":{"listChanged":false}},"serverInfo":{"name":"digidoc-mcp","version":"0.1.0"}}}
```

The automated jar smoke test additionally calls `open_container`, checks a refused
outside-root call, and verifies another successful call afterward. Its parser
rejects blank lines, non-JSON text, trailing garbage, and non-JSON-RPC objects.

## Regression history

**The new behavioral tests initially passed against the new implementation.** The
initial baseline command failed because the repository had no POM; that is a
scaffolding failure, not evidence of behavioral red-before-fix tests.

Temporary fault injections subsequently proved these detectors fail:

| Deliberate fault | Test output |
| --- | --- |
| Plain-text line on protocol stdout | 1 run, 1 failure, 0 errors, 0 skipped; `Non-JSON stdout line contaminates the MCP transport: REGRESSION_STDOUT_CONTAMINATION` |
| Disabled allowed-root predicate | 3 run, 3 failures, 0 errors, 0 skipped: outside-root, inside-to-outside symlink, `..` escape |
| Removed inspection's empty trust source | 1 run, 1 failure, 0 errors, 0 skipped: inspection unexpectedly initialized the TSL cache |

All injected faults were removed before the final suites. The offline inspection
change also produced a real regression: the LT fixture was reported as T without
a trusted issuer chain. The assertion failed (`expected: <LT> but was: <T>`).
Listing now describes embedded signature evidence without a trust claim; validation
uses DigiDoc4j's assessed profile. The final suites pass both assertions.

Additional passing regressions cover unsafe extraction names (including NUL and
both separators), exact case-sensitive matching, existing-target preservation,
explicit overwrite, output-directory escape, target symlinks, compressed bombs,
streamed byte limits, oversized containers before parsing, deterministic ancestor
symlink replacement, malformed inputs, and DDOC external-file/entity refusal.

## Requirement coverage

| Requirement | Implementation and evidence |
| --- | --- |
| Shaded jar and four JSON tools | `pom.xml`, `Main`, `Tools`; packaged jar smoke asserts initialize, exactly four schemas, open result |
| Stdout isolation | Saved transport stream, global stdout redirect, stderr logback appender with bounded messages; smoke fault injection fails |
| Canonical allowed roots | `AppConfig`, `PathPolicy`; all four tools reject outside roots; symlink, `..`, sibling-prefix, and changed-ancestor tests |
| Safe extraction and limits | Exact original data names, secure create-new or staged replacement, SHA-256, bounded snapshots/ZIP expansion/writes; preservation and bomb tests |
| Structured failure handling | Fixed code/message objects with `isError: true`; argument tests and jar error/recovery sequence |
| Mode, cache, network scope | PROD/TEST mapping, configured file-cache loader, offline inspection, bundled configuration; config tests and TEST validation tests; no SiVa integration |
| Acceptance fixtures and tests | Valid TEST ASiC-E → VALID; tampered payload → INVALID; two-file/two-signature generated fixture; provenance in `src/test/resources/README.md` |
| Documentation | README marks unofficial/RIA non-affiliation; build, environment, tool contracts, and local/SSH Claude Desktop/Code examples |

## Scope and limits

- Linux filesystem providers must support `SecureDirectoryStream`; access fails
  closed on unsupported providers.
- DDOC permits only nonempty embedded payloads with safe single file names. Empty,
  detached/hash-only, DTD/entity, or path-bearing forms are rejected because the
  legacy library can resolve data filenames from disk or strip their paths.
- Validation may fetch TSL/LOTL and DigiDoc4j OCSP/AIA evidence. No document is sent
  to SiVa or another validation service. Live trust availability can affect results.
- Baseline behavioral red-before-fix was not achieved; the distinction and fault
  injection results are recorded above. No claim is made that every regression
  was observed failing before implementation.
- No workflows, project license, signing/creation server tools, CDOC, push, or PR
  were added/performed. No image installation or container pin was changed.
