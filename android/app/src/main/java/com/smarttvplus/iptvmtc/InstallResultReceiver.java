package com.smarttvplus.iptvmtc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/** نتيجة جلسة التثبيت: يفتح شاشة تأكيد أندرويد ("تثبيت")، ثم يبلغ بالنجاح أو الفشل */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText(context, "تم التثبيت ✓", Toast.LENGTH_LONG).show();
                break;
            default: {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w("InstallResult", "status=" + status + " " + msg);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, "تعذر التثبيت" + (status == PackageInstaller.STATUS_FAILURE_CONFLICT
                            ? " (توقيع مختلف عن النسخة المثبّتة)" : ""), Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
