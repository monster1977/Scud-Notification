package ru.local.pulsreceiver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Screen with the port field, start/stop control and the recent signal log. */
public final class MainActivity extends Activity {
    private int BG, INK, MUTED, GREEN, LINE, ACTION, GRAPHITE;
    private ReceiverSettings settings;
    private EditText portField;
    private TextView statusView, addressView, hintView, eventsView, errorView;
    private Button actionButton;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        BG = getColor(R.color.screen_background); INK = getColor(R.color.text_primary);
        MUTED = getColor(R.color.text_secondary); GREEN = getColor(R.color.brand_text);
        LINE = getColor(R.color.surface_line); ACTION = getColor(R.color.brand_action);
        GRAPHITE = getColor(R.color.brand_graphite);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings = new ReceiverSettings(this);
        buildScreen();
        ticker = new Runnable() {
            @Override public void run() {
                renderState();
                main.postDelayed(this, 1000);
            }
        };
        renderState();
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }
    @Override protected void onPause() {
        main.removeCallbacks(ticker);
        super.onPause();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(GRAPHITE);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Приёмник сигналов");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-medium", Typeface.NORMAL));
        header.addView(title);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout control = card();
        statusView = text("", 15, true);
        statusView.setPadding(0, 0, 0, dp(10));
        control.addView(statusView);

        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView portLabel = text("Порт", 13, false);
        portLabel.setTextColor(MUTED);
        portLabel.setPadding(0, dp(10), dp(8), 0);
        portField = new EditText(this);
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portField.setText(String.valueOf(settings.port()));
        portField.setTextSize(15);
        portRow.addView(portLabel);
        portRow.addView(portField, new LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT));
        control.addView(portRow);

        TextView portNote = text("Порт можно изменить до запуска. Сигналом считается любой POST-запрос из локальной сети.", 12, false);
        portNote.setTextColor(MUTED);
        portNote.setPadding(0, dp(8), 0, 0);
        control.addView(portNote);

        TextView addressLabel = text("IP-адрес телефона", 13, false);
        addressLabel.setTextColor(MUTED);
        addressLabel.setPadding(0, dp(10), 0, 0);
        control.addView(addressLabel);
        addressView = text("", 17, true);
        addressView.setTextColor(GREEN);
        addressView.setTextIsSelectable(true);
        control.addView(addressView);

        errorView = text("", 13, false);
        errorView.setTextColor(Color.parseColor("#B3261E"));
        errorView.setVisibility(TextView.GONE);
        errorView.setPadding(0, dp(8), 0, 0);
        control.addView(errorView);

        actionButton = new Button(this);
        actionButton.setTextSize(15);
        actionButton.setTextColor(Color.WHITE);
        actionButton.setBackgroundColor(ACTION);
        actionButton.setOnClickListener(v -> {
            if (ReceiverService.isRunning()) stopListening();
            else startListening();
        });
        control.addView(actionButton);
        body.addView(control);

        LinearLayout hintCard = card();
        hintView = text("", 12, false);
        hintView.setTextColor(MUTED);
        hintCard.addView(hintView);
        body.addView(hintCard);

        LinearLayout eventsCard = card();
        TextView eventsTitle = text("Последние сигналы", 15, true);
        eventsTitle.setPadding(0, 0, 0, dp(6));
        eventsCard.addView(eventsTitle);
        eventsView = text("", 13, false);
        eventsView.setTextColor(MUTED);
        eventsCard.addView(eventsView);
        body.addView(eventsCard);

        setContentView(root);
    }

    private void renderState() {
        if (isFinishing() || isDestroyed()) return;
        boolean running = ReceiverService.isRunning();
        int port = running ? ReceiverService.port() : settings.port();
        statusView.setText(running ? "Слушает POST-запросы на порту " + port : "Приёмник остановлен");
        statusView.setTextColor(running ? GREEN : MUTED);
        actionButton.setText(running ? "Остановить" : "Запустить");
        portField.setEnabled(!running);

        String error = settings.takeLastError();
        errorView.setText(error);
        errorView.setVisibility(error.isEmpty() ? TextView.GONE : TextView.VISIBLE);

        List<String> addresses = localAddresses();
        StringBuilder ips = new StringBuilder();
        for (String ip : addresses) {
            if (ips.length() > 0) ips.append('\n');
            ips.append(ip);
        }
        addressView.setText(ips.length() == 0 ? "адрес не найден — проверьте Wi-Fi" : ips.toString());
        String host = addresses.isEmpty() ? "IP-адрес-телефона" : addresses.get(0);
        String example = "curl -X POST \"http://" + host + ":" + port + "/notify?message=Новая%20задача\"";
        hintView.setText("Телефон слушает локальную сеть. Пример запроса с компьютера или сервера:\n\n"
                + example + "\n\nТекст после message попадёт в журнал сигналов; если его нет — будет взят тело запроса.");

        List<String> events = settings.events();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) lines.append('\n');
            lines.append(events.get(i));
        }
        eventsView.setText(lines.length() == 0 ? "Сигналов ещё не было" : lines.toString());
    }

    private void startListening() {
        int port;
        try { port = Integer.parseInt(portField.getText().toString().trim()); }
        catch (NumberFormatException invalid) { port = -1; }
        if (port < 1024 || port > 65535) {
            errorView.setText("Укажите порт от 1024 до 65535");
            errorView.setVisibility(TextView.VISIBLE);
            return;
        }
        settings.savePort(port);
        Intent intent = new Intent(this, ReceiverService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        main.postDelayed(this::renderState, 400);
    }

    private void stopListening() {
        stopService(new Intent(this, ReceiverService.class));
        main.postDelayed(this::renderState, 400);
    }


    /** Usable IPv4 addresses of the phone, e.g. its Wi-Fi address in the local network. */
    private static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.getAddress().length != 4) continue; // IPv4 only
                    result.add(address.getHostAddress());
                }
            }
        } catch (SocketException unavailable) { }
        return result;
    }

    private TextView text(String value, int size, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(INK);
        if (medium) view.setTypeface(Typeface.create("sans-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), LINE);
        card.setBackground(background);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
