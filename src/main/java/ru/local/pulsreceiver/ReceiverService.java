package ru.local.pulsreceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Keeps the HTTP listener alive while the receiver is on.
 * The service is started and stopped only from the application screen and shows an ongoing notification.
 */
public final class ReceiverService extends Service {
    static final String ACTION_STOP = "ru.local.pulsreceiver.action.STOP";
    static final String ACTION_NAME_UPDATE = "ru.local.pulsreceiver.action.NAME_UPDATE";
    static final String EXTRA_NAME = "ru.local.pulsreceiver.extra.NAME";
    private static final int NOTIFICATION_ID = 1;
    private static final long WAKE_LOCK_MS = 24 * 60 * 60 * 1000L;

    private static volatile boolean running;
    private static volatile int listeningPort = -1;

    private HttpReceiver receiver;
    private AlertPlayer player;
    private NameSpeaker speaker;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile String speechStatus = "Озвучивание выключено";
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return running; }
    public static int port() { return listeningPort; }
    public static String speechStatus() { return speechStatus; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (receiver != null) return START_STICKY;

        ReceiverSettings settings = new ReceiverSettings(this);
        int port = settings.port();
        player = new AlertPlayer();
        speaker = new NameSpeaker(this, value -> speechStatus = value, () -> player.play(this));
        // Сеть под контролем владельца: любой POST из локальной сети считается сигналом.
        HttpReceiver started = new HttpReceiver(request -> {
            String name = request.speechName();
            String message = request.queryValue("message");
            if (message == null || message.trim().isEmpty()) message = request.body;
            settings.logEvent(eventLine(name, message));
            main.post(() -> {
                if (speaker == null) return;
                if (name == null) player.play(ReceiverService.this);
                else {
                    player.stop();
                    player.vibrate(ReceiverService.this);
                    speaker.speak(name);
                    sendNameToMainActivity(name);
                }
            });
            return HttpReceiver.Reply.ok();
        });
        try {
            started.start(port);
        } catch (IOException bindFailed) {
            settings.setLastError("Не удалось слушать порт " + port + ": " + describe(bindFailed));
            stopSelf();
            return START_NOT_STICKY;
        }
        receiver = started;
        listeningPort = started.port();
        running = true;
        startForeground(NOTIFICATION_ID, buildNotification(listeningPort));
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "PulsReceiver:listen");
        wakeLock.acquire(WAKE_LOCK_MS); // keeps the CPU able to answer requests; auto-releases after 24 h
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (receiver != null) receiver.stop();
        receiver = null;
        main.removeCallbacksAndMessages(null);
        if (speaker != null) speaker.close();
        speaker = null;
        speechStatus = "Озвучивание выключено";
        if (player != null) player.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        running = false;
        listeningPort = -1;
        super.onDestroy();
    }

    private static String eventLine(String name, String message) {
        String text = message == null ? "" : message.trim().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isEmpty()) text = "Сигнал без текста";
        if (text.length() > 160) text = text.substring(0, 160) + "…";
        String sender = name == null ? "" : name.trim();
        String from = sender.isEmpty() ? "" : " от " + sender;
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + from + ": " + text;
    }

    private static String describe(IOException failure) {
        if (failure instanceof java.net.BindException)
            return "порт занят другим приложением. Смените порт и запустите снова";
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    private Notification buildNotification(int port) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 24) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openScreen = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        NotificationManager manager = getSystemService(NotificationManager.class);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("signals", "Сигналы", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, "signals");
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("Приёмник сигналов")
                .setContentText("Слушает POST-запросы на порту " + port)
                .setContentIntent(openScreen)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        return builder.build();
    }

    /**
     * Sends the sender name to the open screen. MainActivity listens for it while it is visible.
     */
    private void sendNameToMainActivity(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        sendBroadcast(new Intent(ACTION_NAME_UPDATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_NAME, trimmed));
    }
}
