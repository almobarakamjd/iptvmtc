# تحديث myTv+ وPlayer+ على تلفاز MTC عبر ADB اللاسلكي — بلا أي ضغطة على الريموت.
# التشغيل: نقرة مزدوجة على update_tv.bat (أو: powershell -File tools\update_tv.ps1)
# يثبّت الملفات الموجودة في مجلد release\ بالمستودع (آخر نسخة مبنية محلياً).

$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$releaseDir = Join-Path $PSScriptRoot '..\release'
$apks = @(
    @{ name = 'myTv+';   pkg = 'com.smarttvplus.iptvmtc'; file = 'SmartTVPlus.apk' },
    @{ name = 'Player+'; pkg = 'com.oqod.movie_player';   file = 'PlayerPlus.apk' }
)

if (-not (Test-Path $adb)) { Write-Host "adb غير موجود: $adb"; Read-Host 'اضغط Enter'; exit 1 }

# 1) إيجاد التلفاز: آخر عنوان ناجح محفوظ، وإلا مسح الشبكة بحثاً عن منفذ 5555
$ipFile = Join-Path $env:TEMP 'mtc_tv_ip.txt'
$tv = $null
if (Test-Path $ipFile) {
    $saved = (Get-Content $ipFile).Trim()
    $c = New-Object Net.Sockets.TcpClient
    try { $c.Connect($saved, 5555); if ($c.Connected) { $tv = $saved } } catch {} finally { $c.Close() }
}
if (-not $tv) {
    Write-Host 'جاري البحث عن التلفاز في الشبكة...'
    $myIp = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like '192.168.*' -or $_.IPAddress -like '10.*' } | Select-Object -First 1).IPAddress
    if (-not $myIp) { Write-Host 'لا يوجد اتصال بشبكة الواي فاي'; Read-Host 'اضغط Enter'; exit 1 }
    $prefix = $myIp.Substring(0, $myIp.LastIndexOf('.'))
    $jobs = foreach ($i in 1..254) {
        $c = New-Object Net.Sockets.TcpClient
        [pscustomobject]@{ ip = "$prefix.$i"; c = $c; a = $c.BeginConnect("$prefix.$i", 5555, $null, $null) }
    }
    Start-Sleep -Seconds 3
    $found = $jobs | Where-Object { $_.a.IsCompleted -and $_.c.Connected } | Select-Object -First 1
    $jobs | ForEach-Object { $_.c.Close() }
    if (-not $found) { Write-Host 'لم يُعثر على التلفاز. تأكد أنه مُشغَّل وعلى نفس شبكة الواي فاي.'; Read-Host 'اضغط Enter'; exit 1 }
    $tv = $found.ip
    Set-Content $ipFile $tv
}

# 2) الاتصال
& $adb connect "${tv}:5555" | Out-Null
$model = (& $adb -s "${tv}:5555" shell getprop ro.product.model 2>$null)
if (-not $model) { Write-Host "تعذّر الاتصال بـ $tv"; Remove-Item $ipFile -ErrorAction SilentlyContinue; Read-Host 'اضغط Enter'; exit 1 }
Write-Host "متصل بالتلفاز $model ($tv)"

# 3) التثبيت (install -r يُبقي الإعدادات؛ ويرفض تلقائياً أي نسخة أقدم من المثبّتة)
foreach ($a in $apks) {
    $path = Join-Path $releaseDir $a.file
    if (-not (Test-Path $path)) { Write-Host "تخطّي $($a.name): الملف غير موجود $path"; continue }
    $before = (& $adb -s "${tv}:5555" shell dumpsys package $a.pkg | Select-String 'versionName=' | Select-Object -First 1) -replace '.*versionName=', ''
    Write-Host "تثبيت $($a.name) (المثبّت حالياً: $before)..."
    $out = & $adb -s "${tv}:5555" install -r $path 2>&1
    $after = (& $adb -s "${tv}:5555" shell dumpsys package $a.pkg | Select-String 'versionName=' | Select-Object -First 1) -replace '.*versionName=', ''
    if ($out -match 'Success') { Write-Host "  ✓ $($a.name) الآن $after" } else { Write-Host "  ✗ فشل: $out" }
}
Read-Host 'انتهى. اضغط Enter للإغلاق'
