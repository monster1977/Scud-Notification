package ru.local.pulsreceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end check through a raw socket, the way curl sends a request:
 * real server, real parser, real guard, real wire format.
 */
public class HttpReceiverTest {
    private HttpReceiver receiver;
    private HttpRequest lastRequest;

    @Before public void startListener() throws Exception {
        // Сеть доверяет себе: любой корректный POST принимает слушатель.
        receiver = new HttpReceiver(request -> { lastRequest = request; return HttpReceiver.Reply.ok(); });
        receiver.start(0); // any free port; port() reports the chosen one
    }

    @After public void stopListener() { receiver.stop(); }

    /** Sends a complete request, half-closes, and returns the whole server response. */
    private String exchange(String request) throws Exception {
        Socket socket = new Socket("127.0.0.1", receiver.port());
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        socket.shutdownOutput();
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) break;
            response.write(b);
        }
        socket.close();
        return response.toString("UTF-8");
    }

    private static String statusLine(String response) {
        int end = response.indexOf("\r\n");
        return end < 0 ? "" : response.substring(0, end);
    }
    private static String body(String response) {
        int end = response.indexOf("\r\n\r\n");
        return end < 0 ? "" : response.substring(end + 4);
    }

    @Test public void postFromTheLocalNetworkIsAccepted() throws Exception {
        String response = exchange("POST /notify?message=hello HTTP/1.1\r\nHost: test\r\nContent-Length: 0\r\n\r\n");
        assertEquals("HTTP/1.1 200 OK", statusLine(response));
        assertTrue(body(response).contains("\"ok\""));
        assertEquals("hello", lastRequest.queryValue("message"));
    }

    @Test public void bodyReachesTheListener() throws Exception {
        // "тело" — 8 bytes in UTF-8; the parser must read it byte-exactly.
        String response = exchange("POST /notify HTTP/1.1\r\nHost: test\r\nContent-Type: text/plain\r\nContent-Length: 8\r\n\r\nтело");
        assertEquals("HTTP/1.1 200 OK", statusLine(response));
        assertEquals("тело", lastRequest.body);
    }

    @Test public void otherMethodsAreRejectedBeforeAnyWork() throws Exception {
        String response = exchange("GET /notify HTTP/1.1\r\nHost: test\r\n\r\n");
        assertEquals("HTTP/1.1 405 Method Not Allowed", statusLine(response));
        assertTrue(body(response).contains("405"));
    }
}
