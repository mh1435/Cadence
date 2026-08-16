package app.cadence.focus;

import android.Manifest;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;

/**
 * Bridge between the web app and {@link VoiceService}.
 *
 * The service also calls back in here: when the app happens to be on screen a
 * heard command is handed to the live page instead of being replayed in an
 * offscreen WebView, so there is only ever one writer to localStorage.
 */
@CapacitorPlugin(
        name = "CadenceVoice",
        permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic") }
)
public class VoicePlugin extends Plugin {

    private static VoicePlugin instance;
    private static volatile boolean visible = false;

    @Override public void load() { instance = this; }

    @Override protected void handleOnResume() { visible = true; }

    @Override protected void handleOnPause() { visible = false; }

    @Override protected void handleOnDestroy() {
        visible = false;
        if (instance == this) instance = null;
    }

    /** True when the visible page can run the command itself. */
    static boolean appOpen() { return instance != null && visible; }

    static void deliver(String command) {
        VoicePlugin p = instance;
        if (p == null) return;
        JSObject data = new JSObject();
        data.put("command", command);
        p.notifyListeners("command", data, true);
    }

    // ------------------------------------------------------------ methods

    @PluginMethod
    public void status(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("supported", true);
        ret.put("running", VoiceService.isRunning());
        ret.put("granted", getPermissionState("mic") == PermissionState.GRANTED);
        ret.put("error", VoiceService.lastError());
        call.resolve(ret);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micResult");
            return;
        }
        launch(call);
    }

    @PermissionCallback
    private void micResult(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            call.reject("Microphone permission is required for background listening.");
            return;
        }
        launch(call);
    }

    private void launch(PluginCall call) {
        try {
            VoiceService.startService(getContext());
            Prefs.setWantsBackgroundListening(getContext(), true);
        } catch (Throwable t) {
            call.reject("Android refused to start the listener: " + t.getMessage());
            return;
        }
        JSObject ret = new JSObject();
        ret.put("running", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Prefs.setWantsBackgroundListening(getContext(), false);
        try { VoiceService.stopService(getContext()); } catch (Throwable ignored) {}
        JSObject ret = new JSObject();
        ret.put("running", false);
        call.resolve(ret);
    }

    /** Commands captured while the app was closed and not yet applied. */
    @PluginMethod
    public void pending(PluginCall call) {
        JSONArray arr = PendingStore.drain(getContext());
        JSObject ret = new JSObject();
        ret.put("commands", arr);
        call.resolve(ret);
    }

    /** Android 12+ hides battery-optimised apps' services; expose the SDK level. */
    @PluginMethod
    public void info(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("sdk", Build.VERSION.SDK_INT);
        ret.put("model", Build.MODEL);
        call.resolve(ret);
    }
}
