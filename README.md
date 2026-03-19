# DomainDocs4s

![Discord](https://img.shields.io/discord/1240565362601230367?style=flat-square&logo=discord&link=https%3A%2F%2Fbit.ly%2Fbusiness4s-discord)
![Release](https://img.shields.io/badge/release-not--released-lightgrey?style=flat-square)

**DomainDocs4s** is a library that helps in generating human-readable domain documentation directly from Scala code by collecting
annotated concepts from TASTy and transforming them into structured, business-friendly output.

See the [**Website**](https://business4s.github.io/domainDocs4s/) for details and join our [**Discord**](https://bit.ly/business4s-discord) for discussions.

## Improvements / TODO

### Scanners
- STTP/HTTP client scanner (e.g. GoogleSheetClientImpl uses STTP, not detected)
- Prometheus scanner
- Akka ask/tell pattern detection ("unknown actor")
- Doobie scanner: reconstruct full SQL (substitute fragments, replace inputs with `?`, use JSqlParser)
- Transactor tracking: annotate transactor variables with db/schema info and propagate wherever they are used (could also extract db/schema from code automatically)

### Core
- Typesafe packages, SymbolModule.requiredPackage
- Limit scanning to non-test code

### Viewer (Cytoscape)
- Export view settings
- Save current modifications as a view (metabase-question style)
- Views defined dynamically by hand/JSON, stored in a JSON file
- Show all classes, let user hide/show in UI (by package or multi-select)
- Show hidden elements so they can be brought back
- Treat in-code overrides as a default view