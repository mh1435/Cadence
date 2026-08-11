package app.cadence.focus;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

/**
 * Android 12+ forbids starting a microphone foreground service from the
 * background, so a reboot cannot silently re-arm listening. Instead of failing
 * quietly, nudge the user to open the app once.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String CH = "cadence_results";
    private static final int NOTE = 4103;

    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!Prefs.wantsBackgroundListening(ctx)) return;

        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open == null) return;
        PendingIntent tap = PendingIntent.getActivity(
                ctx, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH)
                .setContentTitle("Resume background listening")
                .setContentText("Open Cadence once to start listening for “Cadence…” again.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(tap)
                .setAutoCancel(true);
        try { nm.notify(NOTE, b.build()); } catch (Throwable ignored) {}
    }
}
