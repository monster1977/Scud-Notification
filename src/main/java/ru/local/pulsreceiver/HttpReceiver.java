package ru.local.pulsreceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP/1.1 listener for POST signals from the local network.
 * One accept loop hands each connection to a small worker pool and closes it after the reply.
 */
final class HttpReceiver {
    static final int ACCEPT_TIMEOUT_MS = 8000;

    interface SignalListener { Reply handle(HttpRequest request); }

    static final class Reply {
        final int status;
        final String body;
        Reply(int status, String body) { this.status = status; this.body = body; }
        static Reply ok() { return new Reply(200, "{\"status\":\"ok\"}"); }
        static Reply error(int status, String message) {
            String safe = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
            return new Reply(status, "{\"status\":" + status + ",\"message\":\"" + safe + "\"}");
        }
    }

    private final SignalListener listener;
    private ServerSocket server;
    private java.util.concurrent.ExecutorService pool;
    private volatile boolean stopped;

    HttpReceiver(SignalListener listener) { this.listener = listener; }

    /** Binds all interfaces so any host in the local network can reach the port. */
    void start(int port) throws IOException {
        server = new ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"));
        pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        stopped = false;
        Thread acceptor = new Thread(this::acceptLoop, "puls-http-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() { return server == null ? -1 : server.getLocalPort(); }

    void stop() {
        stopped = true;
        try { if (server != null) server.close(); } catch (IOException alreadyClosed) { }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket socket = server.accept();
                pool.execute(() -> serve(socket));
            } catch (IOException stoppedOrTransient) {
                if (stopped) return; // server.close() breaks accept(); a normal shutdown.
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(ACCEPT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            HttpRequest request = null;
            Reply reply = null;
            try {
                request = HttpRequest.parse(in);
            } catch (HttpProblem problem) {
                reply = Reply.error(problem.status, problem.getMessage());
            } catch (IOException disconnected) {
                return; // the client left before completing the request; closing is enough.
            }
            if (request != null) reply = listener.handle(request);
            write(socket.getOutputStream(), reply);
            drain(socket); // let the socket close cleanly instead of resetting the client.
        } catch (IOException ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Reads what the client still has in flight so close() does not send a TCP reset. */
    private static void drain(Socket socket) {
        try {
            socket.setSoTimeout(250);
            InputStream in = socket.getInputStream();
            byte[] junk = new byte[2048];
            while (in.read(junk) >= 0) { }
        } catch (IOException ignored) { }
    }

    private static void write(OutputStream out, Reply reply) throws IOException {
        byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + reply.status + " " + reason(reply.status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 431: return "Request Header Fields Too Large";
            default: return "Error";
        }
    }
}
