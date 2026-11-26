package ru.local.pulsreceiver;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.function.Consumer;

/** Service-owned TTS. All state and engine calls are confined to the main thread. */
final class NameSpeaker {
    static final String GOOGLE_ENGINE = "com.google.android.tts";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Context context;
    private final Consumer<String> status;
    private final Runnable fallback;
    private TextToSpeech tts;
    private boolean ready, failed, closed;
    private String current;
    private long sequence;
    private final Runnable timeout = () -> fail("Озвучивание недоступно. Перезапустите приёмник");

    NameSpeaker(Context context, Consumer<String> status, Runnable fallback) {
        this.context = context;
        this.status = status;
        this.fallback = fallback;
        status.accept("Подготовка русского голоса…");
        try {
            if (!context.getPackageManager().getApplicationInfo(GOOGLE_ENGINE, 0).enabled) {
                fail("Включите синтезатор речи Google");
                return;
            }
            // Posting also handles an immediate onInit failure before constructor assignment.
            tts = new TextToSpeech(context.getApplicationContext(),
                    result -> main.post(() -> initialized(result)), GOOGLE_ENGINE);
            main.postDelayed(timeout, 15000);
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            fail("Установите синтезатор речи Google и русский офлайн-голос");
        } catch (RuntimeException unavailable) {
            fail("Не удалось запустить синтезатор речи Google");
        }
    }

    private void initialized(int result) {
        if (closed || failed) return;
        main.removeCallbacks(timeout);
        if (result != TextToSpeech.SUCCESS) {
            fail("Не удалось запустить синтезатор речи Google");
            return;
        }
        try {
            Voice best = null;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) for (Voice voice : voices) {
                if (!"ru".equals(voice.getLocale().getLanguage()) || voice.isNetworkConnectionRequired()) continue;
                Set<String> features = voice.getFeatures();
                if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (best == null || voice.getQuality() > best.getQuality()
                        || (voice.getQuality() == best.getQuality() && voice.getName().compareTo(best.getName()) < 0)) best = voice;
            }
            if (best == null || tts.setVoice(best) != TextToSpeech.SUCCESS) {
                fail("Установите русский офлайн-голос Google, затем перезапустите приёмник");
                return;
            }
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            // Увеличиваем громкость синтеза речи до максимума по всем возможным потокам
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int[] streams = {
                    AudioManager.STREAM_ALARM,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.STREAM_SYSTEM,
                    AudioManager.STREAM_VOICE_CALL
            };
            for (int stream : streams) {
                int max = audioManager.getStreamMaxVolume(stream);
                if (max > 0) {
                    audioManager.setStreamVolume(stream, max, 0);
                }
            }
            // Устанавливаем нормальную скорость и тон голоса (если API позволяет)
            try { tts.setSpeechRate(1.0f); } catch (Exception ignored) {}
            try { tts.setPitch(1.0f); } catch (Exception ignored) {}
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { main.post(() -> finished(id, false)); }
                @Override public void onError(String id) { main.post(() -> finished(id, true)); }
                @Override public void onError(String id, int code) { onError(id); }
            });
            ready = true;
            status.accept("Озвучивание имён · русский офлайн-голос");
            next();
        } catch (RuntimeException unavailable) {
            fail("Офлайн-голос недоступен. Проверьте настройки речи");
        }
    }

    void speak(String text) {
        main.post(() -> {
            if (closed || text == null || text.trim().isEmpty()) return;
            if (failed) { fallback.run(); return; }
            // Bound waiting names so a burst cannot build an unbounded speech backlog.
            if (pending.size() == 10) pending.removeFirst();
            pending.addLast(text);
            next();
        });
    }

    private void next() {
        if (!ready || closed || current != null || pending.isEmpty()) return;
        current = "name-" + (++sequence);
        try {
            if (tts.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, current) == TextToSpeech.ERROR) {
                fail("Не удалось озвучить имя. Проверьте офлайн-голос");
            } else main.postDelayed(timeout, 20000);
        } catch (RuntimeException unavailable) {
            fail("Не удалось озвучить имя. Перезапустите приёмник");
        }
    }

    private void finished(String id, boolean error) {
        if (closed || !id.equals(current)) return;
        main.removeCallbacks(timeout);
        if (error) { fail("Ошибка офлайн-голоса. Проверьте настройки речи"); return; }
        current = null;
        next();
    }

    private void fail(String message) {
        if (closed) return;
        boolean hadSpeech = current != null || !pending.isEmpty();
        failed = true;
        ready = false;
        current = null;
        pending.clear();
        main.removeCallbacks(timeout);
        if (tts != null) { try { tts.stop(); } catch (RuntimeException ignored) { } }
        status.accept(message);
        if (hadSpeech) fallback.run();
    }

    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        pending.clear();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (RuntimeException ignored) { }
            tts = null;
        }
    }
}
