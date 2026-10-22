package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ada's Live mode, slice 2: the messages a device sends Google and reads back. */
class LiveProtocolTest {
    @Test
    fun theTokenGoesInTheUrlAndTheSetupAsksForAudioInTheVoice() {
        assertEquals("wss://x.example/ws?access_token=auth_tokens%2Fab%2Bc%3D", LiveProtocol.connectUrl("wss://x.example/ws", "auth_tokens/ab+c="))
        assertEquals("wss://x.example/ws?v=1&access_token=t", LiveProtocol.connectUrl("wss://x.example/ws?v=1", "t"))
        val setup = Json.parseToJsonElement(LiveProtocol.setup("gemini-3.8-live", "Kore")).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        val gen = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", gen["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("Kore", gen["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        assertFalse("systemInstruction" in setup)
        assertTrue("sessionResumption" in setup)
        val resumed = Json.parseToJsonElement(LiveProtocol.setup("models/m-live", "Puck", resumeHandle = "h1", instruction = "You are Ada.")).jsonObject["setup"]!!.jsonObject
        assertEquals("models/m-live", resumed["model"]!!.jsonPrimitive.content)
        assertEquals("h1", resumed["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
        assertEquals("You are Ada.", resumed["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val audio = Json.parseToJsonElement(LiveProtocol.audio("AAAA")).jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AAAA", audio["data"]!!.jsonPrimitive.content)
        assertTrue("audioStreamEnd" in LiveProtocol.audioEnd())
    }

    @Test
    fun googlesMessagesReadAsEventsInOrderAndJunkReadsAsNothing() {
        assertEquals(listOf(LiveProtocol.Event.SetupComplete), LiveProtocol.decode("""{"setupComplete":{}}"""))
        assertEquals(
            listOf(LiveProtocol.Event.Audio(listOf("AAA", "BBB")), LiveProtocol.Event.TurnComplete),
            LiveProtocol.decode("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA"}},{"text":"hi"},{"inlineData":{"mimeType":"image/png","data":"X"}},{"inlineData":{"data":"BBB"}}]},"turnComplete":true}}"""),
        )
        assertEquals(listOf(LiveProtocol.Event.Interrupted), LiveProtocol.decode("""{"serverContent":{"interrupted":true}}"""))
        assertEquals(listOf(LiveProtocol.Event.GoAway(12_500)), LiveProtocol.decode("""{"goAway":{"timeLeft":"12.5s"}}"""))
        assertEquals(listOf(LiveProtocol.Event.GoAway(null)), LiveProtocol.decode("""{"goAway":{}}"""))
        assertEquals(listOf(LiveProtocol.Event.Resumable("h2")), LiveProtocol.decode("""{"sessionResumptionUpdate":{"newHandle":"h2","resumable":true}}"""))
        assertEquals(emptyList(), LiveProtocol.decode("""{"sessionResumptionUpdate":{"newHandle":"h3","resumable":false}}"""))
        assertEquals(emptyList(), LiveProtocol.decode("not json"))
        assertEquals(emptyList(), LiveProtocol.decode("""{"usageMetadata":{"totalTokenCount":3}}"""))
    }

    @Test
    fun slice4TranscriptsAndToolCallsReadAsEventsAndTheSearchAnswerGoesBack() {
        assertEquals(
            listOf(LiveProtocol.Event.Heard("what's the latest"), LiveProtocol.Event.Said("The strike")),
            LiveProtocol.decode("""{"serverContent":{"inputTranscription":{"text":"what's the latest"},"outputTranscription":{"text":"The strike"}}}"""),
        )
        assertEquals(
            listOf(LiveProtocol.Event.ToolCall("c1", "search_the_web", "rail strike"), LiveProtocol.Event.ToolCall("", "other", "")),
            LiveProtocol.decode("""{"toolCall":{"functionCalls":[{"id":"c1","name":"search_the_web","args":{"query":"rail strike"}},{"name":"other"},{"id":"x"}]}}"""),
        )
        val r = Json.parseToJsonElement(LiveProtocol.toolResponse("c1", "search_the_web", listOf(LiveProtocol.Hit("bbc.co.uk", "Strike off", "Talks went well")))).jsonObject
        val fr = r["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray.single().jsonObject
        assertEquals("c1", fr["id"]!!.jsonPrimitive.content)
        val hit = fr["response"]!!.jsonObject["results"]!!.jsonArray.single().jsonObject
        assertEquals("1", hit["n"]!!.jsonPrimitive.content)
        assertEquals("bbc.co.uk", hit["site"]!!.jsonPrimitive.content)
        val refused = Json.parseToJsonElement(LiveProtocol.toolResponse("c2", "search_the_web", emptyList(), error = "no")).jsonObject
        assertEquals("no", refused["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray.single().jsonObject["response"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), LiveProtocol.decode("""{"serverContent":{"outputTranscription":{"text":""}}}"""))
    }
}
