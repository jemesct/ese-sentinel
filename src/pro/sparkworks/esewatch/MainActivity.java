package pro.sparkworks.esewatch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Alerts.ensureChannels(this);
        ProbeJob.schedule(this);

        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("eSE Sentinel");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView blurb = new TextView(this);
        blurb.setText("Probes the StrongBox secure element every 15 minutes. "
                + "If the chip stops answering (the bug that causes lockouts), "
                + "you get an alarm notification with ~1–3 hours to reboot.");
        blurb.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(blurb);

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(statusView);

        Button probeNow = new Button(this);
        probeNow.setText("Probe now");
        probeNow.setOnClickListener(v -> runProbe());
        root.addView(probeNow);

        Button battery = new Button(this);
        battery.setText("Exempt from battery optimisation");
        battery.setOnClickListener(v -> requestBatteryExemption());
        root.addView(battery);

        TextView logTitle = new TextView(this);
        logTitle.setText("Probe history (newest first)");
        logTitle.setTypeface(null, Typeface.BOLD);
        logTitle.setPadding(0, pad, 0, pad / 4);
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        root.addView(logView);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        // Edge-to-edge is enforced on recent Android: inset the content past the
        // status bar / display cutout / nav bar ourselves.
        sc.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets si = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(si.left, si.top, si.right, si.bottom);
            return insets;
        });
        setContentView(sc);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String last = Prober.getLastStatus(this);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm.isIgnoringBatteryOptimizations(getPackageName());
        statusView.setText("Last probe: " + (last.isEmpty() ? "none yet" : last)
                + "\nBattery exemption: " + (exempt ? "granted" : "NOT granted — tap below"));
        logView.setText(Prober.getLog(this));
    }

    private void runProbe() {
        statusView.setText("Probing… (up to 20 s)");
        new Thread(() -> {
            Prober.Result r = Prober.probe();
            String prev = Prober.getLastStatus(this);
            Prober.record(this, r);
            if (Prober.STATUS_TIMEOUT.equals(r.status)) {
                Alerts.alarmChipDead(this, r.detail);
            } else if (Prober.STATUS_OK.equals(r.status) && Prober.STATUS_TIMEOUT.equals(prev)) {
                Alerts.infoRecovered(this);
            }
            runOnUiThread(this::refresh);
        }, "ese-probe-manual").start();
    }

    private void requestBatteryExemption() {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }
}
