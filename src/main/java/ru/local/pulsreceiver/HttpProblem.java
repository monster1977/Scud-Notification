package ru.local.pulsreceiver;

/** A rejected HTTP request with the status the client should receive. */
final class HttpProblem extends Exception {
    final int status;
    HttpProblem(int status, String message) { super(message); this.status = status; }
}
