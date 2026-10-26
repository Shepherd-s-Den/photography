# Photography contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

- [SQLite migrations](../backend/src/main/resources/db/) define the schema contract, including four `AlbumStart` layouts, 27 chapter-start layouts and all 249 ISO 3166-1 countries/territories; foreign-key enforcement is enabled on every connection.
- The [OpenAPI specification](http-api.openapi.json) defines year-required album creation, metadata reads and free-form rename, deletion, pagination, workspace reads, multi-country chapter creation/update and `AlbumStart`-template listing, including representation and error contracts.

## Frontend delivery

The backend packages the complete built frontend tree and serves `GET`/`HEAD` assets with their media types. Extensionless paths outside `/api`, `/health` and `/assets` return the app entry point. Missing assets, dotted paths and unsupported frontend methods return `404`; API and health responses take precedence.

Unmatched `/api` requests return JSON `404 {"code":"not_found"}`. The backend build requires the frontend entry point; Dagger prepares it for remote checks and the local launcher prepares it for development.
