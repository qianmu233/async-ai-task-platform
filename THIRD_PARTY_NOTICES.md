# Third-party dependency notices

P1's authorized project-owned source is licensed under Apache License 2.0
(SPDX: Apache-2.0); see LICENSE. This file records third-party dependency
information and does not relicense those components.

The source candidate references independently licensed Maven components and
does not vendor their JARs, sources, caches, or a built application. See
[the complete dependency inventory](docs/third-party/dependency-inventory.json)
and [license review](docs/DEPENDENCY_LICENSE_REVIEW.md) for exact coordinates,
versions, declared licenses, evidence, and verification limits.

Not all dependencies are Apache-2.0: MySQL Connector/J 9.7.0 declares GPLv2 with the
Universal FOSS Exception 1.0; Logback 1.5.34 declares EPL-2.0/LGPL-2.1-only dual
licensing; JUnit Jupiter 5.13.4 declares EPL-2.0. Jakarta and other components
retain the terms recorded for their exact versions in the inventory.
Bundling binaries or distributing modified dependencies requires a separate
review of the relevant licenses, notices, source obligations, and exceptions.

The MySQL Connector/J legal text extracted without alteration from the actual
resolved 9.7.0 JAR is retained under docs/third-party/licenses/. Copyright and
license statements in that document belong to their respective rights holders.
No personal or organizational copyright owner is invented for this project.
