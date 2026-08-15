package app.cadence.focus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Lets another app on the device (VOID) ask Cadence to run a command, the
 * same way the "hey cadence" wake word does — without opening Cadence's UI.
 *
 * Wire contract (also relied on by VOID's CadencePlugin.java):
 *   action: "app.cadence.focus.RUN_COMMAND", package-targeted, guarded by
 *   the "app.cadence.focus.permission.RUN_COMMAND" permission declared in
 *   the manifest. Extra "command": the raw text to hand to runCommand().
 *   Result code: 1 = MESSAGE (setResultData is the real result string),
 *   2 = LIVE (delivered to a foregrounded Cadence, no result text),
 *   3 = QUEUED (applied next time the app opens). Any other/absent code
 *   means this receiver never ran — e.g. an old Cadence build.
 */
public class RunCommandReceiver extends BroadcastReceiver {

    static final String ACTION = "app.cadence.focus.RUN_COMMAND";

    private static final int RESULT_MESSAGE = 1;
    private static final int RESULT_LIVE = 2;
    private static final int RESULT_QUEUED = 3;

    @Override public void onReceive(final Context context, Intent intent) {
        String command = intent.getStringExtra("command");
        if (command == null || command.trim().isEmpty()) return;

        final PendingResult pending = goAsync();
        CommandDispatch.run(context.getApplicationContext(), command, new CommandDispatch.Callback() {
            @Override public void onDone(int outcome, String message) {
                int code = outcome == CommandDispatch.MESSAGE ? RESULT_MESSAGE
                         : outcome == CommandDispatch.LIVE ? RESULT_LIVE
                         : RESULT_QUEUED;
                pending.setResultCode(code);
                pending.setResultData(message);
                pending.finish();
            }
        });
    }
}
