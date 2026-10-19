package com.dsh.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlatJsonTest {

    @Test
    fun `reads string values`() {
        assertEquals("message", FlatJson.string("""{"type":"message"}""", "type"))
    }

    @Test
    fun `reads integer values`() {
        assertEquals(0, FlatJson.int("""{"type":"exit","code":0}""", "code"))
        assertEquals(143, FlatJson.int("""{"type":"exit","code":143}""", "code"))
    }

    @Test
    fun `missing keys are null rather than an error`() {
        assertNull(FlatJson.string("""{"a":"b"}""", "zzz"))
    }

    @Test
    fun `a key appearing INSIDE a string value is not matched`() {
        // The reason this is a parser and not a regex. An agent's own reply can
        // contain JSON, and a regex would read the embedded "type" as the event
        // type and misroute the message.
        val payload = """{"type":"message","text":"look: \"type\":\"exit\" ok"}"""
        assertEquals("message", FlatJson.string(payload, "type"))
        assertEquals("look: \"type\":\"exit\" ok", FlatJson.string(payload, "text"))
    }

    @Test
    fun `escapes are decoded`() {
        val payload = """{"text":"line1\nline2\ttabbed"}"""
        assertEquals("line1\nline2\ttabbed", FlatJson.string(payload, "text"))
    }

    @Test
    fun `unicode escapes are decoded`() {
        assertEquals("A\u2026B", FlatJson.string("""{"text":"A\u2026B"}""", "text"))
    }

    @Test
    fun `escaped quotes and backslashes survive`() {
        assertEquals("""say "hi" \ bye""", FlatJson.string("""{"text":"say \"hi\" \\ bye"}""", "text"))
    }

    @Test
    fun `braces inside a string do not end the object`() {
        assertEquals("{not the end}", FlatJson.string("""{"text":"{not the end}","type":"message"}""", "text"))
        assertEquals("message", FlatJson.string("""{"text":"{not the end}","type":"message"}""", "type"))
    }

    @Test
    fun `a null value reads as null`() {
        assertNull(FlatJson.string("""{"signal":null}""", "signal"))
    }

    @Test
    fun `nested values are skipped without breaking the parse`() {
        val payload = """{"meta":{"a":[1,2]},"type":"message"}"""
        assertEquals("message", FlatJson.string(payload, "type"))
        assertNull("nested objects are not flattened", FlatJson.string(payload, "meta"))
    }

    @Test
    fun `an empty object parses to nothing`() {
        assertEquals(emptyMap<String, String?>(), FlatJson.parse("{}"))
    }

    @Test
    fun `malformed input returns null instead of throwing`() {
        // A dropped connection can truncate a payload mid-object. That must not
        // take down the reader thread.
        assertNull(FlatJson.parse("""{"type":"mess"""))
        assertNull(FlatJson.parse("not json at all"))
        assertNull(FlatJson.parse(""))
        assertNull(FlatJson.parse("""{"a"}"""))
    }

    @Test
    fun `whitespace between tokens is tolerated`() {
        assertEquals("message", FlatJson.string("{ \"type\" : \"message\" }", "type"))
    }

    @Test
    fun `a non-numeric value for an int key is null`() {
        assertNull(FlatJson.int("""{"code":"nope"}""", "code"))
    }
}
