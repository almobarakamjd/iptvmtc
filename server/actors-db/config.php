<?php
// إعدادات الاتصال بقاعدة البيانات — عدّل القيم الأربعة التالية حسب ما أنشأته بلوحة hPanel
// (قسم "قواعد البيانات" / MySQL Databases). غالباً DB_HOST يبقى 'localhost'.
define('DB_HOST', 'localhost');
define('DB_NAME', 'CHANGE_ME_db_name');
define('DB_USER', 'CHANGE_ME_db_user');
define('DB_PASS', 'CHANGE_ME_db_password');

// رمز حماية بسيط يجب أن يُرسَل مع كل طلب (?token=...) — غيّره لقيمة سرية خاصة بك،
// ونفس القيمة يجب أن تُضبط بإعدادات تطبيق myTv+ (لا يزال هذا الربط غير مُنفَّذ بالتطبيق بعد).
define('API_TOKEN', 'CHANGE_ME_secret_token');

function db_connect() {
    $conn = new mysqli(DB_HOST, DB_USER, DB_PASS, DB_NAME);
    if ($conn->connect_error) {
        http_response_code(500);
        die(json_encode(['error' => 'db_connect_failed']));
    }
    $conn->set_charset('utf8mb4');
    return $conn;
}

function require_token() {
    if (!isset($_REQUEST['token']) || $_REQUEST['token'] !== API_TOKEN) {
        http_response_code(401);
        die(json_encode(['error' => 'unauthorized']));
    }
}
