package ru.local.pulsreceiver;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Parsing rules for one HTTP/1.x request; no Android classes involved. */
public class HttpRequestTest {

    private static HttpRequest parse(String method, String target, String headers, String body) throws Exception {
        String raw = method + " " + target + " HTTP/1.1\r\n" + headers
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
        return HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpProblem expectProblem(String method, String target, String headers, String body) {
        try {
            parse(method, target, headers, body);
        } catch (HttpProblem problem) {
            return problem;
        } catch (Exception other) {
            fail("Expected HttpProblem but got " + other);
        }
        fail("Expected HttpProblem");
        return null;
    }

    @Test public void parsesPostWithQueryHeadersAndBody() throws Exception {
        HttpRequest request = parse("POST", "/notify?token=abc&message=hi+there", "X-Puls-Token: abc\r\n", "hello");
        assertEquals("POST", request.method);
        assertEquals("/notify", request.path);
        assertEquals("hello", request.body);
        assertEquals("hi there", request.queryValue("message"));
        assertEquals("abc", request.queryValue("token"));
        assertEquals("abc", request.header("x-puls-token"));
        assertNull(request.queryValue("missing"));
    }

    @Test public void queryValuesArePercentDecodedAsUtf8() throws Exception {
        HttpRequest request = parse("POST", "/notify?message=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82", "", "");
        assertEquals("Привет", request.queryValue("message"));
    }

    @Test public void headerNamesBecomeLowercase() throws Exception {
        HttpRequest request = parse("POST", "/notify", "X-PULS-TOKEN: Upper\r\n", "");
        assertEquals("Upper", request.header("x-puls-token"));
    }

    @Test public void emptyBodyIsAllowed() throws Exception {
        HttpRequest request = parse("POST", "/notify", "", "");
        assertEquals("", request.body);
        assertEquals("", request.query);
    }

    @Test public void rejectsOtherMethods() {
        HttpProblem problem = expectProblem("GET", "/notify", "", "");
        assertEquals(405, problem.status);
        problem = expectProblem("PUT", "/notify", "", "");
        assertEquals(405, problem.status);
    }

    @Test public void rejectsMalformedRequestLine() {
        assertEquals(400, expectProblem("POST", "notify", "", "").status); // no leading slash
        assertEquals(400, expectProblem("POST", "/notify HTTP/2.0", "", "").status); // unsupported version
    }

    @Test public void rejectsNonNumericContentLength() {
        String raw = "POST /notify HTTP/1.1\r\nContent-Length: 5x\r\n\r\n";
        try {
            HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            fail("Expected rejection of a non-numeric Content-Length");
        } catch (HttpProblem problem) {
            assertEquals(400, problem.status);
        } catch (IOException unexpected) {
            fail("Expected HttpProblem but got " + unexpected);
        }
    }

    @Test public void rejectsDuplicateHeaders() {
        String raw = "POST /notify HTTP/1.1\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n";
        try {
            HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            fail("Expected rejection of a duplicated header");
        } catch (HttpProblem problem) {
            assertEquals(400, problem.status);
        } catch (IOException unexpected) {
            fail("Expected HttpProblem but got " + unexpected);
        }
    }

    @Test public void rejectsBodyOverTheCap() {
        String raw = "POST /notify HTTP/1.1\r\nContent-Length: 70000\r\n\r\n";
        try {
            HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            fail("Expected rejection of an oversized body");
        } catch (HttpProblem problem) {
            assertEquals(413, problem.status);
        } catch (IOException unexpected) {
            fail("Expected HttpProblem but got " + unexpected);
        }
    }

    @Test public void rejectsBodiesShorterThanContentLength() {
        String raw = "POST /notify HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc";
        try {
            HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            fail("Expected rejection of a truncated body");
        } catch (HttpProblem wrong) {
            fail("Expected IOException but got HttpProblem " + wrong.status);
        } catch (IOException expected) { }
    }

    @Test public void prefersSenderNameFromScudHeaderOverOtherSources() throws Exception {
        HttpRequest request = requestWithNameHeaders();
        assertEquals("Петя", request.getName());
    }

    @Test public void readsSenderNameFromPlainHeaderAndDecodesIt() throws Exception {
        HttpRequest request = parse("POST /signal HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "Name: Anna%20Petrova\r\n"
                + "\r\n");
        assertEquals("Anna Petrova", request.getName());
    }

    @Test public void fallsBackToQueryParameterWhenNoNameHeaderIsPresent() throws Exception {
        HttpRequest request = parse("POST /signal?name=%D0%90%D0%BD%D1%8F HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n");
        assertEquals("Аня", request.getName());
    }

    @Test public void reportsNoSenderNameWhenHeaderIsBlankOrAbsent() throws Exception {
        HttpRequest blank = parse("POST /signal HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "Name: \r\n"
                + "\r\n");
        assertNull(blank.getName());

        HttpRequest absent = parse("POST /signal HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n");
        assertNull(absent.getName());
    }

    @Test public void keepsPercentCharacterThatIsNotAnEscapeSequence() throws Exception {
        HttpRequest request = parse("POST /signal HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "Name: 100%\r\n"
                + "\r\n");
        assertEquals("100%", request.getName());
    }

    private static HttpRequest requestWithNameHeaders() throws Exception {
        return parse("POST /signal?name=%D0%90%D0%BD%D1%8F HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "X-Scud-Notification/name: %D0%9F%D0%B5%D1%82%D1%8F\r\n"
                + "Name: %D0%90%D0%BD%D1%8F\r\n"
                + "\r\n");
    }

    private static HttpRequest parse(String raw) throws Exception {
        return HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
