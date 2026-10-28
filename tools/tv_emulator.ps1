# يشغّل محاكي التلفاز mytv_tv (إن لم يكن يعمل) ويجعل نافذته فوق كل النوافذ دائماً
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Add-Type @"
using System; using System.Text; using System.Runtime.InteropServices;
public class TvWin {
  delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(P p, IntPtr l);
  [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  public static bool PinOnTop(string title) {
    bool found = false;
    EnumWindows((h, l) => {
      var t = new StringBuilder(256); GetWindowText(h, t, 256);
      if (t.ToString().StartsWith(title)) { SetWindowPos(h, new IntPtr(-1), 0, 0, 0, 0, 0x0003); found = true; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
"@
if (-not [TvWin]::PinOnTop("Android Emulator - mytv_tv")) {
    Remove-Item "$env:USERPROFILE\.android\avd\mytv_tv.avd\*.lock" -Recurse -Force -ErrorAction SilentlyContinue
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "mytv_tv" -WindowStyle Hidden
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep 2
        if ([TvWin]::PinOnTop("Android Emulator - mytv_tv")) { break }
    }
}
