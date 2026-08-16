package app.cadence.focus;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers whether the user asked for background listening, so a reboot can prompt. */
final class Prefs {

    private static final String FILE = "cadence-native";
    private static final String KEY_BG = "background_listening";

    private Prefs() {}

    private static SharedPreferences of(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void setWantsBackgroundListening(Context ctx, boolean on) {
        of(ctx).edit().putBoolean(KEY_BG, on).apply();
    }

    static boolean wantsBackgroundListening(Context ctx) {
        return of(ctx).getBoolean(KEY_BG, false);
    }
}
