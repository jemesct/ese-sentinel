package pro.sparkworks.esewatch;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public class ProbeJob extends JobService {
    private static final String TAG = "eSESentinel";
    private static final int JOB_ID = 1;
    private static final long PERIOD_MS = 15 * 60 * 1000L; // JobScheduler minimum

    public static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        JobInfo pending = js.getPendingJob(JOB_ID);
        if (pending != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, ProbeJob.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)              // survives reboot
                .setRequiresBatteryNotLow(false)
                .build();
        int rc = js.schedule(job);
        Log.i(TAG, "scheduled periodic probe job rc=" + rc);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Prober.Result r = Prober.probe();
            String prev = Prober.getLastStatus(this);
            Prober.record(this, r);
            Log.i(TAG, "probe: " + r.status + " " + r.latencyMs + "ms " + r.detail);
            if (Prober.STATUS_TIMEOUT.equals(r.status)) {
                Alerts.alarmChipDead(this, r.detail);
            } else if (Prober.STATUS_OK.equals(r.status)
                    && Prober.STATUS_TIMEOUT.equals(prev)) {
                Alerts.infoRecovered(this);
            }
            jobFinished(params, false);
        }, "ese-probe-job").start();
        return true; // work continues on our thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if the system killed us mid-probe
    }
}
