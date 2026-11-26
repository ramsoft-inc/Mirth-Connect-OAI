package com.ramsoft.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials in the copy of a log event that is shipped to the log receiver (Loki).
 *
 * <p>Only the shipped copy is changed: the local {@code mirth.log} is written by log4j's own file
 * appender from the original event, so support can still read a token there (for example to replay
 * a call in Postman). Mirth DEBUG output contains live bearer tokens ({@code js-connector: Bearer eyJ…})
 * and API responses that carry the log receiver's {@code subscription-key} (ACK-176 item 6).
 *
 * <p>Works on the rendered JSON entry, where an event's message is itself JSON-escaped, so every
 * pattern also matches the escaped form ({@code \"access_token\":\"…\"}). Masks contain no quote or
 * backslash, so a valid entry stays valid. Patterns only catch known shapes; PHI is not in scope.
 */
final class LogRedactor {

    static final String JWT_MASK = "<redacted-jwt>";
    static final String VALUE_MASK = "<redacted>";

    /** A JWT/JWS/JWE: a base64url JSON header ({@code eyJ}), then at least two more dot-separated parts. */
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_.-]*");

    /**
     * Name endings that mark a credential: {@code ClientSecret}, {@code aesKey}, {@code access_token},
     * {@code Ocp-Apim-Subscription-Key}, {@code password}... A name must END with one, so
     * {@code Clientsecretkeyid} (an id) and {@code Clientsecretendtime} (a date) are kept.
     * {@code Authorization} must be the whole name (or {@code Proxy-Authorization}): as an ending it
     * would also take {@code priorAuthorization}, an insurance field rather than a credential.
     */
    private static final String SECRET_NAME = "(?:[A-Za-z0-9_-]*?(?:secret|password|passwd|pwd|token|aes_?key|api_?key"
            + "|access_?key|private_?key|shared_?key|signing_?key|subscription[-_]?key|connection_?string)"
            + "|(?:proxy-)?authorization)";

    /** A flag rather than a credential, so {@code "ResetPassword":"true"} stays readable. */
    private static final String FLAG = "(?:true|false|null)";

    /**
     * JSON members with a credential name, plain or escaped inside a message: {@code "ClientSecret":"…"}.
     * Values shorter than 4 characters are kept: they are settings such as {@code "…AssocToken": "1"};
     * so are {@code true}, {@code false} and {@code null}.
     * The value repetition is possessive: a greedy repeated group recurses once per character and threw
     * StackOverflowError on a ~1,700-character token (a normal B2C access token) on the channel thread.
     */
    private static final Pattern MEMBER = Pattern.compile(
            "(?i)(\\\\?\"" + SECRET_NAME + "\\\\?\"\\s*:\\s*\\\\?\")(?!" + FLAG + "\\\\?\")"
            + "(?:[^\"\\\\]|\\\\(?!\")){4,}+");

    /**
     * Parameters with a credential name in a URL, form body or connection string. {@code code} only after
     * {@code ?} or {@code &} (Azure Function keys), so text like {@code statusCode=200} is kept.
     */
    private static final Pattern PARAM = Pattern.compile(
            "(?i)((?:(?<![A-Za-z0-9_-])(?:" + SECRET_NAME + "|sig)|[?&]code)=)(?!" + FLAG + "(?:[&;\\s\"'\\\\<>]|$))"
            + "[^&;\\s\"'\\\\<>]+");

    /** {@code Authorization: Basic …} / {@code Bearer …} written as text rather than as a JSON member. */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(authorization[\"\\\\]*\\s*[:=]\\s*[\"\\\\]*(?:basic|bearer)\\s+)[A-Za-z0-9+/=._~-]{8,}");

    private LogRedactor() {
    }

    /** The entry with credentials masked; the same instance when there was nothing to mask. */
    static String redact(String entry) {
        if (entry == null) {
            return null;
        }
        String out = entry;
        out = replace(MEMBER, out, "$1" + VALUE_MASK);
        out = replace(PARAM, out, "$1" + VALUE_MASK);
        out = replace(AUTH_HEADER, out, "$1" + VALUE_MASK);
        out = replace(JWT, out, JWT_MASK);
        return out;
    }

    /** One pass: continues from the first match instead of scanning again with replaceAll(). */
    private static String replace(Pattern pattern, String input, String replacement) {
        Matcher m = pattern.matcher(input);
        if (!m.find()) {
            return input;
        }
        StringBuffer out = new StringBuffer(input.length());
        do {
            m.appendReplacement(out, replacement);
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }
}
