package com.ramsoft.logging;

/**
 * Checks that a rendered log event is one well-formed JSON object (RFC 8259, strict: no raw control
 * characters in strings, nothing after the closing brace), and picks up the top-level {@code service}
 * and {@code key} strings on the way.
 *
 * <p>The log receiver parses the whole batch as one array, so a single malformed entry would make it
 * reject every event in the batch. The layouts are JSON built by string concatenation in Global
 * Scripts and "Check Mirth Health", so this cannot be left to the layout.
 */
final class JsonObjectCheck {

    /** Deeper nesting is reported as malformed rather than risking a stack overflow on a channel thread. */
    static final int MAX_DEPTH = 64;

    private static final Malformed MALFORMED = new Malformed();

    final boolean valid;
    /** Number of top-level members (0 for {@code {}}). */
    final int members;
    final String service;
    final String key;

    private final String s;
    private final int len;
    private int pos;
    private int topMembers;
    private String topService;
    private String topKey;

    private JsonObjectCheck(String s) {
        this.s = s;
        this.len = s.length();
        boolean ok;
        try {
            ws();
            ok = pos < len && s.charAt(pos) == '{';
            if (ok) {
                object(0, true);
                ws();
                ok = pos == len;
            }
        } catch (Malformed e) {
            ok = false;
        }
        this.valid = ok;
        this.members = ok ? topMembers : 0;
        this.service = ok ? topService : null;
        this.key = ok ? topKey : null;
    }

    static JsonObjectCheck of(String text) {
        return new JsonObjectCheck(text == null ? "" : text);
    }

    private void object(int depth, boolean top) {
        if (depth > MAX_DEPTH) {
            throw MALFORMED;
        }
        expect('{');
        ws();
        if (peek('}')) {
            pos++;
            return;
        }
        while (true) {
            ws();
            String name = string(top);
            ws();
            expect(':');
            ws();
            if (top && peek('"') && ("service".equals(name) || "key".equals(name))) {
                String value = string(true);
                if ("service".equals(name)) {
                    topService = value;
                } else {
                    topKey = value;
                }
            } else {
                value(depth);
            }
            if (top) {
                topMembers++;
            }
            ws();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect('}');
            return;
        }
    }

    private void array(int depth) {
        if (depth > MAX_DEPTH) {
            throw MALFORMED;
        }
        expect('[');
        ws();
        if (peek(']')) {
            pos++;
            return;
        }
        while (true) {
            ws();
            value(depth);
            ws();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect(']');
            return;
        }
    }

    private void value(int depth) {
        if (pos >= len) {
            throw MALFORMED;
        }
        switch (s.charAt(pos)) {
            case '{':
                object(depth + 1, false);
                break;
            case '[':
                array(depth + 1);
                break;
            case '"':
                string(false);
                break;
            case 't':
                literal("true");
                break;
            case 'f':
                literal("false");
                break;
            case 'n':
                literal("null");
                break;
            default:
                number();
        }
    }

    /** Parses a string; returns its decoded value only when {@code capture} is set. */
    private String string(boolean capture) {
        expect('"');
        StringBuilder sb = capture ? new StringBuilder() : null;
        while (true) {
            if (pos >= len) {
                throw MALFORMED;
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb == null ? null : sb.toString();
            }
            if (c < 0x20) {
                throw MALFORMED;
            }
            if (c != '\\') {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }
            if (pos >= len) {
                throw MALFORMED;
            }
            char e = s.charAt(pos++);
            char decoded;
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    decoded = e;
                    break;
                case 'b':
                    decoded = '\b';
                    break;
                case 'f':
                    decoded = '\f';
                    break;
                case 'n':
                    decoded = '\n';
                    break;
                case 'r':
                    decoded = '\r';
                    break;
                case 't':
                    decoded = '\t';
                    break;
                case 'u':
                    if (pos + 4 > len) {
                        throw MALFORMED;
                    }
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = hexDigit(s.charAt(pos + i));
                        if (digit < 0) {
                            throw MALFORMED;
                        }
                        cp = cp * 16 + digit;
                    }
                    pos += 4;
                    decoded = (char) cp;
                    break;
                default:
                    throw MALFORMED;
            }
            if (sb != null) {
                sb.append(decoded);
            }
        }
    }

    /** ASCII hex only: Character.digit() also accepts full-width and other Unicode digits, which JSON does not. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private void number() {
        if (peek('-')) {
            pos++;
        }
        if (pos >= len) {
            throw MALFORMED;
        }
        char c = s.charAt(pos);
        if (c == '0') {
            pos++;
        } else if (c >= '1' && c <= '9') {
            digits();
        } else {
            throw MALFORMED;
        }
        if (peek('.')) {
            pos++;
            digits();
        }
        if (peek('e') || peek('E')) {
            pos++;
            if (peek('+') || peek('-')) {
                pos++;
            }
            digits();
        }
    }

    private void digits() {
        int start = pos;
        while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
            pos++;
        }
        if (pos == start) {
            throw MALFORMED;
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) {
            throw MALFORMED;
        }
        pos += word.length();
    }

    private void ws() {
        while (pos < len) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < len && s.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw MALFORMED;
        }
        pos++;
    }

    /** Control flow only: preallocated, no stack trace. */
    private static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed() {
            super(null, null, false, false);
        }
    }
}
