<?php
// مزامنة بيانات مستخدم أبي فيصل المخفي بين الأجهزة (جدول user_data في user_schema.sql).
//
// قراءة الكل:  GET  user_data.php?token=...            ← {"ok":true,"fields":{"favs":{"data":"...","rev":3},...}}
// حفظ حقل:    POST جسمه JSON {"token":"...","field":"favs","data":"<JSON نصّي>"}  ← {"ok":true,"rev":4}
// الحفظ «الأخير يغلب»: الجهاز يجلب قبل العرض ويرفع بعد كل تعديل، فالتعارض لا يقع إلا بتعديل جهازين في اللحظة نفسها.

require __DIR__ . '/config.php';
header('Content-Type: application/json; charset=utf-8');

$FIELDS = ['favs', 'recent', 'lists', 'episodes'];
$USER = 'owner';

$data = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $data = json_decode(file_get_contents('php://input'), true);
    if (!is_array($data)) { http_response_code(400); die(json_encode(['error' => 'bad_json'])); }
}
$token = isset($_GET['token']) ? $_GET['token'] : (isset($data['token']) ? $data['token'] : '');
if ($token !== OWNER_TOKEN) { http_response_code(401); die(json_encode(['error' => 'unauthorized'])); }

$conn = db_connect();

if ($data === null) {
    $stmt = $conn->prepare('SELECT field, data, rev FROM user_data WHERE user_id = ?');
    $stmt->bind_param('s', $USER);
    $stmt->execute();
    $res = $stmt->get_result();
    $out = new stdClass();
    while ($row = $res->fetch_assoc()) $out->{$row['field']} = ['data' => $row['data'], 'rev' => intval($row['rev'])];
    echo json_encode(['ok' => true, 'fields' => $out], JSON_UNESCAPED_UNICODE);
    exit;
}

if (!isset($data['field']) || !in_array($data['field'], $FIELDS, true)) {
    http_response_code(400); die(json_encode(['error' => 'bad_field']));
}
if (!isset($data['data']) || !is_string($data['data']) || json_decode($data['data']) === null) {
    http_response_code(400); die(json_encode(['error' => 'bad_data']));
}

$stmt = $conn->prepare(
    'INSERT INTO user_data (user_id, field, data, rev) VALUES (?, ?, ?, 1)
     ON DUPLICATE KEY UPDATE data = VALUES(data), rev = rev + 1'
);
$stmt->bind_param('sss', $USER, $data['field'], $data['data']);
if (!$stmt->execute()) { http_response_code(500); die(json_encode(['error' => 'save_failed'])); }

$stmt = $conn->prepare('SELECT rev FROM user_data WHERE user_id = ? AND field = ?');
$stmt->bind_param('ss', $USER, $data['field']);
$stmt->execute();
$rev = intval($stmt->get_result()->fetch_assoc()['rev']);
echo json_encode(['ok' => true, 'rev' => $rev]);
