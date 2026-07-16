package com.shaforostoff.neonvideocompressor.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.util.ArrayList;

/**
 * Durable copy of the last finished conversion result, so the "compression
 * finished" screen (Open / Share / Replace original) survives the process being
 * killed. The in-memory {@link ConversionService#getLastTerminal()} dies with
 * the process; a big encode can leave the process bloated and cached, making it
 * a prime low-memory-killer target moments after it finishes — exactly when the
 * user hasn't acted on the result yet. The output file itself is already durable
 * in MediaStore, so persisting its Uri lets us reconstruct the result screen on
 * the next launch and act on the file regardless of process death.
 *
 * <p>"Acknowledged" means the user has actually seen the finished screen (live
 * or restored). Only an <em>unacknowledged</em> result is auto-shown on launch —
 * that is the case that got lost.
 */
public final class ResultStore {

    private static final String PREFS = "last_result";
    private static final String KEY_PRESENT = "present";
    private static final String KEY_ACKED = "acknowledged";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_AUDIO_ONLY = "audio_only";
    private static final String KEY_PARTIAL = "partial";
    private static final String KEY_BATCH_TOTAL = "batch_total";
    private static final String KEY_INPUT_URI = "input_uri";
    private static final String KEY_OUTPUTS = "outputs"; // '\n'-joined uri strings

    private ResultStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Persist a finished result. No-op unless there is at least one output. */
    public static void save(Context ctx, ConversionService.Snapshot s) {
        if (s == null || s.outputs.isEmpty()) {
            clear(ctx);
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (Uri u : s.outputs) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(u.toString());
        }
        prefs(ctx).edit()
                .putBoolean(KEY_PRESENT, true)
                .putBoolean(KEY_ACKED, false)
                .putString(KEY_MESSAGE, s.message)
                .putBoolean(KEY_AUDIO_ONLY, s.audioOnly)
                .putBoolean(KEY_PARTIAL, s.partial)
                .putInt(KEY_BATCH_TOTAL, Math.max(1, s.batchTotal))
                .putString(KEY_INPUT_URI, s.inputUri != null ? s.inputUri.toString() : null)
                .putString(KEY_OUTPUTS, joined.toString())
                .apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /** Mark the stored result as seen so it is not auto-shown again on launch. */
    public static void markAcknowledged(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean(KEY_PRESENT, false)) {
            p.edit().putBoolean(KEY_ACKED, true).apply();
        }
    }

    /** True when a result is stored that the user has not seen yet. */
    public static boolean hasPending(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getBoolean(KEY_PRESENT, false) && !p.getBoolean(KEY_ACKED, false);
    }

    /**
     * Reconstruct the stored result as a terminal (DONE) snapshot, or {@code null}
     * if nothing is stored. Drops (and clears) the record when its first output no
     * longer resolves — e.g. the user deleted the file in the meantime.
     */
    public static ConversionService.Snapshot load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(KEY_PRESENT, false)) return null;

        String joined = p.getString(KEY_OUTPUTS, "");
        ArrayList<Uri> outputs = new ArrayList<>();
        if (joined != null && !joined.isEmpty()) {
            for (String part : joined.split("\n")) {
                if (!part.isEmpty()) outputs.add(Uri.parse(part));
            }
        }
        if (outputs.isEmpty() || !uriResolves(ctx, outputs.get(0))) {
            clear(ctx);
            return null;
        }

        ConversionService.Snapshot s = new ConversionService.Snapshot();
        s.status = ConversionService.Status.DONE;
        s.overall = 1f;
        s.message = p.getString(KEY_MESSAGE, null);
        s.audioOnly = p.getBoolean(KEY_AUDIO_ONLY, false);
        s.partial = p.getBoolean(KEY_PARTIAL, false);
        s.batchTotal = p.getInt(KEY_BATCH_TOTAL, 1);
        s.batchIndex = Math.max(0, s.batchTotal - 1); // completed: forces 100%
        String input = p.getString(KEY_INPUT_URI, null);
        s.inputUri = input != null ? Uri.parse(input) : null;
        s.output = outputs.get(0);
        s.outputs.addAll(outputs);
        return s;
    }

    /** Whether a content Uri still points at a live item. */
    private static boolean uriResolves(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }
}
