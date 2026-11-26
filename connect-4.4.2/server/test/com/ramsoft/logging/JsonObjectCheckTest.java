package com.ramsoft.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonParser;

public class JsonObjectCheckTest {

    private static final String[] VALID = {
        "{}",
        " {  } ",
        "{\"a\":1}",
        "{\"timestamp\":\"2026-11-26T10:00:00.000\",\"level\":\"DEBUG\",\"message\":\"line\\nwith \\\"escapes\\\" \\u00e9\\/\"}",
        "{\"n\":[-0,1.5,2e10,3E-2,-4.25e+3],\"o\":{\"p\":[true,false,null,{}]},\"s\":\"\"}",
        "{\"unicode\":\"\u4e2d\ud83d\ude00\"}",
        "{\r\n\t\"a\" : \"b\" ,\n \"c\":[ ]\r\n}",
    };

    private static final String[] INVALID = {
        "",
        "   ",
        "[]",
        "[{\"a\":1}]",
        "\"just a string\"",
        "{",
        "{\"a\":1",
        "{\"a\":1}}",
        "{\"a\":1} trailing",
        "{\"a\":1},{\"b\":2}",
        "{\"key\":\"device\"7\"}",            // an unescaped quote from a concatenated value
        "{\"thread\":\"Send \"ADT\" (1)\"}",   // a bare %t
        "{\"a\":\"raw\ttab\"}",               // a raw control character in a string
        "{\"a\":\"raw\nnewline\"}",
        "{\"a\":\"bad \\x escape\"}",
        "{\"a\":\"short \\u12\"}",
        "{\"a\":\"\\u12g4\"}",
        "{\"a\":\"\\u\uff26\uff26\uff26\uff26\"}",  // full-width F: a Unicode digit, not JSON hex
        "{\"a\":\"\\u\u0661\u0662\u0663\u0664\"}",  // Arabic-Indic digits
        "{\"a\":01}",
        "{\"a\":1.}",
        "{\"a\":.5}",
        "{\"a\":+1}",
        "{\"a\":1e}",
        "{\"a\":tru}",
        "{\"a\":NaN}",
        "{a:1}",
        "{\"a\" 1}",
        "{\"a\":1,}",
        "{,\"a\":1}",
        "{\"a\":[1,]}",
        "{\"a\":[1 2]}",
        "{'a':1}",
        "{\"a\":\"unterminated}",
    };

    @Test
    public void wellFormedObjectsAreAccepted() {
        for (String s : VALID) {
            assertTrue(s, JsonObjectCheck.of(s).valid);
            assertTrue("gson must read it as an object too: " + s, new JsonParser().parse(s).isJsonObject());
        }
    }

    @Test
    public void anythingTheReceiverCouldNotParseAsAnObjectIsRejected() {
        for (String s : INVALID) {
            assertFalse(s, JsonObjectCheck.of(s).valid);
        }
        assertFalse(JsonObjectCheck.of(null).valid);
    }

    @Test
    public void topLevelServiceAndKeyAreCapturedDecoded() {
        JsonObjectCheck check = JsonObjectCheck.of(
                "{\"service\":\"MirthConnectService\",\"o\":{\"key\":\"nested\"},\"key\":\"dev\\\"ice\\u0041\"}");
        assertTrue(check.valid);
        assertEquals(3, check.members);
        assertEquals("MirthConnectService", check.service);
        assertEquals("a nested key is not the event's key", "dev\"iceA", check.key);
    }

    @Test
    public void nonStringServiceOrKeyIsNotCaptured() {
        JsonObjectCheck check = JsonObjectCheck.of("{\"service\":42,\"key\":null}");
        assertTrue(check.valid);
        assertNull(check.service);
        assertNull(check.key);
    }

    @Test
    public void memberCountIsZeroForAnEmptyObject() {
        assertEquals(0, JsonObjectCheck.of("{ }").members);
        assertEquals(1, JsonObjectCheck.of("{\"a\":{\"b\":1,\"c\":2}}").members);
    }

    @Test
    public void deepNestingIsRejectedWithoutOverflowingTheStack() {
        StringBuilder ok = new StringBuilder("{\"a\":");
        StringBuilder deep = new StringBuilder("{\"a\":");
        for (int i = 0; i < JsonObjectCheck.MAX_DEPTH - 1; i++) {
            ok.append('[');
        }
        for (int i = 0; i < 100_000; i++) {
            deep.append('[');
        }
        for (int i = 0; i < JsonObjectCheck.MAX_DEPTH - 1; i++) {
            ok.append(']');
        }
        ok.append('}');
        assertTrue(JsonObjectCheck.of(ok.toString()).valid);
        assertFalse(JsonObjectCheck.of(deep.toString()).valid);
    }
}
