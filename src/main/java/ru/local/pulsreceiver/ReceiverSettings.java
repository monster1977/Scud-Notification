package ru.local.pulsreceiver;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Port, short event log, and one-shot error text for the screen. */
final class ReceiverSettings {
    static final int DEFAULT_PORT = 8765;
    private static final int MAX_EVENTS = 6;

    private final SharedPreferences prefs;
    ReceiverSettings(Context context) { prefs = context.getSharedPreferences("receiver_settings", Context.MODE_PRIVATE); }

    int port() {
        int value = prefs.getInt("port", DEFAULT_PORT);
        return value < 1024 || value > 65535 ? DEFAULT_PORT : value;
    }
    void savePort(int port) { prefs.edit().putInt("port", port).apply(); }

    void logEvent(String line) {
        List<String> events = events();
        events.add(0, line);
        StringBuilder stored = new StringBuilder();
        for (int i = 0; i < events.size() && i < MAX_EVENTS; i++) {
            if (i > 0) stored.append('\n');
            stored.append(events.get(i));
        }
        prefs.edit().putString("events", stored.toString()).apply();
    }
    List<String> events() {
        List<String> result = new ArrayList<>();
        for (String line : prefs.getString("events", "").split("\n"))
            if (!line.isEmpty()) result.add(line);
        return result;
    }

    void setLastError(String message) { prefs.edit().putString("last_error", message).apply(); }
    /** Returns the stored error once and clears it, so the screen does not show stale problems. */
    String takeLastError() {
        String value = prefs.getString("last_error", "");
        if (!value.isEmpty()) prefs.edit().remove("last_error").apply();
        return value;
    }
}
