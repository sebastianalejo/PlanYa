package com.example.planya.data.operator

import android.content.Context
import com.example.planya.domain.model.OperatorRequestJournal
import com.google.gson.Gson
import java.security.MessageDigest
import java.util.UUID

class OperatorJournal(context: Context, private val userId: String) : OperatorRequestJournal {
    private val preferences = context.applicationContext.getSharedPreferences("operator_requests", Context.MODE_PRIVATE)
    override fun requestId(payload: List<String>): String = synchronized(lock) {
        val bytes = Gson().toJson(listOf(userId) + payload).toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        preferences.getString(key, null)?.let { return@synchronized it }
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString(key, id).commit()) { "No se pudo guardar la solicitud. No se ha enviado." }
        id
    }
    private companion object { val lock = Any() }
}
