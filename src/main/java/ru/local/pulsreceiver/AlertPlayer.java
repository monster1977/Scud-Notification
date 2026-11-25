package ru.local.pulsreceiver;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Plays the system notification sound and vibrates when a new signal arrives. */
final class AlertPlayer {
    private Ringtone ringtone;

    void play(Context context) {
        stop();
        Uri sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION);
        if (sound != null) {
            ringtone = RingtoneManager.getRingtone(context, sound);
            if (ringtone != null) {
                try { ringtone.play(); } catch (Exception unavailable) { ringtone = null; }
            }
        }
        if (ringtone == null) beep();
        vibrate(context);
    }

    /** Fallback when the device has no default notification sound. */
    private void beep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 400);
        } catch (Exception ignored) { }
    }

    /** Never throws: a missing or busy vibrator must not break the request handler thread. */
    void vibrate(Context context) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 250, 150, 250};
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else vibrator.vibrate(pattern, -1);
        } catch (Exception unavailable) { }
    }

    void stop() {
        if (ringtone != null) {
            try { ringtone.stop(); } catch (Exception ignored) { }
            ringtone = null;
        }
    }
}
