package com.ramsoft.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogRedactorTest {

    // A syntactically real JWT (header {"alg":"RS256","typ":"JWT"}), not a live token.
    private static final String JWT = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9" + "." + "eyJzdWIiOiIxMjM0NSIsImV4cCI6MTd9" + "." + "c2lnbmF0dXJlLXZhbHVl";
    private static final String KEY = "306e0123456789abcdef0123456789ab";

    @Test
    public void aBearerTokenLineAsMirthLogsItIsMasked() {
        String out = LogRedactor.redact("js-connector: Bearer " + JWT);
        assertEquals("js-connector: Bearer " + LogRedactor.JWT_MASK, out);
    }

    @Test
    public void aJwtIsMaskedWhereverItAppears() {
        assertEquals("jwt: " + LogRedactor.JWT_MASK, LogRedactor.redact("jwt: " + JWT));
        assertEquals("[" + LogRedactor.JWT_MASK + "]", LogRedactor.redact("[" + JWT + "]"));
        // An unsigned JWT ("alg":"none") ends with a dot and an empty signature.
        String unsigned = "eyJhbGciOiJub25lIn0" + "." + "eyJzdWIiOiIxMjM0NSJ9" + ".";
        assertEquals("x " + LogRedactor.JWT_MASK + " y", LogRedactor.redact("x " + unsigned + " y"));
    }

    @Test
    public void theLogReceiverKeyInsideALoggedDeviceResponseIsMasked() {
        // What COMMON_UTIL_CallAPIGET logs, as it appears JSON-escaped inside the shipped entry's message.
        String message = "COMMON_UTIL_CallAPIGET: Response = {\\\"resourceType\\\":\\\"Device\\\",\\\"logEndpoint\\\":"
                + "\\\"https://pre-us01-apim.azure-api.net/logreceiversub/LogReceiver?subscription-key=" + KEY + "\\\"}";
        String out = LogRedactor.redact(message);
        assertFalse(out, out.contains(KEY));
        assertTrue(out, out.contains("LogReceiver?subscription-key=<redacted>\\\"}"));
    }

    @Test
    public void credentialParametersInUrlsFormsAndConnectionStringsAreMasked() {
        assertEquals("GET /x?subscription-key=<redacted>&page=2", LogRedactor.redact("GET /x?subscription-key=" + KEY + "&page=2"));
        assertEquals("https://fn.example/api/LogReceiver?code=<redacted>&clientId=7",
                LogRedactor.redact("https://fn.example/api/LogReceiver?code=AbC/dEf+gh==&clientId=7"));
        assertEquals("grant_type=client_credentials&client_secret=<redacted>",
                LogRedactor.redact("grant_type=client_credentials&client_secret=s3cr3t~value"));
        assertEquals("Server=db;User=mirth;Password=<redacted>;Encrypt=true",
                LogRedactor.redact("Server=db;User=mirth;Password=p@ss;Encrypt=true"));
        assertEquals("blob?sv=2022-11-02&sig=<redacted>&se=2026", LogRedactor.redact("blob?sv=2022-11-02&sig=AbC%2BdEf%3D&se=2026"));
    }

    @Test
    public void credentialJsonMembersAreMaskedPlainAndEscaped() {
        assertEquals("{\"access_token\":\"<redacted>\",\"expires_in\":3599}",
                LogRedactor.redact("{\"access_token\":\"opaque-token-123\",\"expires_in\":3599}"));
        assertEquals("headers {\\\"Ocp-Apim-Subscription-Key\\\":\\\"<redacted>\\\",\\\"Accept\\\":\\\"json\\\"}",
                LogRedactor.redact("headers {\\\"Ocp-Apim-Subscription-Key\\\":\\\"" + KEY + "\\\",\\\"Accept\\\":\\\"json\\\"}"));
        assertEquals("{\\\"password\\\" : \\\"<redacted>\\\"}", LogRedactor.redact("{\\\"password\\\" : \\\"hunter2\\\"}"));
    }

    @Test
    public void theDeviceConfigResponseKeepsItsSettingsButLosesItsSecrets() {
        // The shape COMMON_UTIL_CallAPIGET_nonResource logs for /fhir/Device/Config, escaped inside a message.
        String message = "Response = {\\n  \\\"ClientID\\\": \\\"7e2e445a-28e0-4dc9-a48a-863d429a4ee0\\\",\\n"
                + "  \\\"ClientSecret\\\": \\\"Abc8Q~" + "exampleSecretValue_123\\\",\\n"
                + "  \\\"Clientsecretkeyid\\\": \\\"739965a2-e0df-4839-8b4a-7e011b1d3d00\\\",\\n"
                + "  \\\"Clientsecretendtime\\\": \\\"7/9/2028 4:59:41 PM\\\",\\n"
                + "  \\\"EventReportOnSameAssocToken\\\": \\\"1\\\",\\n"
                + "  \\\"cacheWebServer\\\": {\\n    \\\"aesKey\\\": \\\"QmFzZTY0" + "RXhhbXBsZUFlc0tleVZhbHVlMTIzNDU2Nzg=\\\"\\n  }\\n}";
        String out = LogRedactor.redact(message);
        assertTrue(out, out.contains("\\\"ClientSecret\\\": \\\"<redacted>\\\""));
        assertTrue(out, out.contains("\\\"aesKey\\\": \\\"<redacted>\\\""));
        assertTrue("an id is not a secret", out.contains("\\\"Clientsecretkeyid\\\": \\\"739965a2-e0df-4839-8b4a-7e011b1d3d00\\\""));
        assertTrue("a date is not a secret", out.contains("\\\"Clientsecretendtime\\\": \\\"7/9/2028 4:59:41 PM\\\""));
        assertTrue("a one-character setting is not a secret", out.contains("\\\"EventReportOnSameAssocToken\\\": \\\"1\\\""));
        assertTrue(out, out.contains("7e2e445a-28e0-4dc9-a48a-863d429a4ee0"));
        assertFalse(out, out.contains("exampleSecretValue"));
        assertFalse(out, out.contains("QmFzZTY0"));
    }

    @Test
    public void secretNamedParametersAreMaskedByTheirNameEnding() {
        assertEquals("u?pageToken=<redacted>&size=10", LogRedactor.redact("u?pageToken=abcdef123456&size=10"));
        assertEquals("cfg apiKey=<redacted> aesKey=<redacted>", LogRedactor.redact("cfg apiKey=k-123456 aesKey=Zm9vYmFy"));
        assertEquals("tokenizer=on", LogRedactor.redact("tokenizer=on"));
    }

    @Test
    public void flagValuesOfSecretNamedFieldsStayReadable() {
        String[] untouched = {
            "{\"ResetPassword\":\"true\",\"HasToken\":\"FALSE\",\"apiKey\":\"null\"}",
            "{\\\"ResetPassword\\\":\\\"true\\\"}",
            "u?resetPassword=true&rememberToken=false",
        };
        for (String s : untouched) {
            assertSame(s, s, LogRedactor.redact(s));
        }
        // Only the exact word: a value that merely starts with one is still masked.
        assertEquals("{\"password\":\"<redacted>\"}", LogRedactor.redact("{\"password\":\"trueblue7\"}"));
        assertEquals("u?password=<redacted>&a=1", LogRedactor.redact("u?password=nullable9&a=1"));
    }

    @Test
    public void authorizationIsMaskedOnlyAsTheWholeHeaderName() {
        String prior = "{\"priorAuthorization\":\"Required\",\"priorAuthorizationNumber\":\"PA-778812\"}";
        assertSame(prior, LogRedactor.redact(prior));
        assertEquals("{\"Authorization\":\"<redacted>\"}", LogRedactor.redact("{\"Authorization\":\"SharedKey acct:c2lnbmF0dXJl\"}"));
        assertEquals("{\\\"Proxy-Authorization\\\":\\\"<redacted>\\\"}",
                LogRedactor.redact("{\\\"Proxy-Authorization\\\":\\\"Negotiate YIIBhgYGKwYBBQUCoIIBejCCAXag\\\"}"));
    }

    @Test(timeout = 20000)
    public void veryLongCredentialValuesAreMaskedWithoutOverflowingASmallStack() throws Exception {
        // B2C tokens and Authorization values are commonly 1,500-2,500 characters; a greedy repeated group
        // overflowed at ~1,720 on a 1 MB stack. 100,000 on a 256 KB stack leaves no doubt.
        StringBuilder sb = new StringBuilder(100_000);
        for (int i = 0; i < 100_000; i++) {
            sb.append((char) ('a' + i % 26));
        }
        final String big = sb.toString();
        final String bigJwt = "eyJhbGciOiJSUzI1NiJ9" + "." + big + "." + "c2lnbmF0dXJl";
        final String[] inputs = {
            "{\\\"access_token\\\":\\\"" + big + "\\\"}",
            "{\"Authorization\":\"Bearer " + big + "\"}",
            "GET /x?subscription-key=" + big + "&a=1",
            "Authorization: Basic " + big,
            "jwt " + bigJwt,
        };
        final String[] outputs = new String[inputs.length];
        final Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                for (int i = 0; i < inputs.length; i++) {
                    outputs[i] = LogRedactor.redact(inputs[i]);
                }
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "small-stack", 256 * 1024);
        t.start();
        t.join();
        if (failure[0] != null) {
            throw new AssertionError("redaction failed on a long value: " + failure[0], failure[0]);
        }
        assertEquals("{\\\"access_token\\\":\\\"<redacted>\\\"}", outputs[0]);
        assertEquals("{\"Authorization\":\"<redacted>\"}", outputs[1]);
        assertEquals("GET /x?subscription-key=<redacted>&a=1", outputs[2]);
        assertEquals("Authorization: Basic <redacted>", outputs[3]);
        assertEquals("jwt " + LogRedactor.JWT_MASK, outputs[4]);
    }

    @Test
    public void basicAuthorizationHeadersAreMasked() {
        assertEquals("Authorization: Basic <redacted>", LogRedactor.redact("Authorization: Basic dXNlcjpwYXNzd29yZA=="));
    }

    @Test
    public void ordinaryLogTextIsLeftAlone() {
        String[] untouched = {
            "HTTP_Call - resolved timeout (s): 60",
            "Outbound OAI Polling - statusCode=200, error code=5, errorCode=7",
            "StudyInstanceUID=1.2.840.113619.2.55.3.604688119.868.1249343483.504",
            // JWT-shaped only if "eyJ" may start mid-word: the boundary keeps this ordinary text.
            "heyJudeFoo.barbazqux.end and eyJ alone",
            "password reset requested for user 42",
            "subscription-keys rotated",
            "{\"key\":\"pre-us01-MIRTH-INSTANCE-7476327-SERGEHOSPITAL\"}",
        };
        for (String s : untouched) {
            assertSame(s, s, LogRedactor.redact(s));
        }
        assertEquals(null, LogRedactor.redact(null));
    }

    @Test
    public void aRedactedEntryIsStillOneValidJsonObject() {
        String entry = "{\"message\":\"Bearer " + JWT + " then Response = {\\\"access_token\\\":\\\"" + JWT
                + "\\\",\\\"logEndpoint\\\":\\\"https://h/LogReceiver?subscription-key=" + KEY + "\\\"}\",\"exception\":\"\"}";
        assertTrue(JsonObjectCheck.of(entry).valid);
        String out = LogRedactor.redact(entry);
        assertTrue(out, JsonObjectCheck.of(out).valid);
        assertFalse(out, out.contains("eyJ"));
        assertFalse(out, out.contains(KEY));
    }
}
