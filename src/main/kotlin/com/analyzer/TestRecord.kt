package com.analyzer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TestRecord(
    @SerialName("author") val authorEmail: String,
    @SerialName("test") val functionName: String,
    @SerialName("file") val filePath: String,
    val date: String,
    @SerialName("system") val systemId: String? = null
)