package com.nexo.browser

import com.nexo.browser.ai.AiHub
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AiHubTest {
    private val shieldValues = JSONObject().put("aiConfig", JSONObject()
        .put("enabled", true).put("scope", "selected").put("provider", "openai")
        .put("endpoint", "https://api.openai.com/v1").put("model", "gpt-4.1-nano").put("apiKey", "sk-propia"))

    @Test fun shieldReadsTheMasterConnectionAndKeepsItsOwnPolicy() {
        val master = AiHub.Connection("openrouter", "", "openai/gpt-4.1-mini", "sk-or-maestra")
        val config = AiHub.overlayShield(shieldValues, master).getJSONObject("aiConfig")
        // OpenRouter, Gemini, Groq…: Chat Completions con su dirección real.
        assertEquals("custom", config.getString("provider"))
        assertEquals("https://openrouter.ai/api/v1", config.getString("endpoint"))
        assertEquals("openai/gpt-4.1-mini", config.getString("model"))
        assertEquals("sk-or-maestra", config.getString("apiKey"))
        // Lo que decide PhantomShield (activación, alcance) no se toca.
        assertEquals(true, config.getBoolean("enabled"))
        assertEquals("selected", config.getString("scope"))
    }

    @Test fun claudeAndOpenAiKeepTheirNativeProtocol() {
        val claude = AiHub.Connection("claude", "", "claude-haiku-5-5", "k")
        assertEquals("claude", AiHub.overlayShield(shieldValues, claude).getJSONObject("aiConfig").getString("provider"))
        val custom = AiHub.Connection("custom", "https://ia.example.com/v1/", "m", "")
        assertEquals("https://ia.example.com/v1", AiHub.overlayShield(shieldValues, custom).getJSONObject("aiConfig").getString("endpoint"))
    }

    @Test fun masterKeyIsNeverPersistedInTheShieldStore() {
        val master = AiHub.Connection("openai", "", "gpt-4.1-mini", "sk-maestra")
        val write = JSONObject().put("aiConfig", JSONObject().put("enabled", true).put("apiKey", "sk-maestra"))
        assertEquals("", AiHub.stripShield(write, master).getJSONObject("aiConfig").getString("apiKey"))
        // Una clave propia distinta sí se guarda (modo no unificado o clave independiente).
        val own = JSONObject().put("aiConfig", JSONObject().put("apiKey", "sk-otra"))
        assertSame(own, AiHub.stripShield(own, master))
    }

    @Test fun withoutMasterEverythingStaysAsSaved() {
        assertSame(shieldValues, AiHub.overlayShield(shieldValues, null))
        val other = JSONObject().put("blockedCount", 3)
        assertSame(other, AiHub.overlayShield(other, AiHub.Connection("openai", "", "m", "k")))
    }
}
