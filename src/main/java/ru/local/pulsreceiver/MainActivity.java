package ru.local.pulsreceiver;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Screen with the port field, start/stop control and the recent signal log. */
public final class MainActivity extends Activity {
    private int BG, INK, MUTED, GREEN, LINE, ACTION, GRAPHITE;
    private ReceiverSettings settings;
    private EditText portField;
    private TextView statusView, addressView, logView, nameView, speechView;
    private Button actionButton;
    private String cachedAddress;
    private String senderName;
    private String pendingTestName;
    private int pendingTestPort;
    private long lastLogStamp;
    private int lastLogSize;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            renderState();
            ticker.postDelayed(this, 1000);
        }
    };

    /** The service pushes every received name here, from its HTTP worker thread. */
    private final BroadcastReceiver nameReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            showName(intent.getStringExtra(ReceiverService.EXTRA_NAME));
        }
    };

    private final Runnable testRequest = new Runnable() {
        @Override public void run() {
            performTestRequest(pendingTestName, pendingTestPort);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = new ReceiverSettings(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildPalette();
        buildScreen();
        registerNameReceiver();
        ticker.post(refresher);
        if (ReceiverService.isRunning()) startService(new Intent(this, ReceiverService.class));
    }

    @Override protected void onResume() { super.onResume(); ticker.post(refresher); refreshAddress(); }
    @Override protected void onPause() { super.onPause(); ticker.removeCallbacks(refresher); }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacks(refresher);
        unregisterReceiver(nameReceiver);
        super.onDestroy();
    }

    /**
     * The port field, the start button and the recent signal log share one card,
     * with the name of the last sender below the port, and the status line below the card.
     * The window is not scrollable: only the log text scrolls, with the title fixed at the top.
     */
    private void buildScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        int pad = dp(22);
        screen.setPadding(pad, dp(30), pad, 0);

        TextView title = text(34, INK, Typeface.NORMAL);
        title.setText("Приёмник сигналов");
        title.setLetterSpacing(-0.03f);
        screen.addView(title, matchWrap());

        View gapSmall = spacer(12);
        screen.addView(gapSmall);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(0x14, 0xff, 0xff, 0xff));
        cardBg.setCornerRadius(dp(18));
        cardBg.setStroke(dp(1), LINE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(pad, pad, pad, pad);

        portField = new EditText(this);
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portField.setMaxLines(1);
        portField.setSingleLine(true);
        portField.setText(String.valueOf(settings.port()));
        portField.setTextColor(INK);
        portField.setHintTextColor(MUTED);
        portField.setBackgroundColor(Color.TRANSPARENT);
        portField.setHint("Порт");
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(Color.argb(0x0a, 0xff, 0xff, 0xff));
        fieldBg.setCornerRadius(dp(10));
        fieldBg.setStroke(dp(1), LINE);
        GradientDrawable fieldFocus = new GradientDrawable();
        fieldFocus.setColor(Color.argb(0x0a, 0xff, 0xff, 0xff));
        fieldFocus.setCornerRadius(dp(10));
        fieldFocus.setStroke(dp(2), ACTION);
        android.graphics.drawable.StateListDrawable fieldStates = new android.graphics.drawable.StateListDrawable();
        fieldStates.addState(new int[] { android.R.attr.state_focused }, fieldFocus);
        fieldStates.addState(new int[] { }, fieldBg);
        portField.setBackground(fieldStates);
        card.addView(portField, matchWrap());

        nameView = text(20, INK, Typeface.NORMAL);
        nameView.setLetterSpacing(-0.01f);
        nameView.setVisibility(View.GONE);
        LinearLayout.LayoutParams nameRow = matchWrap();
        nameRow.topMargin = dp(12);
        card.addView(nameView, nameRow);

        actionButton = new Button(this);
        actionButton.setAllCaps(false);
        actionButton.setTextColor(INK);
        actionButton.setTextSize(20);
        actionButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        actionButton.setBackgroundColor(Color.TRANSPARENT);
        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setColor(Color.argb(0x28, 0xff, 0xff, 0xff));
        pressedBg.setCornerRadius(dp(10));
        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.argb(0x1e, 0xff, 0xff, 0xff));
        normalBg.setCornerRadius(dp(10));
        android.graphics.drawable.StateListDrawable buttonBg = new android.graphics.drawable.StateListDrawable();
        buttonBg.addState(new int[] { android.R.attr.state_pressed }, pressedBg);
        buttonBg.addState(new int[] { }, normalBg);
        actionButton.setBackground(buttonBg);
        LinearLayout.LayoutParams buttonRow = matchWrap();
        buttonRow.topMargin = dp(12);
        card.addView(actionButton, buttonRow);

        TextView testButton = text(17, MUTED, Typeface.NORMAL);
        testButton.setText("Отправить тестовый сигнал");
        testButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        testButton.setPadding(0, dp(12), 0, dp(12));
        testButton.setOnClickListener(v -> sendTestSignal());
        LinearLayout.LayoutParams testRow = matchWrap();
        testRow.topMargin = dp(4);
        card.addView(testButton, testRow);

        speechView = text(14, MUTED, Typeface.NORMAL);
        card.addView(speechView, matchWrap());
        TextView voiceSettings = text(16, MUTED, Typeface.NORMAL);
        voiceSettings.setText("Установить русский офлайн-голос");
        voiceSettings.setPadding(0, dp(8), 0, dp(8));
        voiceSettings.setOnClickListener(v -> openVoiceSettings());
        card.addView(voiceSettings, matchWrap());
        screen.addView(card, matchWrap());

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.height = dp(1);
        screen.addView(divider, rowParams);

        statusView = text(20, MUTED, Typeface.NORMAL);
        statusView.setLetterSpacing(-0.01f);
        screen.addView(statusView, matchWrap());

        addressView = text(17, MUTED, Typeface.NORMAL);
        screen.addView(addressView, matchWrap());

        View gap = spacer(16);
        screen.addView(gap);

        View line = new View(this);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lineParams = matchWrap();
        lineParams.height = dp(1);
        screen.addView(line, lineParams);

        TextView logTitle = text(13, MUTED, Typeface.NORMAL);
        logTitle.setText("ПОСЛЕДНИЕ СИГНАЛЫ");
        logTitle.setLetterSpacing(0.14f);
        logTitle.setPadding(0, dp(12), 0, dp(8));
        screen.addView(logTitle, matchWrap());

        logView = text(17, MUTED, Typeface.NORMAL);
        logView.setLineSpacing(dp(4), 1f);
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(logView);
        screen.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(screen);
    }

    private void startReceiver() {
        int port = parsePort();
        if (port < 1024 || port > 65535) {
            statusView.setText("Укажите порт от 1024 до 65535");
            return;
        }
        settings.savePort(port);
        portField.setText(String.valueOf(port));
        Intent intent = new Intent(this, ReceiverService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void openVoiceSettings() {
        try {
            startActivity(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .setPackage(NameSpeaker.GOOGLE_ENGINE));
        } catch (android.content.ActivityNotFoundException unavailable) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "market://details?id=" + NameSpeaker.GOOGLE_ENGINE)));
            } catch (android.content.ActivityNotFoundException noStore) {
                new android.app.AlertDialog.Builder(this).setTitle("Офлайн-озвучивание")
                        .setMessage("Установите синтезатор речи Google и загрузите русский голос в его настройках. Затем перезапустите приёмник.")
                        .setPositiveButton("Понятно", null).show();
            }
        }
    }

    private void stopReceiver() {
        Intent intent = new Intent(this, ReceiverService.class);
        intent.setAction(ReceiverService.ACTION_STOP);
        startService(intent);
    }

    /** The name arrives through the service broadcast, so the ticker never touches it. */
    private void showName(String name) {
        if (name == null || name.trim().isEmpty()) {
            senderName = null;
            nameView.setVisibility(View.GONE);
            return;
        }
        senderName = name.trim();
        nameView.setText("Отправитель: " + senderName);
        nameView.setVisibility(View.VISIBLE);
    }

    private String currentName() {
        return senderName;
    }

    /** Sends one POST request to the local receiver, with the sender name in a header. */
    private void sendTestSignal() {
        int port = parsePort();
        if (port < 1024 || port > 65535) {
            statusView.setText("Укажите порт от 1024 до 65535");
            return;
        }
        String name = currentName();
        pendingTestName = name == null ? "Тестовый отправитель" : name;
        pendingTestPort = port;
        statusView.setText("Отправляю тестовый сигнал...");
        ticker.removeCallbacks(testRequest);
        new Thread(testRequest).start();
    }

    private void performTestRequest(String name, int port) {
        HttpURLConnection conn = null;
        try {
            String target = "http://127.0.0.1:" + port + "/?message=" + encoded("тестовый сигнал");
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setFixedLengthStreamingMode(0);
            conn.setRequestProperty("X-Scud-Notification/name", encoded(name));
            conn.connect();
            final int code = conn.getResponseCode();
            final String body = readBody(conn);
            ticker.post(() -> {
                if (code >= 200 && code < 300) statusView.setText("Тестовый сигнал отправлен · ответ " + body);
                else statusView.setText("Тестовый сигнал отклонён · код " + code);
            });
        } catch (IOException | NumberFormatException failure) {
            final String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            ticker.post(() -> statusView.setText("Не удалось отправить сигнал: " + reason));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Header and query values travel as US-ASCII, so anything else goes through percent-encoding. */
    private static String encoded(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (IOException impossible) {
            return value;
        }
    }

    private String readBody(HttpURLConnection conn) {
        InputStream in = null;
        try { in = conn.getInputStream(); }
        catch (IOException fallback) { in = conn.getErrorStream(); }
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        try {
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (IOException stopped) {
            // A short answer is still an answer.
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private int parsePort() {
        try { return Integer.parseInt(portField.getText().toString().trim()); }
        catch (NumberFormatException failure) { return -1; }
    }

    private void renderState() {
        int port = settings.port();
        boolean active = ReceiverService.isRunning();
        speechView.setText(ReceiverService.speechStatus());
        actionButton.setText(active ? "Остановить приёмник" : "Запустить приёмник");
        actionButton.setOnClickListener(v -> {
            if (ReceiverService.isRunning()) stopReceiver();
            else startReceiver();
        });
        if (active) statusView.setText("Приёмник запущен · порт " + port + " · ожидание сигналов");
        else statusView.setText("Приёмник остановлен · порт " + port);
        List<String> events = settings.events();
        if (events.size() != lastLogSize || events.isEmpty() || System.currentTimeMillis() - lastLogStamp > 4000) {
            lastLogSize = events.size();
            lastLogStamp = System.currentTimeMillis();
            if (events.isEmpty()) logView.setText("Пока сигналов не было");
            else logView.setText(join(events));
        }
        if (cachedAddress == null) refreshAddress();
        addressView.setText("IP-адрес телефона: " + (cachedAddress == null ? "поиск..." : cachedAddress));
    }

    private void refreshAddress() {
        new Thread(() -> {
            final String address = localIpAddress();
            ticker.post(() -> {
                cachedAddress = address;
                addressView.setText("IP-адрес телефона: " + (address == null ? "поиск..." : address));
            });
        }).start();
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (out.length() > 0) out.append('\n');
            out.append(lines.get(i));
        }
        return out.toString();
    }

    private static String localIpAddress() {
        try {
            List<String> candidates = new ArrayList<>();
            for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                 interfaces != null && interfaces.hasMoreElements(); ) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (Enumeration<InetAddress> addresses = nic.getInetAddresses();
                     addresses != null && addresses.hasMoreElements(); ) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.contains("%") || host.contains(":")) continue;
                    if (host.startsWith("169.254.")) continue;
                    candidates.add(host);
                }
            }
            for (String candidate : candidates) {
                if (candidate.startsWith("10.") || candidate.startsWith("192.168.")) return candidate;
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (SocketException failure) {
            return null;
        }
    }

    /** RECEIVER_NOT_EXPORTED exists only from API 33; the two-argument call below is the older equivalent. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerNameReceiver() {
        IntentFilter filter = new IntentFilter(ReceiverService.ACTION_NAME_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(nameReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(nameReceiver, filter);
    }

    private void buildPalette() {
        BG = Color.rgb(18, 18, 20);
        INK = Color.rgb(244, 244, 245);
        MUTED = Color.rgb(146, 148, 152);
        GREEN = Color.rgb(46, 204, 113);
        LINE = Color.argb(0x2a, 0xff, 0xff, 0xff);
        ACTION = Color.rgb(255, 214, 10);
        GRAPHITE = Color.rgb(30, 30, 33);
    }

    private TextView text(int size, int color, int typeface) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", typeface));
        return view;
    }

    private View spacer(int dp) {
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(dp)));
        return space;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
