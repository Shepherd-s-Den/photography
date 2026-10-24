# Photography contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

- [SQLite migrations](../backend/src/main/resources/db/) define the schema contract, including the four seeded `AlbumStart` layouts; foreign-key enforcement is enabled on every connection.
- The [OpenAPI specification](http-api.openapi.json) defines the five album operations and `AlbumStart`-template listing, including requests, responses, pagination and error contracts.

## Frontend delivery

The backend packages the complete built frontend tree and serves `GET`/`HEAD` assets with their media types. Extensionless paths outside `/api`, `/health` and `/assets` return the app entry point. Missing assets, dotted paths and unsupported frontend methods return `404`; API and health responses take precedence.

Unmatched `/api` requests return JSON `404 {"code":"not_found"}`. The backend build requires the frontend entry point; Dagger prepares it for remote checks and the local launcher prepares it for development.
