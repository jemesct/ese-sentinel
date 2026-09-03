package pro.sparkworks.esewatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Performs one real StrongBox operation with a hard timeout.
 *
 * Rationale: when the SN220 eSE dies, IKeyMintDevice::begin blocks forever inside the
 * StrongBox KeyMint HAL (no timeout at any layer). A healthy chip answers a small AES-GCM
 * op in well under a second. So: run the op on a sacrificial thread, wait up to
 * TIMEOUT_S. Timeout == the exact failure mode that ends in lockout ~1-3h later.
 *
 * While the chip is wedged, each probe thread leaks (blocked forever in the binder call);
 * PROBE_IN_FLIGHT prevents stacking more than one.
 */
public final class Prober {
    private static final String TAG = "eSESentinel";
    private static final String KEY_ALIAS = "ese_probe_key";
    private static final int TIMEOUT_S = 20;
    private static final AtomicBoolean PROBE_IN_FLIGHT = new AtomicBoolean(false);

    public static final String STATUS_OK = "OK";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_ERROR = "ERROR";

    public static final class Result {
        public final String status;   // OK / TIMEOUT / ERROR
        public final long latencyMs;
        public final String detail;
        Result(String status, long latencyMs, String detail) {
            this.status = status; this.latencyMs = latencyMs; this.detail = detail;
        }
    }

    /** Blocking; call from a background thread. */
    public static Result probe() {
        if (!PROBE_IN_FLIGHT.compareAndSet(false, true)) {
            // The previous probe never returned: still wedged. Don't burn another
            // keystore2 binder thread; report timeout again.
            return new Result(STATUS_TIMEOUT, -1, "previous probe still blocked");
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Result> out = new AtomicReference<>();
        final long start = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                doStrongBoxOp();
                out.set(new Result(STATUS_OK, System.currentTimeMillis() - start, ""));
            } catch (Throwable e) {
                Log.w(TAG, "probe error", e);
                out.set(new Result(STATUS_ERROR, System.currentTimeMillis() - start,
                        e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
            } finally {
                PROBE_IN_FLIGHT.set(false);
                done.countDown();
            }
        }, "ese-probe");
        t.setDaemon(true);
        t.start();
        try {
            if (!done.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                // Leave PROBE_IN_FLIGHT true; the thread owns it and will clear it if
                // the call ever returns (i.e. the chip self-recovered).
                return new Result(STATUS_TIMEOUT, TIMEOUT_S * 1000L, "StrongBox op did not return");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(STATUS_ERROR, -1, "interrupted");
        }
        return out.get();
    }

    private static void doStrongBoxOp() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey key = null;
        KeyStore.Entry entry = ks.isKeyEntry(KEY_ALIAS) ? ks.getEntry(KEY_ALIAS, null) : null;
        if (entry instanceof KeyStore.SecretKeyEntry) {
            key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        if (key == null) {
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(128)
                    .setIsStrongBoxBacked(true)   // the whole point
                    .build());
            key = kg.generateKey();               // also exercises the eSE
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);         // keystore createOperation -> StrongBox begin
        c.doFinal(new byte[16]);                  // update + finish
    }

    // ---- history (newline records in SharedPreferences, newest first, capped) ----

    private static final String PREFS = "history";
    private static final String KEY_LOG = "log";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final int MAX_LINES = 200;

    public static void record(Context ctx, Result r) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date())
                + "  " + r.status
                + (r.latencyMs >= 0 ? " (" + r.latencyMs + " ms)" : "")
                + (r.detail.isEmpty() ? "" : "  " + r.detail);
        String log = sp.getString(KEY_LOG, "");
        String[] lines = log.isEmpty() ? new String[0] : log.split("\n");
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < Math.min(lines.length, MAX_LINES - 1); i++) {
            sb.append('\n').append(lines[i]);
        }
        sp.edit().putString(KEY_LOG, sb.toString())
                .putString(KEY_LAST_STATUS, r.status).apply();
    }

    public static String getLog(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "(no probes yet)");
    }

    public static String getLastStatus(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_STATUS, "");
    }
}
