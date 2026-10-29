/* مزامنة مستخدم أبي فيصل المخفي: المفضلة والمشاهدات الأخيرة والقوائم المخصّصة واحدة على كل
   الأجهزة (TCL، MTC، غيرها) عبر user_data.php على استضافة hPanel. الضيف لا يُزامَن أبداً.
   - الدخول: يجلب نسخة الخادم ويكتبها على الجهاز. أول دخول على الإطلاق (الخادم فارغ) يبذر
     بيانات الضيف الحالية على هذا الجهاز، لأنها بيانات أبي فيصل قبل وجود المستخدم المخفي.
   - كل تعديل يُرفع بعد ثانية ونصف (يُجمع التعديلات المتتالية في طلب واحد).
   - تعديل بلا اتصال يبقى معلَّماً «لم يُرفع» ويُرفع قبل أي جلب لاحق، فلا تمحوه نسخة الخادم. */
var UserSync = (function () {
  var BASE = 'https://amjd.law/apps/ipv/user_data.php';
  var PROGRESS = 'https://amjd.law/apps/ipv/progress.php';
  var TOKEN = 'mytvplus_owner_Vb8rT3qK6wNz';
  var DIRTY_KEY = 'aftv_owner_dirty_v1';
  var timers = {};
  var lastPull = 0;

  function dirty() { try { return JSON.parse(localStorage.getItem(DIRTY_KEY)) || {}; } catch (e) { return {}; } }
  function setDirty(field, on) {
    var d = dirty();
    if (on) d[field] = 1; else delete d[field];
    try { localStorage.setItem(DIRTY_KEY, JSON.stringify(d)); } catch (e) {}
  }

  function active() { return Storage.getUser() === 'owner'; }

  // نص عادي لا application/json: يتجنب طلب OPTIONS المسبق من صفحة file://
  function upload(field) {
    var raw = Storage.rawField(field);
    if (raw == null) { setDirty(field, false); return Promise.resolve(); }
    return fetch(BASE, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: JSON.stringify({ token: TOKEN, field: field, data: raw })
    }).then(function (r) {
      if (!r.ok) throw new Error('رفض الخادم الحفظ (' + r.status + ')');
      setDirty(field, false);
    });
  }

  function pushSoon(field) {
    if (!active()) return;
    setDirty(field, true);
    clearTimeout(timers[field]);
    timers[field] = setTimeout(function () { upload(field).catch(function () {}); }, 1500);
  }

  function pushDirty() {
    return Promise.all(Object.keys(dirty()).map(function (f) { clearTimeout(timers[f]); return upload(f); }));
  }

  /* يرفع المعلَّق أولاً ثم يجلب. onDone(changedSomething) */
  function pull(onDone, onError) {
    pushDirty().then(function () {
      return fetch(BASE + '?token=' + encodeURIComponent(TOKEN)).then(function (r) {
        if (!r.ok) throw new Error('تعذر الاتصال بالخادم (' + r.status + ')');
        return r.json();
      });
    }).then(function (res) {
      lastPull = Date.now();
      var fields = (res && res.fields) || {};
      var changedAny = false;
      if (!Object.keys(fields).length) {
        // أول دخول على الإطلاق: بذرة من بيانات الضيف على هذا الجهاز
        Storage.syncFields.forEach(function (f) {
          var g = Storage.guestRaw(f);
          if (g != null && Storage.rawField(f) == null) { Storage.setRawField(f, g); changedAny = true; }
          if (Storage.rawField(f) != null) setDirty(f, true);
        });
        return pushDirty().then(function () { return changedAny; });
      }
      Storage.syncFields.forEach(function (f) {
        if (!fields[f] || dirty()[f]) return;
        if (Storage.rawField(f) !== fields[f].data) { Storage.setRawField(f, fields[f].data); changedAny = true; }
      });
      return changedAny;
    }).then(function (changedAny) { if (onDone) onDone(changedAny); })
      .catch(function (e) { if (onError) onError((e && e.message) || 'خطأ غير متوقع'); });
  }

  Storage.onChange(pushSoon);

  return {
    active: active,
    /* بيانات المزامنة التي تُرسل مع كل تشغيل إلى Player+ ليرفع موضع التوقف */
    playerSync: function (sub) { return active() ? { url: PROGRESS, token: TOKEN, sub: sub } : null; },
    /* مواضع حلقات مسلسل من الخادم: onDone({streamId: {pos_ms, dur_ms, done, season, episode, at}}) — فارغ للضيف أو عند الخطأ */
    seriesProgress: function (sub, seriesId, onDone) {
      if (!active()) { onDone({}); return; }
      fetch(PROGRESS + '?token=' + encodeURIComponent(TOKEN) + '&sub=' + encodeURIComponent(sub) +
        '&series_id=' + encodeURIComponent(seriesId))
        .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
        .then(function (res) {
          var map = {};
          ((res && res.items) || []).forEach(function (x) { map[String(x.stream_id)] = x; });
          onDone(map);
        }).catch(function () { onDone({}); });
    },
    enter: function (onDone, onError) { Storage.setUser('owner'); pull(onDone, onError); },
    leave: function () { pushDirty().catch(function () {}); Storage.setUser('guest'); },
    pull: pull,
    /* جلب خفيف عند الرجوع للرئيسية أو للتطبيق — مرة كل 30 ثانية على الأكثر */
    refresh: function (onChanged) {
      if (!active() || Date.now() - lastPull < 30000) return;
      pull(function (ch) { if (ch && onChanged) onChanged(); });
    }
  };
})();
