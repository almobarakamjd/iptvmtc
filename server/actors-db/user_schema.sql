-- بيانات مستخدم أبي فيصل المخفي في myTv+ (المفضلة، المشاهدات الأخيرة، القوائم المخصّصة)
-- تُزامَن بين كل الأجهزة (TCL، MTC، غيرها). شغّلها مرة واحدة عبر phpMyAdmin على القاعدة نفسها u317488478_iptv.

CREATE TABLE IF NOT EXISTS user_data (
  user_id VARCHAR(32) NOT NULL,             -- 'owner' حالياً (الضيف لا يُخزَّن هنا، يبقى على الجهاز)
  field VARCHAR(32) NOT NULL,               -- favs / recent / lists
  data MEDIUMTEXT NOT NULL,                 -- JSON كما يحفظه التطبيق في localStorage
  rev INT UNSIGNED NOT NULL DEFAULT 1,      -- يزيد مع كل حفظ — ليعرف الجهاز أن نسخته قديمة
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, field)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
