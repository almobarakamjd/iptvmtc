<?php
// نقطة استقبال بيانات فيلم/مسلسل واحد وتخزينها — يستدعيها تطبيق myTv+ (أو سكربت الزحف
// الخلفي) بطلب POST بجسم JSON. أي حقل غير متوفر يُرسَل null فيبقى NULL بقاعدة البيانات.
//
// مثال جسم الطلب:
// {
//   "token": "...", "subscription_id": "p1", "kind": "movie", "xtream_id": 574006,
//   "name": "...", "year": 2022, "studio": null, "director": "...", "rating": 7.5,
//   "country": null, "lang": "EN", "genre": "Drama", "plot": "...", "duration_secs": 6120,
//   "poster": "https://...", "container_extension": "mkv",
//   "category_id": "281", "category_name": "Replay Matches",
//   "cast": "ممثل أول, ممثل ثاني, ..."
// }

require __DIR__ . '/config.php';
header('Content-Type: application/json; charset=utf-8');

$raw = file_get_contents('php://input');
$data = json_decode($raw, true);
if (!is_array($data)) { http_response_code(400); die(json_encode(['error' => 'bad_json'])); }

if (!isset($_REQUEST['token']) && isset($data['token'])) $_REQUEST['token'] = $data['token'];
require_token();

foreach (['subscription_id', 'kind', 'xtream_id', 'name'] as $req) {
    if (empty($data[$req]) && $data[$req] !== 0) {
        http_response_code(400);
        die(json_encode(['error' => 'missing_field', 'field' => $req]));
    }
}
if (!in_array($data['kind'], ['movie', 'series'], true)) {
    http_response_code(400);
    die(json_encode(['error' => 'bad_kind']));
}

$conn = db_connect();

$stmt = $conn->prepare(
    'INSERT INTO titles
        (subscription_id, kind, xtream_id, name, year, studio, director, rating, country,
         lang, genre, plot, duration_secs, poster, container_extension, category_id, category_name)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
     ON DUPLICATE KEY UPDATE
        name=VALUES(name), year=VALUES(year), studio=VALUES(studio), director=VALUES(director),
        rating=VALUES(rating), country=VALUES(country), lang=VALUES(lang), genre=VALUES(genre),
        plot=VALUES(plot), duration_secs=VALUES(duration_secs), poster=VALUES(poster),
        container_extension=VALUES(container_extension), category_id=VALUES(category_id),
        category_name=VALUES(category_name), id=LAST_INSERT_ID(id)'
);
if (!$stmt) { http_response_code(500); die(json_encode(['error' => 'sql_prepare_failed', 'detail' => $conn->error])); }
$year = isset($data['year']) ? intval($data['year']) : null;
$rating = isset($data['rating']) ? floatval($data['rating']) : null;
$duration = isset($data['duration_secs']) ? intval($data['duration_secs']) : null;
$stmt->bind_param(
    'ssisisssssisssss',
    $data['subscription_id'], $data['kind'], $data['xtream_id'], $data['name'],
    $year, $data['studio'], $data['director'], $rating, $data['country'],
    $data['lang'], $data['genre'], $data['plot'], $duration,
    $data['poster'], $data['container_extension'], $data['category_id'], $data['category_name']
);
$stmt->execute();
$titleId = $stmt->insert_id;
$stmt->close();

$addedActors = [];
if (!empty($data['cast'])) {
    $names = array_filter(array_map('trim', explode(',', $data['cast'])));
    $insA = $conn->prepare('INSERT IGNORE INTO actors (name) VALUES (?)');
    $selA = $conn->prepare('SELECT id FROM actors WHERE name = ?');
    $link = $conn->prepare('INSERT IGNORE INTO actor_title (actor_id, title_id) VALUES (?, ?)');
    if (!$insA || !$selA || !$link) { http_response_code(500); die(json_encode(['error' => 'sql_prepare_failed', 'detail' => $conn->error])); }
    foreach ($names as $name) {
        if ($name === '') continue;
        $insA->bind_param('s', $name);
        $insA->execute();
        $selA->bind_param('s', $name);
        $selA->execute();
        $selA->bind_result($actorId);
        if ($selA->fetch()) {
            $link->bind_param('ii', $actorId, $titleId);
            $link->execute();
            $addedActors[] = $name;
        }
        $selA->free_result();
    }
    $insA->close(); $selA->close(); $link->close();
}

$conn->close();
echo json_encode(['ok' => true, 'title_id' => $titleId, 'actors' => $addedActors]);
