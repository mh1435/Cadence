package app.cadence.focus;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies the bundled Vosk model out of assets/ into the app's private files
 * directory the first time it is needed. Vosk opens the model with plain file
 * I/O, so it cannot read it straight from the APK.
 */
final class ModelAssets {

    private static final String ASSET_DIR = "vosk-model";
    private static final String STAMP = ".copied";

    private ModelAssets() {}

    /** @return absolute path of the unpacked model directory. */
    static String ensure(Context ctx) throws IOException {
        File target = new File(ctx.getFilesDir(), ASSET_DIR);
        File stamp = new File(target, STAMP);
        if (stamp.exists()) return target.getAbsolutePath();

        deleteTree(target);
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("cannot create " + target);
        }
        copyTree(ctx, ASSET_DIR, target);

        FileOutputStream out = new FileOutputStream(stamp);
        out.write('1');
        out.close();
        return target.getAbsolutePath();
    }

    private static void copyTree(Context ctx, String assetPath, File dest) throws IOException {
        String[] children = ctx.getAssets().list(assetPath);
        if (children == null || children.length == 0) {   // a file, not a directory
            copyFile(ctx, assetPath, dest);
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) throw new IOException("cannot create " + dest);
        for (String child : children) {
            copyTree(ctx, assetPath + "/" + child, new File(dest, child));
        }
    }

    private static void copyFile(Context ctx, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        InputStream in = ctx.getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
