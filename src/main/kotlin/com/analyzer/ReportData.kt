package com.analyzer

import kotlinx.serialization.Serializable

@Serializable
data class ReportData(
    val records: List<TestRecord>,
    val systemNames: Map<String, String>,
    val authorNames: Map<String, String>,
    val generatedAt: String,
    /**
     * Точный момент начала года формирования отчёта (1 января 00:00 в часовом
     * поясе генерации), ISO offset. Вычисляется на стороне Kotlin: JS в браузере
     * не может восстановить его из generatedAt — new Date(год, 0, 1) даёт
     * полночь в поясе браузера, а не в поясе генерации.
     */
    val yearStart: String
)
