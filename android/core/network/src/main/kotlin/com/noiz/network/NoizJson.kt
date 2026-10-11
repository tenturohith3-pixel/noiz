package com.noiz.network

import kotlinx.serialization.json.Json

/**
 * One codec for the whole app (§10: "explicit wire models and predictable schema
 * evolution").
 *
 *  - `ignoreUnknownKeys`: a v1 server may add fields; the client must not crash.
 *  - `coerceInputValues`: `null` where the server promised a primitive becomes the
 *    Kotlin default instead of a decode failure.
 *  - `explicitNulls = false`: absent and `null` are the same thing on this API.
 *  - `encodeDefaults = false`: we never send a field the server did not ask for.
 */
val NoizJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}
