package com.smarttvplus.iptvmtc;

import android.app.admin.DeviceAdminReceiver;

/**
 * مستقبل «المهيمن» (Device Owner): لا يفعل شيئاً بنفسه، وجوده شرط ليُعيَّن التطبيق مهيمناً
 * عبر adb (dpm set-device-owner). وبعد التعيين تصير جلسات PackageInstaller التي يفتحها
 * التطبيق تُثبَّت صامتة بلا شاشة «تثبيت» (للتطبيق نفسه ولـPlayer+).
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
