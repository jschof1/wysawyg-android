package com.cragnet.wysawyg

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@ConsistentCopyVisibility
data class CloudDestination private constructor(val url: String, val origin: String) {
    companion object {
        fun parse(value: String, allowHttp: Boolean = false): CloudDestination {
            val parsed = value.trim().toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid HTTPS provider URL.")
            require(parsed.isHttps || allowHttp) { "Cloud transcription requires an HTTPS provider URL." }
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null) {
                "Remove credentials and fragments from the provider URL. Use the API key field for credentials."
            }
            val origin = parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
            return CloudDestination(parsed.toString(), origin)
        }
    }
}

data class CloudConfiguration(val destination: CloudDestination, val apiKey: String, val model: String, val prompt: String)
