package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.web.R;
import org.telegram.tgnet.TL_smsjobs;
import org.telegram.ui.LaunchActivity;

public class SMSJobsNotification extends Service {

    private static SparseArray<SMSJobsNotification> instance = new SparseArray<>();
    private static SparseArray<Intent> service = new SparseArray<>();

    public int currentAccount;
    public boolean shown;

    public SMSJobsNotification() {
        super();
    }

    public static boolean check() {
        boolean shown = false;
        for (int i : SharedConfig.activeAccounts) {
            shown = check(i) || shown;
        }
        return shown;
    }

    public static boolean check(int currentAccount) {
        boolean showNotification = ApplicationLoader.mainInterfacePaused;
        if (showNotification) {
            showNotification = MessagesController.getInstance(currentAccount).smsjobsStickyNotificationEnabled;
        }
        if (showNotification) {
            showNotification = (
                SMSJobController.getInstance(currentAccount).getState() == SMSJobController.STATE_JOINED &&
                SMSJobController.getInstance(currentAccount).currentStatus != null
            );
        }

        final boolean shownNow = instance.get(num) != null && instance.get(num).shown;
        if (shownNow != showNotification) {
            if (showNotification) {
                Intent localInstance = new Intent(ApplicationLoader.applicationContext, SMSJobsNotification.class);
                localInstance.putExtra("account", currentAccount);
                service.put(currentAccount, localInstance);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ApplicationLoader.applicationContext.startForegroundService(service.get(currentAccount));
                } else {
                    ApplicationLoader.applicationContext.startService(service.get(currentAccount));
                }
            } else {
                if (service.get(currentAccount) != null) {
                    ApplicationLoader.applicationContext.stopService(service.get(currentAccount));
                    service.put(currentAccount, null);
                }
            }
        } else if (shownNow) {
            instance.get(currentAccount).update();
        }

        return showNotification;
    }

    @Override
    public void onDestroy() {
        shown = false;
        super.onDestroy();
        try {
            stopForeground(true);
        } catch (Throwable ignore) {}
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(38);
        } catch (Throwable ignore) {}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private NotificationCompat.Builder builder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        currentAccount = intent.getIntExtra("account", UserConfig.selectedAccount);

        if (instance.get(currentAccount) != this && instance.get(currentAccount) != null) {
            instance.get(currentAccount).stopSelf();
        }
        instance.put(currentAccount, this);
        shown = true;

        if (builder == null) {
            NotificationsController.checkOtherNotificationsChannel();
            builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            builder.setSmallIcon(R.drawable.left_sms);
            builder.setWhen(System.currentTimeMillis());
            builder.setChannelId(NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);

            Intent openIntent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
            openIntent.setData(Uri.parse("tg://settings/premium_sms"));
            PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, openIntent, PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(pendingIntent);
        }
        builder.setContentTitle(LocaleController.getString(R.string.SmsNotificationTitle));
        TL_smsjobs.TL_smsjobs_status status = SMSJobController.getInstance(currentAccount).currentStatus;
        final int sent = status != null ? status.recent_sent : 0;
        final int all = status != null ? status.recent_sent + status.recent_remains : 100;
        builder.setContentText(LocaleController.formatString(R.string.SmsNotificationSubtitle, sent, all));
        builder.setProgress(all, sent, false);
        try {
            startForeground(38, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
        AndroidUtilities.runOnUIThread(this::updateNotify);
        return Service.START_NOT_STICKY;
    }

    public void update() {
        if (builder != null) {
            builder.setContentTitle(LocaleController.getString(R.string.SmsNotificationTitle));
            TL_smsjobs.TL_smsjobs_status status = SMSJobController.getInstance(currentAccount).currentStatus;
            final int sent = status != null ? status.recent_sent : 0;
            final int all = status != null ? status.recent_sent + status.recent_remains : 100;
            builder.setContentText(LocaleController.formatString(R.string.SmsNotificationSubtitle, sent, all));
            builder.setProgress(all, sent, false);
        }
        updateNotify();
    }

    private void updateNotify() {
        if (builder == null) return;
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(38, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
