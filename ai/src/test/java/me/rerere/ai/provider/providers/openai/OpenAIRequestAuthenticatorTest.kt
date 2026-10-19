package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIRequestAuthenticatorTest {
    @Test
    fun `API key auth adds bearer token`() = runBlocking {
        val setting = ProviderSetting.OpenAI(apiKey = "sk-test")
        val request = OpenAIRequestAuthenticator(KeyRoulette.default())
            .authenticate(
                Request.Builder().url("https://api.openai.com/v1/responses"),
                setting,
            )
            .build()

        assertEquals("Bearer sk-test", request.header("Authorization"))
    }
}
