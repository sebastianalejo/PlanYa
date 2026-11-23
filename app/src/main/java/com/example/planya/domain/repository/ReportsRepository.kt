package com.example.planya.domain.repository

import com.example.planya.domain.model.ReportDetail
import com.example.planya.domain.model.ReportFilter
import com.example.planya.domain.model.ReportsPage

/** Solo lectura: la cuenta no puede cambiar estados, borrar ni sancionar desde US-13. */
interface ReportsRepository {
    suspend fun page(filter: ReportFilter, nextKey: String? = null): ReportsPage
    suspend fun detail(id: String): ReportDetail
}
