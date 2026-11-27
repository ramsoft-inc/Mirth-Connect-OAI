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

    /** One character of a JSON string value: anything but a quote or backslash, or one whole escape. */
    private static final String PLAIN_CHAR = "(?:[^\"\\\\]|\\\\.)";

    /**
     * One character of a JSON string value that is itself escaped inside a message, where the value ends
     * at {@code \"}: a plain character, an escape of the message ({@code \n}, {@code é}), or an
     * escape of the inner value ({@code \\\"} for a quote, {@code \\\\} for a backslash). Never a bare
     * quote, so a match cannot run past the end of the message it sits in.
     */
    private static final String EMBEDDED_CHAR = "(?:[^\"\\\\]|\\\\[^\"\\\\]|\\\\\\\\(?:\\\\.|[^\"\\\\]))";

    /**
     * JSON members with a credential name: {@code "ClientSecret":"…"}. The value is read escape by escape,
     * so an escaped quote inside it does not end it early and a trailing escaped backslash is not split
     * (which would leave the entry invalid JSON).
     * Values shorter than 4 characters are kept: they are settings such as {@code "…AssocToken": "1"};
     * so are {@code true}, {@code false} and {@code null}.
     * Value repetitions are possessive: a greedy repeated group recurses once per character and threw
     * StackOverflowError on a ~1,700-character token (a normal B2C access token) on the channel thread.
     */
    private static final Pattern MEMBER = Pattern.compile(
            "(?i)(\"" + SECRET_NAME + "\"\\s*:\\s*\")(?!" + FLAG + "\")" + PLAIN_CHAR + "{4,}+");

    /** The same members escaped inside a message: {@code \"ClientSecret\":\"…\"}. */
    private static final Pattern EMBEDDED_MEMBER = Pattern.compile(
            "(?i)(\\\\\"" + SECRET_NAME + "\\\\\"\\s*:\\s*\\\\\")(?!" + FLAG + "\\\\\")" + EMBEDDED_CHAR + "{4,}+");

    /**
     * Parameters with a credential name in a URL, form body or connection string. {@code code} only after
     * {@code ?} or {@code &} (Azure Function keys), so text like {@code statusCode=200} is kept.
     * A connection-string value may be quoted ({@code Password="…"}, escaped inside a message, or
     * {@code Password='…'}) and is then masked with its quotes. An unquoted value that contains spaces is
     * masked up to its {@code ;}; any other unquoted value ends at the first space or delimiter. An escaped
     * backslash ({@code \\}) is part of an unquoted value.
     */
    private static final Pattern PARAM = Pattern.compile(
            "(?i)((?:(?<![A-Za-z0-9_-])(?:" + SECRET_NAME + "|sig)|[?&]code)=)(?:"
            + "\\\\\"" + EMBEDDED_CHAR + "*+\\\\\""
            + "|'(?:[^'\"\\\\]|\\\\.|'')*+'"
            + "|(?!" + FLAG + "(?:[&;\\s\"'\\\\<>]|$))(?:"
            + "(?:[^&;\"'\\\\<>\\r\\n]|\\\\\\\\)++(?=;)"
            + "|(?:[^&;\\s\"'\\\\<>]|\\\\\\\\)++))");

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
        out = replace(EMBEDDED_MEMBER, out, "$1" + VALUE_MASK);
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
