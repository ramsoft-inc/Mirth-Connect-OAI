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
        assertEquals("token=" + LogRedactor.JWT_MASK, LogRedactor.redact("token=" + JWT));
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
