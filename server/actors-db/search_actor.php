<?php
// بحث عن ممثل بالاسم (مطابقة جزئية) وإرجاع كل أعماله المخزَّنة.
// طلب GET: search_actor.php?token=...&name=...&subscription_id=p1 (subscription_id اختياري)

require __DIR__ . '/config.php';
header('Content-Type: application/json; charset=utf-8');
require_token();

$name = isset($_GET['name']) ? trim($_GET['name']) : '';
if ($name === '') { http_response_code(400); die(json_encode(['error' => 'missing_name'])); }

$conn = db_connect();

$sql = 'SELECT t.id, t.kind, t.xtream_id, t.name, t.year, t.poster, t.rating, t.subscription_id
        FROM actor_title at
        JOIN actors a ON a.id = at.actor_id
        JOIN titles t ON t.id = at.title_id
        WHERE a.name LIKE CONCAT(\'%\', ?, \'%\')';
$params = [$name];
$types = 's';
if (!empty($_GET['subscription_id'])) {
    $sql .= ' AND t.subscription_id = ?';
    $params[] = $_GET['subscription_id'];
    $types .= 's';
}
$sql .= ' ORDER BY t.rating DESC, t.year DESC LIMIT 300';

$stmt = $conn->prepare($sql);
$stmt->bind_param($types, ...$params);
$stmt->execute();
$res = $stmt->get_result();

$titles = [];
while ($row = $res->fetch_assoc()) $titles[] = $row;

$stmt->close();
$conn->close();
echo json_encode(['ok' => true, 'count' => count($titles), 'titles' => $titles]);
