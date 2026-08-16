package app.cadence.focus;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Commands heard while the app was closed that could not be applied straight
 * away. The web app drains this queue the next time it starts.
 */
final class PendingStore {

    private static final String FILE = "pending-commands.json";
    private static final int MAX = 40;
    private static final Object LOCK = new Object();

    private PendingStore() {}

    static void add(Context ctx, String command) {
        synchronized (LOCK) {
            JSONArray arr = read(ctx);
            arr.put(command);
            while (arr.length() > MAX) arr.remove(0);
            write(ctx, arr.toString());
        }
    }

    /** Returns everything queued and empties the queue. */
    static JSONArray drain(Context ctx) {
        synchronized (LOCK) {
            JSONArray arr = read(ctx);
            write(ctx, "[]");
            return arr;
        }
    }

    private static JSONArray read(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return new JSONArray();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return new JSONArray(new String(buf, "UTF-8"));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) {}
        }
    }

    private static void write(Context ctx, String json) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(ctx.getFilesDir(), FILE));
            out.write(json.getBytes("UTF-8"));
            out.flush();
        } catch (IOException ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }
}
