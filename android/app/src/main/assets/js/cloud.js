/* نسخ احتياطي سحابي: يحفظ كل بيانات التطبيق (الاشتراكات، المفضلة، المشاهدات الأخيرة) على
   خدمة تخزين حرة بلا تسجيل (kvdb.io) — مشفّرة بكلمة مرور يختارها المستخدم (AES-GCM عبر Web Crypto)
   قبل الرفع، فلا تُقرأ بياناتك (روابط/كلمات مرور الاشتراكات) حتى لو عرف أحد رقم مكان التخزين.
   ملاحظة مهمة: لا يحفظ "مكان التوقف" داخل كل فيلم/مسلسل — التشغيل الآن يتم خارجياً عبر
   VLC/MX Player، وهما يحفظان تقدّم المشاهدة داخل كل تطبيق بشكل مستقل عنا. */
var Cloud = (function () {
  var BASE = 'https://kvdb.io/';
  var BUCKET_KEY = 'aftv_cloud_bucket';
  var DATA_NAME = 'backup';

  /* رمز مكان التخزين: يُقرأ أولاً من ملف على ذاكرة الجهاز المشتركة (يبقى بعد حذف التطبيق
     وإعادة تثبيته — انظر AndroidStorage في MainActivity.java)، وإلا من localStorage
     (يُمسح مع حذف التطبيق؛ يُستخدم فقط كاحتياط عند الاختبار بمتصفح عادي بلا هذا الجسر) */
  function readPersistedBucket() {
    try { if (typeof AndroidStorage !== 'undefined') { var v = AndroidStorage.readBackupId(); if (v) return v; } } catch (e) {}
    try { return localStorage.getItem(BUCKET_KEY) || ''; } catch (e) { return ''; }
  }
  function writePersistedBucket(id) {
    try { localStorage.setItem(BUCKET_KEY, id); } catch (e) {}
    try { if (typeof AndroidStorage !== 'undefined') AndroidStorage.writeBackupId(id); } catch (e) {}
  }

  function getBucket() {
    return new Promise(function (resolve, reject) {
      var b = readPersistedBucket();
      if (b) { resolve(b); return; }
      // kvdb.io صار يشترط أي نص بريد إلكتروني بجسم الطلب عند إنشاء أول مكان تخزين
      // (بلا أي تسجيل أو تحقق فعلي — مجرد حقل مطلوب) وإلا يرفض الطلب بخطأ 500
      fetch(BASE, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'email=aftv-' + Date.now() + '@aftv.local'
      }).then(function (r) {
        if (!r.ok) throw new Error('تعذر إنشاء مكان تخزين');
        return r.text();
      }).then(function (id) {
        id = id.trim();
        writePersistedBucket(id);
        resolve(id);
      }).catch(reject);
    });
  }

  function buf2b64(buf) { return btoa(String.fromCharCode.apply(null, new Uint8Array(buf))); }
  function b642arr(b64) {
    var bin = atob(b64);
    var arr = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return arr;
  }

  function deriveKey(password, salt) {
    var enc = new TextEncoder();
    return crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveKey'])
      .then(function (baseKey) {
        return crypto.subtle.deriveKey(
          { name: 'PBKDF2', salt: salt, iterations: 100000, hash: 'SHA-256' },
          baseKey, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']
        );
      });
  }

  function encrypt(text, password) {
    var salt = crypto.getRandomValues(new Uint8Array(16));
    var iv = crypto.getRandomValues(new Uint8Array(12));
    return deriveKey(password, salt).then(function (key) {
      var enc = new TextEncoder();
      return crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, enc.encode(text)).then(function (cipher) {
        return { salt: buf2b64(salt), iv: buf2b64(iv), data: buf2b64(cipher) };
      });
    });
  }

  function decrypt(payload, password) {
    var salt = b642arr(payload.salt);
    var iv = b642arr(payload.iv);
    var data = b642arr(payload.data);
    return deriveKey(password, salt).then(function (key) {
      return crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv }, key, data).then(function (plainBuf) {
        return new TextDecoder().decode(plainBuf);
      });
    });
  }

  return {
    hasBucket: function () { return !!readPersistedBucket(); },
    getBucketId: function () { return readPersistedBucket(); },
    /* لاستيراد نسخة على جهاز جديد (نادراً ما تُستخدم الآن — الملف على ذاكرة الجهاز يجدها تلقائياً
       إلا إذا كان الاستيراد على جهاز آخر مختلف تماماً) */
    setBucketId: function (id) { writePersistedBucket((id || '').trim()); },

    /* يرفع نسخة مشفّرة من كل بيانات التطبيق الحالية. onDone(bucketId) — يجب حفظ هذا الرمز
       لاستخدامه لاحقاً عند الاستيراد على جهاز آخر (مثلاً بعد تركيب التطبيق من جديد) */
    backup: function (password, onDone, onError) {
      var bucketId;
      getBucket().then(function (bucket) {
        bucketId = bucket;
        return encrypt(JSON.stringify(Storage.exportAll()), password).then(function (payload) {
          return fetch(BASE + bucket + '/' + DATA_NAME, { method: 'POST', body: JSON.stringify(payload) });
        });
      }).then(function (r) {
        if (!r.ok) throw new Error('فشل رفع النسخة الاحتياطية');
        onDone(bucketId);
      }).catch(function (e) { onError((e && e.message) || 'خطأ غير متوقع'); });
    },
    /* يجلب النسخة المحفوظة (من رمز الجهاز المحفوظ محلياً — اربطه أولاً عبر setBucketId على جهاز جديد)
       ويفكّها بكلمة المرور، ثم يستبدل بها بيانات الجهاز الحالية */
    restore: function (password, onDone, onError) {
      getBucket().then(function (bucket) {
        return fetch(BASE + bucket + '/' + DATA_NAME).then(function (r) {
          if (!r.ok) throw new Error('لا توجد نسخة محفوظة بهذا الرمز');
          return r.json();
        }).then(function (payload) {
          return decrypt(payload, password).catch(function () {
            throw new Error('كلمة المرور غير صحيحة، أو النسخة تالفة');
          });
        });
      }).then(function (json) {
        Storage.importAll(JSON.parse(json));
        onDone();
      }).catch(function (e) { onError((e && e.message) || 'خطأ غير متوقع'); });
    }
  };
})();
