package io.egoflow.app.egoflow

/**
 * Ingest pipeline the server should provision for a stream session.
 *
 * Sent as the required `ingestType` field of POST /api/v1/streams/register (server zod enum
 * "MEDIAMTX" | "HTTP", no default). Lives next to the backend client rather than in `:core`
 * so this server-contract detail does not leak into the transport API surface.
 */
enum class IngestType(val wireValue: String) {
    MEDIAMTX("MEDIAMTX"),
    HTTP("HTTP"),
}
