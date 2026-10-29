-- بيانات مستخدم أبي فيصل المخفي في myTv+ (المفضلة، المشاهدات الأخيرة، القوائم المخصّصة)
-- تُزامَن بين كل الأجهزة (TCL، MTC، غيرها). شغّلها مرة واحدة عبر phpMyAdmin على القاعدة نفسها u317488478_iptv.

CREATE TABLE IF NOT EXISTS user_data (
  user_id VARCHAR(32) NOT NULL,             -- 'owner' حالياً (الضيف لا يُخزَّن هنا، يبقى على الجهاز)
  field VARCHAR(32) NOT NULL,               -- favs / recent / lists / episodes
  data MEDIUMTEXT NOT NULL,                 -- JSON كما يحفظه التطبيق في localStorage
  rev INT UNSIGNED NOT NULL DEFAULT 1,      -- يزيد مع كل حفظ — ليعرف الجهاز أن نسخته قديمة
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, field)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- موضع التوقف لكل فيلم وحلقة (يرفعه Player+ أثناء المشاهدة) — بالملّي ثانية، فيُعرض ساعات:دقائق:ثوانٍ.
-- series_id/season/episode للحلقات: منها يعرف myTv+ آخر حلقة شوهدت والحلقات المكتملة في كل موسم.
CREATE TABLE IF NOT EXISTS progress (
  user_id VARCHAR(32) NOT NULL,
  sub VARCHAR(50) NOT NULL,                 -- الاشتراك (p1، p2...) — أرقام Xtream تختلف بين الاشتراكات
  kind ENUM('movie','series') NOT NULL,
  stream_id VARCHAR(32) NOT NULL,           -- رقم الفيلم أو الحلقة
  series_id VARCHAR(32) NULL,
  season SMALLINT UNSIGNED NULL,
  episode SMALLINT UNSIGNED NULL,
  pos_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
  dur_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
  done TINYINT(1) NOT NULL DEFAULT 0,       -- شوهد حتى آخر دقيقتين
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, sub, kind, stream_id),
  KEY by_series (user_id, sub, series_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
