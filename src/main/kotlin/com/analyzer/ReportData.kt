package com.analyzer

import kotlinx.serialization.Serializable

@Serializable
data class ReportData(
    val records: List<TestRecord>,
    val systemNames: Map<String, String>,
    val authorNames: Map<String, String>,
    val generatedAt: String
)