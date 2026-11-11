package com.example.planya.data

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.ChatApi
import com.example.planya.data.remote.dto.*
import com.example.planya.data.repository.*
import com.example.planya.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ConversationsTest {
    private val id = "8b3c2f4e-1d2a-4c55-9d3e-5f6a7b8c9d0e"
    private val dto = ConversationDto(id, "Café de prueba", "host", "Ana", "CAFE", 1000, 8200, false)
    private open class Api : ChatApi {
        var reply = ConversationsResponseDto()
        var header: String? = null
        var cursor: String? = null
        var failure: Exception? = null
        override suspend fun conversations(token: String, nextKey: String?): ConversationsResponseDto {
            header = token; cursor = nextKey; failure?.let { throw it }; return reply
        }
        override suspend fun messages(token: String, planId: String, nextKey: String?, after: String?): ChatPageResponseDto = error("No usado")
        override suspend fun send(token: String, planId: String, body: SendChatMessageDto): ChatMessageResponseDto = error("No usado")
    }
    private fun api() = Api().apply { reply = ConversationsResponseDto(true, ConversationsPageDto("u1", listOf(dto), "next", 1100)) }
    @Test fun mapperPreservesFinishedPlanAndDropsMalformedRows() {
        val item = dto.toDomain()!!
        assertEquals(id, item.planId)
        assertEquals(PlanCategory.CAFE, item.category)
        assertTrue(item.isOpen(1100))
        assertFalse(item.isOpen(8200))
        assertNull(dto.copy(planId = "not-a-uuid").toDomain())
        assertNull(dto.copy(title = " ").toDomain())
        assertNull(dto.copy(chatExpiresAt = 999).toDomain())
        assertNull(dto.copy(planExpiresAt = null).toDomain())
    }
    @Test fun repositoryUsesCurrentSessionAndOpaqueCursor() = runBlocking {
        val api = api()
        val page = ConversationsRepositoryImpl(api, "u1") { "session-test" }.page("opaque")
        assertEquals("Bearer session-test", api.header)
        assertEquals("opaque", api.cursor)
        assertEquals(id, page.items.single().planId)
        assertEquals("next", page.nextKey)
        assertEquals(1100L, page.serverTime)
    }
    @Test fun anotherAccountsPageIsAnAuthenticationFailure() = runBlocking {
        val api = api().apply { reply = ConversationsResponseDto(true, ConversationsPageDto("another-user", listOf(dto), null, 1000)) }
        assertTrue(runCatching { ConversationsRepositoryImpl(api, "u1") { "t" }.page(null) }.exceptionOrNull() is AuthSessionRequiredException)
    }
    @Test fun undeployedRouteIsAnErrorNotAnEmptyList() = runBlocking {
        val api = api().apply { failure = HttpException(Response.error<ConversationsResponseDto>(404,
            """{"message":"Not Found"}""".toResponseBody())) }
        val result = runCatching { ConversationsRepositoryImpl(api, "u1") { "t" }.page(null) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("todavía no está disponible"))
    }
    @Test fun cursorExpiryHasItsOwnRecoverableFailure() = runBlocking {
        val api = api().apply { failure = HttpException(Response.error<ConversationsResponseDto>(409,
            """{"code":"CURSOR_EXPIRED"}""".toResponseBody())) }
        assertTrue(runCatching { ConversationsRepositoryImpl(api, "u1") { "t" }.page("old") }.exceptionOrNull() is ConversationsCursorExpiredException)
    }
    @Test fun ioAndCancellationAreNotConvertedIntoSuccessfulEmptyPages() = runBlocking {
        val api = api().apply { failure = IOException("Offline") }
        val repository = ConversationsRepositoryImpl(api, "u1") { "t" }
        assertTrue(runCatching { repository.page(null) }.exceptionOrNull() is ChatNetworkException)
        api.failure = CancellationException("Cancelled")
        assertTrue(runCatching { repository.page(null) }.exceptionOrNull() is CancellationException)
    }
    @Test fun mergeUpdatesDuplicatesAndKeepsSoonestClosingFirst() {
        val a = dto.toDomain()!!
        val b = a.copy(planId = "second", chatExpiresAt = 9000)
        val merged = mergeConversations(listOf(b, a), listOf(a.copy(title = "Actualizado")))
        assertEquals(listOf(id, "second"), merged.map { it.planId })
        assertEquals("Actualizado", merged.first().title)
    }
}
