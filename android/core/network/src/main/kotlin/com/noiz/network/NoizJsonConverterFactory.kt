package com.noiz.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Converter

/**
 * Retrofit converter backed by kotlinx.serialization (§4.2).
 *
 * `converter-kotlinx-serialization` is a declared dependency of this module and is
 * re-exported via `api(libs.retrofit.serialization)`, so the wire codec is the real
 * one. The previous implementation returned `null` from every method with the comment
 * "stub used to unblock compilation", which made Retrofit throw
 * `Could not locate Converter for … NoizApi` on the first call of *every* API method.
 *
 * The official converter also unwraps the `Continuation<T>` wrapper that Retrofit
 * passes for `suspend` functions, and honours `@Url`/`@Body` parameter annotations,
 * so hand-rolling those would only reintroduce bugs.
 */
object NoizJsonConverterFactory {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    @JvmStatic
    fun create(): Converter.Factory = NoizJson.asConverterFactory(JSON_MEDIA_TYPE)
}