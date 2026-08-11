package app.cadence.focus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

/**
 * Always-on wake word listening.
 *
 * Android will not let an app touch the microphone from the background, so this
 * runs as a foreground service with a permanent notification — that is the only
 * sanctioned way to keep listening once the app is closed or the screen is off.
 * Recognition is Vosk, running entirely on the device: nothing leaves the phone.
 */
public class VoiceService extends Service implements RecognitionListener {

    private static final String TAG = "CadenceVoice";

    public static final String ACTION_STOP = "app.cadence.focus.STOP_LISTENING";

    private static final String CH_RUNNING = "cadence_listening";
    private static final String CH_RESULT = "cadence_results";
    private static final int NOTE_RUNNING = 4101;
    private static final int NOTE_RESULT = 4102;

    private static final float SAMPLE_RATE = 16000.0f;
    private static final long DEBOUNCE_MS = 1200;

    /** Wake words, plus the spellings the recogniser most often lands on. */
    private static final String[] WAKE = {
            "hey cadence", "ok cadence", "okay cadence",
            "cadence", "cadance", "kadence", "cadences", "credence", "cadets"
    };

    private static volatile boolean running = false;
    private static volatile String lastError = "";

    public static boolean isRunning() { return running; }
    public static String lastError() { return lastError; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechService speech;
    private Model model;
    private long lastHandled = 0L;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForegroundSafely("Listening for “Cadence…”");
        running = true;
        lastError = "";
        new Thread(new Runnable() {
            @Override public void run() { initRecogniser(); }
        }, "cadence-vosk-init").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;   // the OS restarts us if it reclaims memory
    }

    @Override public void onDestroy() {
        running = false;
        if (speech != null) {
            try { speech.stop(); } catch (Throwable ignored) {}
            try { speech.shutdown(); } catch (Throwable ignored) {}
            speech = null;
        }
        if (model != null) {
            try { model.close(); } catch (Throwable ignored) {}
            model = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- setup

    private void initRecogniser() {
        try {
            String path = ModelAssets.ensure(this);
            final Model m = new Model(path);
            final Recognizer rec = new Recognizer(m, SAMPLE_RATE);
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        model = m;
                        speech = new SpeechService(rec, SAMPLE_RATE);
                        speech.startListening(VoiceService.this);
                    } catch (Throwable t) {
                        fail(t);
                    }
                }
            });
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void fail(Throwable t) {
        Log.e(TAG, "listener failed", t);
        lastError = String.valueOf(t.getMessage());
        notifyResult("Background listening stopped", "Cadence could not start the recogniser.");
        stopSelf();
    }

    // ------------------------------------------------------- recognition

    @Override public void onResult(String hypothesis) { handle(textOf(hypothesis)); }

    @Override public void onFinalResult(String hypothesis) { handle(textOf(hypothesis)); }

    @Override public void onPartialResult(String hypothesis) { /* wait for the full utterance */ }

    @Override public void onError(Exception e) { Log.w(TAG, "recogniser error", e); }

    @Override public void onTimeout() {
        if (speech != null) speech.startListening(this);   // re-arm
    }

    private static String textOf(String hypothesis) {
        if (hypothesis == null) return "";
        try {
            JSONObject o = new JSONObject(hypothesis);
            return o.optString("text", o.optString("partial", ""));
        } catch (Throwable t) {
            return "";
        }
    }

    private void handle(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String low = text.toLowerCase().trim();

        int at = -1;
        String matched = null;
        for (String w : WAKE) {
            int i = low.indexOf(w);
            if (i < 0) continue;
            if (at < 0 || i < at || (i == at && w.length() > matched.length())) { at = i; matched = w; }
        }
        if (at < 0) return;                                   // ordinary conversation

        String command = low.substring(at + matched.length()).replaceAll("^[\\s,.:;-]+", "").trim();
        if (command.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastHandled < DEBOUNCE_MS) return;          // one utterance, one action
        lastHandled = now;

        dispatch(command);
    }

    private void dispatch(final String command) {
        if (VoicePlugin.appOpen()) {                          // the live page handles it
            VoicePlugin.deliver(command);
            return;
        }
        HeadlessRunner.run(this, command, new HeadlessRunner.Done() {
            @Override public void onDone(String message, boolean ok) {
                if (ok && message != null && !message.isEmpty()) {
                    notifyResult("Cadence", message);
                } else {
                    PendingStore.add(VoiceService.this, command);
                    notifyResult("Cadence heard you", "“" + command + "” — applied next time you open the app.");
                }
            }
        });
    }

    // ----------------------------------------------------- notifications

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel run = new NotificationChannel(CH_RUNNING, "Background listening", NotificationManager.IMPORTANCE_LOW);
        run.setShowBadge(false);
        run.setDescription("Shown while Cadence is listening for its wake word.");
        nm.createNotificationChannel(run);
        NotificationChannel res = new NotificationChannel(CH_RESULT, "Voice results", NotificationManager.IMPORTANCE_DEFAULT);
        res.setDescription("What Cadence did with a spoken command.");
        nm.createNotificationChannel(res);
    }

    private void startForegroundSafely(String text) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent tap = open == null ? null : PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, VoiceService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_RUNNING)
                .setContentTitle("Cadence")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Stop", stop);
        if (tap != null) b.setContentIntent(tap);

        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTE_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTE_RUNNING, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground refused", t);
            lastError = String.valueOf(t.getMessage());
            stopSelf();
        }
    }

    private void notifyResult(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent tap = open == null ? null : PendingIntent.getActivity(
                this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_RESULT)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setAutoCancel(true);
        if (tap != null) b.setContentIntent(tap);
        try { nm.notify(NOTE_RESULT, b.build()); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ control

    static void startService(Context ctx) {
        Intent i = new Intent(ctx, VoiceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void stopService(Context ctx) {
        ctx.stopService(new Intent(ctx, VoiceService.class));
    }
}
