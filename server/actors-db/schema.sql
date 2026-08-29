-- قاعدة بيانات "الممثل ← أعماله" لتطبيق myTv+
-- شغّلها مرة واحدة عبر phpMyAdmin بلوحة hPanel بعد إنشاء قاعدة بيانات فارغة.

CREATE TABLE IF NOT EXISTS titles (
  id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  subscription_id VARCHAR(50) NOT NULL,       -- مثلاً 'p1' لـ"الاشتراك الأول" — يمنع تعارض نفس xtream_id بين اشتراكين مختلفين
  kind ENUM('movie','series') NOT NULL,
  xtream_id INT UNSIGNED NOT NULL,            -- stream_id أو series_id الأصلي من Xtream
  name VARCHAR(500) NOT NULL,
  year SMALLINT UNSIGNED NULL,
  studio VARCHAR(255) NULL,                   -- شركة الإنتاج (حقل studio بـXtream، غالباً فارغ)
  director TEXT NULL,                         -- نص خام، قد يحوي أكثر من اسم
  rating DECIMAL(3,1) NULL,
  country VARCHAR(100) NULL,                  -- الجنسية (حقل country بـXtream إن وُجد)
  lang VARCHAR(10) NULL,                      -- لغة الفيلم، مُستنتجة من بادئة الاسم (EN- / AR- ...)
  genre VARCHAR(255) NULL,
  plot TEXT NULL,
  duration_secs INT UNSIGNED NULL,
  poster VARCHAR(500) NULL,
  container_extension VARCHAR(20) NULL,
  category_id VARCHAR(50) NULL,
  category_name VARCHAR(255) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_title (subscription_id, kind, xtream_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS actors (
  id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  UNIQUE KEY uniq_actor_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS actor_title (
  actor_id INT UNSIGNED NOT NULL,
  title_id INT UNSIGNED NOT NULL,
  PRIMARY KEY (actor_id, title_id),
  FOREIGN KEY (actor_id) REFERENCES actors(id) ON DELETE CASCADE,
  FOREIGN KEY (title_id) REFERENCES titles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
