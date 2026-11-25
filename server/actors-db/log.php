<?php
// سجلّ تشخيص عن بُعد لـmyTv+ (2026-11-26): التلفاز لا يصله adb، فالتطبيق يرفع أحداثه هنا ونقرؤها من اللابتوب.
//
// رفع:   POST جسمه JSON {"token":"...","device":"TCL-xxxx","ver":"1.7.9","lines":[{"t":<ms>,"tag":"hdmi","msg":"..."}]}
// قراءة: GET  log.php?token=...&after=<id>&limit=500[&device=...]   ← {"ok":true,"rows":[{"id","at","device","ver","t","tag","msg"}]}
// مسح:   GET  log.php?token=...&clear=1
// الجدول يُنشأ تلقائياً عند أول طلب، ويُحتفظ بآخر 20000 سطر.

require __DIR__ . '/config.php';
header('Content-Type: application/json; charset=utf-8');

const LOG_TOKEN = 'mytvplus_log_Kd4vR8sJ2xQm';

$data = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $data = json_decode(file_get_contents('php://input'), true);
    if (!is_array($data)) { http_response_code(400); die(json_encode(['error' => 'bad_json'])); }
}
$in = $data === null ? $_GET : $data;
if (!isset($in['token']) || $in['token'] !== LOG_TOKEN) { http_response_code(401); die(json_encode(['error' => 'unauthorized'])); }

$conn = db_connect();
$conn->query(
    'CREATE TABLE IF NOT EXISTS app_log (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
        at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        device VARCHAR(64) NOT NULL,
        ver VARCHAR(16) NOT NULL,
        t BIGINT NOT NULL,
        tag VARCHAR(24) NOT NULL,
        msg TEXT NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
);

if ($data !== null) {
    $device = substr(isset($data['device']) ? strval($data['device']) : '?', 0, 64);
    $ver = substr(isset($data['ver']) ? strval($data['ver']) : '?', 0, 16);
    $lines = isset($data['lines']) && is_array($data['lines']) ? array_slice($data['lines'], 0, 300) : [];
    $stmt = $conn->prepare('INSERT INTO app_log (device, ver, t, tag, msg) VALUES (?,?,?,?,?)');
    $n = 0;
    foreach ($lines as $l) {
        if (!is_array($l)) continue;
        $t = isset($l['t']) ? intval($l['t']) : 0;
        $tag = substr(isset($l['tag']) ? strval($l['tag']) : '', 0, 24);
        $msg = substr(isset($l['msg']) ? strval($l['msg']) : '', 0, 4000);
        $stmt->bind_param('ssiss', $device, $ver, $t, $tag, $msg);
        if ($stmt->execute()) $n++;
    }
    if (mt_rand(0, 50) === 0) {
        $conn->query('DELETE FROM app_log WHERE id < (SELECT m FROM (SELECT MAX(id) - 20000 AS m FROM app_log) x)');
    }
    echo json_encode(['ok' => true, 'saved' => $n]);
    exit;
}

if (isset($in['clear'])) {
    $conn->query('TRUNCATE TABLE app_log');
    echo json_encode(['ok' => true, 'cleared' => true]);
    exit;
}

$after = isset($in['after']) ? intval($in['after']) : 0;
$limit = max(1, min(2000, isset($in['limit']) ? intval($in['limit']) : 500));
if (isset($in['device']) && $in['device'] !== '') {
    $dev = strval($in['device']);
    $stmt = $conn->prepare('SELECT id, UNIX_TIMESTAMP(at) AS at, device, ver, t, tag, msg FROM app_log WHERE id > ? AND device = ? ORDER BY id DESC LIMIT ?');
    $stmt->bind_param('isi', $after, $dev, $limit);
} else {
    $stmt = $conn->prepare('SELECT id, UNIX_TIMESTAMP(at) AS at, device, ver, t, tag, msg FROM app_log WHERE id > ? ORDER BY id DESC LIMIT ?');
    $stmt->bind_param('ii', $after, $limit);
}
$stmt->execute();
$res = $stmt->get_result();
$rows = [];
while ($r = $res->fetch_assoc()) {
    $r['id'] = intval($r['id']); $r['at'] = intval($r['at']); $r['t'] = intval($r['t']);
    $rows[] = $r;
}
echo json_encode(['ok' => true, 'rows' => array_reverse($rows)], JSON_UNESCAPED_UNICODE);
