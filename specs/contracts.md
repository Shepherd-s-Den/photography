# Photography contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

- [SQLite migrations](../backend/src/main/resources/db/) define the schema contract, including 68 photo layouts beside four `AlbumStart` and 27 chapter-start layouts, and all 249 ISO 3166-1 countries/territories; the reference seeds own the authoritative catalog inventory. Foreign-key enforcement is enabled on every connection. Chapter-owned photo rows contain complete original metadata and required thumbnail references, with a separate technical photo-write journal rather than persisted chapter-import status. This breaks the undeployed photo schema; checksum-incompatible development databases require explicit recreation, preserving retained data unless its owner authorizes replacement.
- The [OpenAPI specification](http-api.openapi.json) defines year-required album creation, metadata reads and free-form rename, deletion, pagination, chapter inventories in workspace reads, multi-country chapter creation/update, spread insertion/movement/template change/deletion, context-scoped template listings, photo inventory/Refresh, album SSE and managed-thumbnail delivery, including representation and error contracts. Workspace spreads carry `templateId`; occupied template changes/deletes use the `409 spread_occupied` confirmation handshake.
- Successful chapter saves return the saved identity and scheduling result separately. Postcommit scheduling failure preserves the successful save and requires Refresh-only retry; unchanged-source updates return `not-requested`. Inventory counts describe complete photo rows and authoritative placement references; runtime processing feedback resets on restart.

## Frontend delivery

The backend packages the complete built frontend tree and serves `GET`/`HEAD` assets with their media types. Extensionless paths outside `/api`, `/health`, `/thumbnails` and `/assets` return the app entry point. Missing assets, dotted paths and unsupported frontend methods return `404`; API, health and managed-thumbnail responses take precedence.

Unmatched `/api` requests return JSON `404 {"code":"not_found"}`. The backend build requires the frontend entry point; Dagger prepares it for remote checks and the local launcher prepares it for development.
