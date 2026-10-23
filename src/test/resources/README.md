# Test fixtures

The following files under `fixtures/` originate from [open-eid/digidoc4j](https://github.com/open-eid/digidoc4j), commit `61978f47a9fa595a01fe8b288a53fa1f8138e893`:

| Local file | Upstream path under `digidoc4j/src/test/resources/` |
| --- | --- |
| `valid-asice.asice` | `testFiles/valid-containers/valid-asice.asice` |
| `test-signing-token.p12` | `testFiles/p12/sign_RSA_from_TEST_of_ESTEIDSK2015.p12` |

These upstream materials are covered by the upstream GNU Lesser General Public License, version 2.1; see [the upstream license](https://github.com/open-eid/digidoc4j/blob/61978f47a9fa595a01fe8b288a53fa1f8138e893/LICENSE.LGPL) and [authors](https://github.com/open-eid/digidoc4j/blob/61978f47a9fa595a01fe8b288a53fa1f8138e893/AUTHORS). These fixtures remain under upstream LGPL-2.1, while the rest of the repository is licensed under Apache-2.0.

The PKCS#12 token is a **public test credential**, password `1234`. It must never be used for production signatures. Tests generate a two-file, two-signature B_BES container locally with this token; generating test fixtures does not add signing to the server's tool surface. The network tests validate the upstream timestamped ASiC-E fixture in TEST mode and a copy whose signed `test.txt` bytes are replaced after signing. Size-limit and unsigned fixtures are generated in temporary directories.

Tests tagged `network` run by default and can be excluded with `-DskipNetworkTests`. The jar integration test runs during Maven `verify`, after shading, and rejects every non-JSON stdout line.

## Composite fixtures for issue #4

The following timestamped ASiC-S fixtures were generated on 2026-10-23 with
digidoc4j 6.2.0 in TEST mode and its default TSA, `http://tsa.demo.sk.ee/tsa`:

| File | Nested ASiC-E | SHA-256 |
| --- | --- | --- |
| `composite-asics.asics` | Unmodified upstream `valid-asice.asice` above | `d622d5a52b821e0a216a49921925a33e0690982d0fbac6278a7c35c98dcceb4e` |
| `composite-b-bes.asics` | Locally generated B_BES signature using the public token above | `26836e4511e563c9aec98ed8d4b01657d31d037497926407b2c729bf4a9507f2` |

Both fixtures retain the licensing of the included upstream test materials.
Generation used a TEST `Configuration`, an empty `TSLCertificateSourceImpl`
(generation does not need online trust-list validation), a 10-second connection
timeout and a 15-second socket timeout. The LT composite was built with:

```java
CompositeContainerBuilder.fromContainerFile("valid-asice.asice")
    .withConfiguration(configuration).buildTimestamped(timestamp -> {})
    .saveAsFile("composite-asics.asics");
```

For the B_BES fixture, an ASICE container was created with the same configuration,
then given `test.txt` (`text/plain`) containing UTF-8 bytes `composite fixture payload`.
It was signed with `SignatureBuilder.aSignature(inner)` using profile `B_BES`
and `PKCS12SignatureToken("test-signing-token.p12", "1234".toCharArray())`.
The wrapper was created with
`CompositeContainerBuilder.fromContainer(inner, "basic.asice")`
and the same `withConfiguration(...).buildTimestamped(timestamp -> {})` calls.
Timestamp bytes and signing times naturally change when regenerated.

Offline tests check nested signature counts and metadata without initializing a
TSL cache. Network tests check the LT composite and compare the B_BES composite's
inner indication with digidoc4j's own nested validation report. The B_BES fixture
lacks long-term validation evidence and reports INDETERMINATE in TEST mode.

`timestamp-only.asics` was generated for issue #3 with digidoc4j 6.2.0 in TEST mode and the bundled test TSA (`http://tsa.demo.sk.ee/tsa`) on 2026-10-23. It contains the public text `Timestamp-only ASiC-S regression fixture for issue #3.` and one RFC 3161 timestamp, with no signatures. It is a project-generated Apache-2.0 test fixture. `TimestampValidationNetworkTest` checks its timestamp-only structure and validates it using TEST trust lists. To regenerate, build an ASICS container with that text as `timestamped.txt` (`text/plain`), add `TimestampBuilder.aTimestamp(container).invokeTimestamping()`, and save it. No private signing key is used.
