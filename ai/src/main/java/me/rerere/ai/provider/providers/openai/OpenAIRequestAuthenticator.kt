package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.Request

/** Centralizes authentication headers for OpenAI-compatible providers. */
internal class OpenAIRequestAuthenticator(
    private val keyRoulette: KeyRoulette,
) {
    suspend fun authenticate(
        builder: Request.Builder,
        providerSetting: ProviderSetting.OpenAI,
    ): Request.Builder {
        val key = keyRoulette.next(
            providerSetting.apiKey,
            providerSetting.id.toString(),
        )
        return builder.header(AUTHORIZATION_HEADER, "Bearer $key")
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
    }
}
