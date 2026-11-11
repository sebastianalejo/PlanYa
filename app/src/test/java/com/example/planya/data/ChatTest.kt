package com.example.planya.data

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.ChatApi
import com.example.planya.data.remote.dto.ChatMessageDto
import com.example.planya.data.remote.dto.ChatMessageResponseDto
import com.example.planya.data.remote.dto.ChatPageDto
import com.example.planya.data.remote.dto.ChatPageResponseDto
import com.example.planya.data.remote.dto.SendChatMessageDto
import com.example.planya.data.repository.ChatNetworkException
import com.example.planya.data.repository.ChatRepositoryImpl
import com.example.planya.data.repository.chatFailure
import com.example.planya.domain.model.ChatBlockReason
import com.example.planya.domain.model.ChatBlockedException
import com.example.planya.domain.model.ChatMessage
import com.example.planya.domain.model.ChatPage
import com.example.planya.domain.model.chatRemainingLabel
import com.example.planya.domain.model.mergeChatMessages
import com.example.planya.domain.repository.ChatRepository
import com.example.planya.domain.usecase.chat.GetChatMessagesUseCase
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ChatTest {
    private val plan = "8b3c2f4e-1d2a-4c55-9d3e-5f6a7b8c9d0e"

    private fun message(ms: Long, id: String, sender: String = "u1", text: String = "hola") =
        ChatMessage(id, plan, sender, "Nombre", text, ms, "${ms.toString().padStart(13, '0')}#$id")

    private fun dto(ms: Long, id: String, sender: String = "u1") = ChatMessageDto(id = id, planId = plan, senderId = sender,
        senderName = "Ana", text = "hola", createdAt = ms, cursor = "${ms.toString().padStart(13, '0')}#$id", expiresAt = 1L)

    @Test fun `merge removes duplicates and keeps the server order`() {
        val a = message(1000, "a"); val b = message(2000, "b"); val c = message(2000, "c")
        val merged = mergeChatMessages(listOf(b, a), listOf(c, a.copy(text = "hola"), b))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test fun `older page merged before newer keeps a single ordered list`() {
        val newer = (10L..19L).map { message(it * 1000, "n$it") }
        val older = (1L..9L).map { message(it * 1000, "o$it") } + newer.first()
        val merged = mergeChatMessages(newer, older)
        assertEquals(19, merged.size)
        assertEquals(merged.sortedBy { it.createdAtMillis }, merged)
    }

    @Test fun `mapper drops incomplete messages instead of inventing data`() {
        val page = ChatPageDto(items = listOf(dto(2000, "b"), dto(1000, "a"), dto(3000, "c").copy(senderName = " "),
            dto(4000, "d").copy(createdAt = null)), nextKey = "older", chatExpiresAt = 99, serverTime = 50, isHost = true).toDomain()
        assertEquals(listOf("a", "b"), page.messages.map { it.id })
        assertEquals("older", page.olderKey)
        assertEquals(99L, page.chatExpiresAt)
        assertTrue(page.isHost)
    }

    @Test fun `server errors become clear exceptions`() {
        assertTrue(chatFailure(401, null) is AuthSessionRequiredException)
        val left = chatFailure(403, """{"success":false,"error":"x","code":"NOT_MEMBER"}""")
        assertEquals(ChatBlockReason.NOT_MEMBER, (left as ChatBlockedException).reason)
        assertEquals(ChatBlockReason.CHAT_CLOSED, (chatFailure(410, """{"code":"CHAT_CLOSED"}""") as ChatBlockedException).reason)
        assertEquals(ChatBlockReason.PLAN_CANCELLED, (chatFailure(410, """{"code":"PLAN_CANCELLED"}""") as ChatBlockedException).reason)
        assertEquals(ChatBlockReason.PLAN_NOT_FOUND, (chatFailure(404, """{"code":"PLAN_NOT_FOUND"}""") as ChatBlockedException).reason)
        // 404 de API Gateway sin código: la ruta no está desplegada, no "el plan no existe".
        val undeployed = chatFailure(404, """{"message":"Not Found"}""")
        assertFalse(undeployed is ChatBlockedException)
        assertTrue(undeployed.message!!.contains("rutas"))
        assertTrue(chatFailure(400, """{"error":"Escribe un mensaje","code":"INVALID"}""") is IllegalArgumentException)
        assertTrue(chatFailure(502, "<html>").message!!.contains("laboratorio"))
    }

    private open class FakeApi : ChatApi {
        override suspend fun conversations(token: String, nextKey: String?) =
            com.example.planya.data.remote.dto.ConversationsResponseDto()
        var lastToken: String? = null
        override suspend fun messages(token: String, planId: String, nextKey: String?, after: String?): ChatPageResponseDto {
            lastToken = token
            return ChatPageResponseDto(true, ChatPageDto(items = listOf(dto0), chatExpiresAt = 10, serverTime = 5))
        }
        override suspend fun send(token: String, planId: String, body: SendChatMessageDto) =
            ChatMessageResponseDto(true, dto0.copy(text = body.text, clientMessageId = body.clientMessageId))
        companion object {
            val dto0 = ChatMessageDto(id = "m1", planId = "8b3c2f4e-1d2a-4c55-9d3e-5f6a7b8c9d0e", senderId = "u1",
                senderName = "Ana", text = "hola", createdAt = 1, cursor = "0000000000001#m1")
        }
    }

    @Test fun `repository sends the session token and accepts its own confirmation`() = runBlocking {
        val api = FakeApi()
        val repository = ChatRepositoryImpl(api, "u1") { "token-1" }
        val sent = repository.send(plan, "Llego en 5", "cid")
        assertEquals("Llego en 5", sent.text)
        assertEquals("cid", sent.clientMessageId)
        repository.latest(plan)
        assertEquals("Bearer token-1", api.lastToken)
    }

    @Test fun `confirmation for another account is rejected`() = runBlocking {
        val repository = ChatRepositoryImpl(FakeApi(), "otra-cuenta") { "token" }
        val failure = runCatching { repository.send(plan, "hola", "cid") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun `page from another plan is rejected`() = runBlocking {
        val repository = ChatRepositoryImpl(FakeApi(), "u1") { "token" }
        assertTrue(runCatching { repository.latest("another-plan") }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `confirmation from another request is rejected`() = runBlocking {
        val api = object : FakeApi() {
            override suspend fun send(token: String, planId: String, body: SendChatMessageDto) =
                ChatMessageResponseDto(true, dto0.copy(clientMessageId = "another-request"))
        }
        val repository = ChatRepositoryImpl(api, "u1") { "token" }
        assertTrue(runCatching { repository.send(plan, "hola", "cid") }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `http and network failures are mapped`() = runBlocking {
        val closed = object : FakeApi() {
            override suspend fun messages(token: String, planId: String, nextKey: String?, after: String?): ChatPageResponseDto =
                throw HttpException(Response.error<ChatPageResponseDto>(410, """{"code":"CHAT_CLOSED","error":"x"}""".toResponseBody()))
            override suspend fun send(token: String, planId: String, body: SendChatMessageDto): ChatMessageResponseDto =
                throw IOException("sin red")
        }
        val repository = ChatRepositoryImpl(closed, "u1") { "token" }
        val read = runCatching { repository.latest(plan) }.exceptionOrNull()
        assertEquals(ChatBlockReason.CHAT_CLOSED, (read as ChatBlockedException).reason)
        assertTrue(runCatching { repository.send(plan, "hola", "cid") }.exceptionOrNull() is ChatNetworkException)
    }

    @Test fun `newer messages are chained across pages with a limit`() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = object : ChatRepository {
            override suspend fun latest(planId: String): ChatPage = error("no usado")
            override suspend fun older(planId: String, cursor: String): ChatPage = error("no usado")
            override suspend fun send(planId: String, text: String, clientMessageId: String): ChatMessage = error("no usado")
            override suspend fun newer(planId: String, cursor: String): ChatPage {
                calls += cursor
                val index = calls.size.toLong()
                val item = message(index * 1000, "m$index")
                return ChatPage(listOf(item), null, if (index < 10) item.cursor else null, 100, 10, null, false)
            }
        }
        val page = GetChatMessagesUseCase(repository).newer(plan, "inicio", maxPages = 3)
        assertEquals(3, calls.size)
        assertEquals(listOf("m1", "m2", "m3"), page.messages.map { it.id })
        assertNotNull(page.newerKey) // quedan más: la próxima actualización continúa desde aquí
    }

    @Test fun `remaining time label`() {
        assertEquals("menos de 1 min", chatRemainingLabel(30))
        assertEquals("12 min", chatRemainingLabel(11 * 60 + 1))
        assertEquals("1 h 05 min", chatRemainingLabel(3600 + 4 * 60 + 30))
        assertEquals("2 h 00 min", chatRemainingLabel(7200))
        assertEquals("cerrado", chatRemainingLabel(0))
    }
}
