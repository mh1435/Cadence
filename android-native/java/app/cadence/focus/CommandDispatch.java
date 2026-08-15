package app.cadence.focus;

import android.content.Context;

/**
 * Shared "what to do with a heard/received command" logic — used by
 * VoiceService's wake-word flow and by RunCommandReceiver (an external app
 * asking Cadence to run something). Keeping this in one place means both
 * callers get the exact same only-one-writer-to-localStorage behavior:
 * if the app is on screen, the live page handles it; otherwise the command
 * runs in an offscreen WebView, or gets queued if that fails.
 */
final class CommandDispatch {

    interface Callback {
        void onDone(int outcome, String message);
    }

    static final int LIVE = 0;     // delivered to the live page — no result text available
    static final int MESSAGE = 1;  // ran headless, message is runCommand()'s real return string
    static final int QUEUED = 2;   // headless run failed/empty — queued for next open

    private CommandDispatch() {}

    static void run(final Context ctx, final String command, final Callback cb) {
        if (VoicePlugin.appOpen()) {
            VoicePlugin.deliver(command);
            cb.onDone(LIVE, null);
            return;
        }
        HeadlessRunner.run(ctx, command, new HeadlessRunner.Done() {
            @Override public void onDone(String message, boolean ok) {
                if (ok && message != null && !message.isEmpty()) {
                    cb.onDone(MESSAGE, message);
                } else {
                    PendingStore.add(ctx, command);
                    cb.onDone(QUEUED, null);
                }
            }
        });
    }
}
