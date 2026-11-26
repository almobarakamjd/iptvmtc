package com.smarttvplus.iptvmtc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * خدمة أمامية بإشعار دائم ما دام «المساعد الذكي» يعمل، حتى لا يوقف النظام العملية إن غادر المستخدم
 * التطبيق أثناء الفحص. العمل نفسه في {@link AiDoctor}؛ هذه الخدمة لا تحمل إلا الإشعار.
 */
public class AiService extends Service {

    private static final String CHANNEL = "ai_doctor";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "المساعد الذكي", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("myTv+")
                .setContentText("المساعد الذكي يفحص التطبيق…")
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(71, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(71, n);
        } catch (Exception e) {
            RemoteLog.log("ai", "foreground refused: " + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
