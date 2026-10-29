<?php
// موضع التوقف لمستخدم أبي فيصل المخفي (جدول progress في user_schema.sql).
//
// عمل واحد:   GET  progress.php?token=...&sub=p1&kind=movie&stream_id=123   ← {"ok":true,"item":{"pos_ms":...,"dur_ms":...,"done":0}|null}
// حلقات مسلسل: GET  progress.php?token=...&sub=p1&series_id=55             ← {"ok":true,"items":[{"stream_id":..,"season":..,"episode":..,"pos_ms":..,"dur_ms":..,"done":..,"at":<unix>}]}
// حفظ:        POST جسمه JSON {"token","sub","kind","stream_id","series_id","season","episode","pos_ms","dur_ms"}
// «شوهد» = وصل لآخر دقيقتين؛ وأقل من دقيقة يُحفظ صفراً (بدأ ولم يتقدم) كما يفعل Player+ محلياً.

require __DIR__ . '/config.php';
header('Content-Type: application/json; charset=utf-8');

$USER = 'owner';
$data = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $data = json_decode(file_get_contents('php://input'), true);
    if (!is_array($data)) { http_response_code(400); die(json_encode(['error' => 'bad_json'])); }
}
$in = $data === null ? $_GET : $data;
if (!isset($in['token']) || $in['token'] !== OWNER_TOKEN) { http_response_code(401); die(json_encode(['error' => 'unauthorized'])); }
$sub = isset($in['sub']) ? strval($in['sub']) : '';
if ($sub === '') { http_response_code(400); die(json_encode(['error' => 'missing_sub'])); }

$conn = db_connect();

if ($data === null && isset($in['series_id'])) {
    $stmt = $conn->prepare('SELECT stream_id, season, episode, pos_ms, dur_ms, done, UNIX_TIMESTAMP(updated_at) AS at
                            FROM progress WHERE user_id = ? AND sub = ? AND series_id = ?');
    $sid = strval($in['series_id']);
    $stmt->bind_param('sss', $USER, $sub, $sid);
    $stmt->execute();
    $res = $stmt->get_result();
    $items = [];
    while ($r = $res->fetch_assoc()) {
        $items[] = ['stream_id' => $r['stream_id'], 'season' => intval($r['season']), 'episode' => intval($r['episode']),
                    'pos_ms' => intval($r['pos_ms']), 'dur_ms' => intval($r['dur_ms']), 'done' => intval($r['done']), 'at' => intval($r['at'])];
    }
    echo json_encode(['ok' => true, 'items' => $items]);
    exit;
}

$kind = isset($in['kind']) ? $in['kind'] : '';
$streamId = isset($in['stream_id']) ? strval($in['stream_id']) : '';
if (!in_array($kind, ['movie', 'series'], true) || $streamId === '') {
    http_response_code(400); die(json_encode(['error' => 'missing_item']));
}

if ($data === null) {
    $stmt = $conn->prepare('SELECT pos_ms, dur_ms, done FROM progress WHERE user_id = ? AND sub = ? AND kind = ? AND stream_id = ?');
    $stmt->bind_param('ssss', $USER, $sub, $kind, $streamId);
    $stmt->execute();
    $r = $stmt->get_result()->fetch_assoc();
    echo json_encode(['ok' => true, 'item' => $r ? ['pos_ms' => intval($r['pos_ms']), 'dur_ms' => intval($r['dur_ms']), 'done' => intval($r['done'])] : null]);
    exit;
}

$pos = max(0, intval(isset($data['pos_ms']) ? $data['pos_ms'] : 0));
$dur = max(0, intval(isset($data['dur_ms']) ? $data['dur_ms'] : 0));
$done = ($dur > 0 && $pos > $dur - 120000) ? 1 : 0;
if ($done || $pos < 60000) $pos = 0;
$seriesId = isset($data['series_id']) && $data['series_id'] !== '' ? strval($data['series_id']) : null;
$season = isset($data['season']) ? intval($data['season']) : null;
$episode = isset($data['episode']) ? intval($data['episode']) : null;

// «شوهد» لا يُلغى بإعادة فتح الحلقة من بدايتها؛ يبقى حتى تُشاهد من جديد إلى منتصفها
$stmt = $conn->prepare(
    'INSERT INTO progress (user_id, sub, kind, stream_id, series_id, season, episode, pos_ms, dur_ms, done)
     VALUES (?,?,?,?,?,?,?,?,?,?)
     ON DUPLICATE KEY UPDATE series_id = COALESCE(VALUES(series_id), series_id), season = COALESCE(VALUES(season), season),
        episode = COALESCE(VALUES(episode), episode), pos_ms = VALUES(pos_ms),
        dur_ms = IF(VALUES(dur_ms) > 0, VALUES(dur_ms), dur_ms),
        done = IF(VALUES(done) = 1, 1, IF(VALUES(pos_ms) > 0, 0, done))'
);
$stmt->bind_param('sssssiiiii', $USER, $sub, $kind, $streamId, $seriesId, $season, $episode, $pos, $dur, $done);
if (!$stmt->execute()) { http_response_code(500); die(json_encode(['error' => 'save_failed'])); }
echo json_encode(['ok' => true, 'done' => $done]);
