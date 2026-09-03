package pro.sparkworks.esewatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;

public final class Alerts {
    private static final String CH_ALARM = "ese_dead";
    private static final String CH_INFO = "ese_info";
    private static final int ID_ALARM = 1;
    private static final int ID_INFO = 2;

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        NotificationChannel alarm = new NotificationChannel(CH_ALARM,
                "Secure element DEAD — reboot", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("Fires when the security chip stops answering. Reboot promptly.");
        alarm.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        alarm.enableVibration(true);
        alarm.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 800});
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alarm.setBypassDnd(true); // honored if the user grants DND access; harmless otherwise
        nm.createNotificationChannel(alarm);

        NotificationChannel info = new NotificationChannel(CH_INFO,
                "Status", NotificationManager.IMPORTANCE_DEFAULT);
        info.setDescription("Recovery / informational messages.");
        nm.createNotificationChannel(info);
    }

    public static void alarmChipDead(Context ctx, String detail) {
        ensureChannels(ctx);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CH_ALARM)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("🚨 REBOOT YOUR PHONE NOW")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "The security chip has stopped answering (" + detail + "). "
                        + "Unlock will stop working within roughly 1–3 hours. "
                        + "Reboot now, while the phone still unlocks."))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setOngoing(true)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(ID_ALARM, n);
    }

    public static void infoRecovered(Context ctx) {
        ensureChannels(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.cancel(ID_ALARM);
        Notification n = new Notification.Builder(ctx, CH_INFO)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Secure element recovered")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "The chip answered again after a stall (this matches the transient "
                        + "pattern — 13 of 17 stalls self-recovered). No action needed, but a "
                        + "reboot at your convenience resets the odds."))
                .setAutoCancel(true)
                .build();
        nm.notify(ID_INFO, n);
    }
}
