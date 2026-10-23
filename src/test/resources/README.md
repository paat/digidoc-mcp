# Test fixtures

The files under `fixtures/` originate from [open-eid/digidoc4j](https://github.com/open-eid/digidoc4j), commit `61978f47a9fa595a01fe8b288a53fa1f8138e893`:

| Local file | Upstream path under `digidoc4j/src/test/resources/` |
| --- | --- |
| `valid-asice.asice` | `testFiles/valid-containers/valid-asice.asice` |
| `test-signing-token.p12` | `testFiles/p12/sign_RSA_from_TEST_of_ESTEIDSK2015.p12` |

These upstream materials are covered by the upstream GNU Lesser General Public License, version 2.1; see [the upstream license](https://github.com/open-eid/digidoc4j/blob/61978f47a9fa595a01fe8b288a53fa1f8138e893/LICENSE.LGPL) and [authors](https://github.com/open-eid/digidoc4j/blob/61978f47a9fa595a01fe8b288a53fa1f8138e893/AUTHORS). These fixtures remain under upstream LGPL-2.1, while the rest of the repository is licensed under Apache-2.0.

The PKCS#12 token is a **public test credential**, password `1234`. It must never be used for production signatures. Tests generate a two-file, two-signature B_BES container locally with this token; generating test fixtures does not add signing to the server's tool surface. The network tests validate the upstream timestamped ASiC-E fixture in TEST mode and a copy whose signed `test.txt` bytes are replaced after signing. Size-limit and unsigned fixtures are generated in temporary directories.

Tests tagged `network` run by default and can be excluded with `-DskipNetworkTests`. The jar integration test runs during Maven `verify`, after shading, and rejects every non-JSON stdout line.
