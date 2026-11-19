package ru.local.pulsreceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Strict parser for one HTTP/1.x request. Only POST to an origin-form path is accepted,
 * headers are capped, and the body is read exactly as Content-Length bytes.
 */
final class HttpRequest {
    static final int MAX_LINE_BYTES = 4096;
    static final int MAX_HEAD_BYTES = 16 * 1024;
    static final int MAX_BODY_BYTES = 64 * 1024;

    final String method, path, query, body;
    final Map<String, String> headers;

    private HttpRequest(String method, String path, String query, Map<String, String> headers, String body) {
        this.method = method; this.path = path; this.query = query; this.headers = headers; this.body = body;
    }

    /** Header value by lower-case name; header names are stored lower-cased. */
    String header(String name) { return headers.get(name); }

    /**
     * Returns the sender name: first the X-Scud-Notification/name header, then the name header,
     * then the name query parameter. Header values are percent-decoded, because the request line
     * and headers may only carry ASCII bytes. Null when the name is absent.
     */
    String getName() {
        String value = decoded(headerValue("x-scud-notification/name"));
        if (value == null) value = decoded(headerValue("name"));
        if (value == null) value = queryValue("name");
        return value;
    }

    /** Returns the token from headers: first X-Scud-Notification/token, then token. */
    String getToken() {
        String value = decoded(headerValue("x-scud-notification/token"));
        if (value == null) value = decoded(headerValue("token"));
        return value;
    }

    private String headerValue(String key) {
        return headers.isEmpty() ? null : headers.get(key);
    }

    /**
     * Percent-decodes header values as UTF-8; malformed escapes are kept as-is. Blank values are
     * treated as absent. Query values are already decoded by queryValue, so this applies to headers
     * only. URLDecoder also maps '+' to a space, so senders should encode spaces as %20.
     */
    private static String decoded(String value) {
        if (value == null || value.isEmpty()) return null;
        if (!value.contains("%")) return value;
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception invalidEncoding) {
            return value;
        }
    }

    /** First value of a query parameter, percent-decoded; null when absent. */
    String queryValue(String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(name)) continue;
            try { return URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), "UTF-8"); }
            catch (Exception invalidEncoding) { return null; }
        }
        return null;
    }

    static HttpRequest parse(InputStream in) throws IOException, HttpProblem {
        String line = readLine(in, MAX_REQUEST_LINE_STATE);
        if (line == null) throw new HttpProblem(400, "Empty request");
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[1].startsWith("/") || !parts[2].startsWith("HTTP/1."))
            throw new HttpProblem(400, "Expected POST /path HTTP/1.x");
        if (!parts[0].equals("POST")) throw new HttpProblem(405, "Only POST is accepted");
        String target = parts[1];
        if (target.indexOf('#') >= 0) throw new HttpProblem(400, "URL fragment is not allowed");
        String path = target, query = "";
        int mark = target.indexOf('?');
        if (mark >= 0) { path = target.substring(0, mark); query = target.substring(mark + 1); }

        Map<String, String> headers = new HashMap<>();
        int headBytes = line.length() + 2;
        while (true) {
            line = readLine(in, headBytes);
            if (line == null) throw new HttpProblem(400, "Request ended before the empty header line");
            if (line.isEmpty()) break;
            headBytes += line.length() + 2;
            if (headBytes > MAX_HEAD_BYTES) throw new HttpProblem(431, "Headers are too large");
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HttpProblem(400, "Malformed header line");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (headers.put(name, line.substring(colon + 1).trim()) != null)
                throw new HttpProblem(400, "Duplicate header: " + name);
        }

        int length = 0;
        String rawLength = headers.get("content-length");
        if (rawLength != null) {
            for (int i = 0; i < rawLength.length(); i++)
                if (rawLength.charAt(i) < '0' || rawLength.charAt(i) > '9') throw new HttpProblem(400, "Content-Length must be a number");
            try { length = Integer.parseInt(rawLength); }
            catch (NumberFormatException tooLong) { throw new HttpProblem(400, "Content-Length must be a number"); }
            if (length > MAX_BODY_BYTES) throw new HttpProblem(413, "Body is larger than 64 KiB");
        }
        byte[] bodyBytes = readFully(in, length);
        return new HttpRequest(parts[0], path, query, headers, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    /** Sentinel headBytes value meaning "the request line itself". */
    private static final int MAX_REQUEST_LINE_STATE = -1;

    private static String readLine(InputStream in, int headBytes) throws IOException, HttpProblem {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (headBytes == MAX_REQUEST_LINE_STATE && line.size() == 0) return null;
                throw new IOException("Connection closed before the line end");
            }
            if (b == '\n') return new String(line.toByteArray(), StandardCharsets.US_ASCII);
            if (b == '\r') {
                int next = in.read();
                if (next != '\n') throw new IOException("Bare CR inside a header");
                return new String(line.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (b < 0x20 || b > 0x7E) throw new IOException("Non-ASCII byte in the request line or headers");
            line.write(b);
            if (line.size() > MAX_LINE_BYTES) throw new HttpProblem(431, "A request line is longer than 4 KiB");
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int got = in.read(data, read, length - read);
            if (got < 0) throw new IOException("Body is shorter than Content-Length");
            read += got;
        }
        return data;
    }
}
