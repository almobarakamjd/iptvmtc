/* الترجمات: تحليل SRT وعرضها، وتحميلها من رابط أو من فلاش USB */
var Subs = (function () {
  var cues = [];      // {start, end, text} بالثواني
  var active = false;

  function timeToSec(t) {
    var m = t.match(/(\d+):(\d+):(\d+)[,.](\d+)/);
    if (!m) return 0;
    return (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3]) + (+m[4]) / 1000;
  }

  function parseSRT(text) {
    var out = [];
    text = text.replace(/\r/g, '');
    var blocks = text.split(/\n\n+/);
    blocks.forEach(function (b) {
      var lines = b.split('\n').filter(function (l) { return l !== ''; });
      if (lines.length < 2) return;
      var ti = lines[0].indexOf('-->') >= 0 ? 0 : 1;
      if (!lines[ti] || lines[ti].indexOf('-->') < 0) return;
      var parts = lines[ti].split('-->');
      var txt = lines.slice(ti + 1).join('\n')
        .replace(/<[^>]+>/g, '')   // إزالة وسوم التنسيق
        .replace(/\{[^}]+\}/g, '');
      if (!txt) return;
      out.push({ start: timeToSec(parts[0]), end: timeToSec(parts[1]), text: txt });
    });
    out.sort(function (a, b) { return a.start - b.start; });
    return out;
  }

  function decodeBuffer(buf) {
    // نجرب UTF-8 أولاً، وإن كثرت رموز التلف نجرب ترميز ويندوز العربي
    try {
      var utf8 = new TextDecoder('utf-8', { fatal: false }).decode(buf);
      var bad = (utf8.match(/�/g) || []).length;
      if (bad < 5) return utf8;
      return new TextDecoder('windows-1256').decode(buf);
    } catch (e) {
      return new TextDecoder('utf-8').decode(buf);
    }
  }

  function loadFromUrl(url, cb, err) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.responseType = 'arraybuffer';
    xhr.timeout = 30000;
    xhr.onload = function () {
      cues = parseSRT(decodeBuffer(xhr.response));
      active = cues.length > 0;
      if (active) cb(cues.length); else err && err('الملف لا يحتوي ترجمة مفهومة');
    };
    xhr.onerror = xhr.ontimeout = function () { err && err('تعذر تحميل ملف الترجمة'); };
    xhr.send();
  }

  /* فحص الفلاش: يجمع ملفات srt من الجذر ومن مجلدات المستوى الأول */
  function listUsbFiles(cb, err) {
    if (typeof tizen === 'undefined' || !tizen.filesystem || !tizen.filesystem.listStorages) {
      err('قراءة الفلاش متاحة على التلفزيون فقط'); return;
    }
    tizen.filesystem.listStorages(function (storages) {
      var usb = storages.filter(function (s) {
        return s.type === 'EXTERNAL' && s.state === 'MOUNTED';
      });
      if (!usb.length) { err('لا يوجد فلاش موصول بالتلفزيون'); return; }
      var found = [], pending = 0;

      function scanDir(dir, depth) {
        pending++;
        dir.listFiles(function (files) {
          files.forEach(function (f) {
            if (f.isDirectory && depth < 2) scanDir(f, depth + 1);
            else if (f.isFile && /\.(srt|txt)$/i.test(f.name)) found.push(f);
          });
          if (--pending === 0) finish();
        }, function () { if (--pending === 0) finish(); });
      }

      function finish() {
        if (found.length) cb(found);
        else err('لا توجد ملفات ترجمة (srt) في الفلاش');
      }

      usb.forEach(function (s) {
        tizen.filesystem.resolve(s.label, function (dir) { scanDir(dir, 0); },
          function () { if (pending === 0) err('تعذر فتح الفلاش'); }, 'r');
      });
    }, function () { err('تعذر الوصول لوحدات التخزين'); });
  }

  function loadFromFile(file, cb, err) {
    // نقرأ عبر URI الملف لنتحكم بالترميز، وإن فشل نستخدم القراءة النصية
    var uri = null;
    try { uri = file.toURI(); } catch (e) {}
    if (uri) {
      loadFromUrl(uri, cb, function () { readAsText(); });
    } else readAsText();

    function readAsText() {
      file.readAsText(function (text) {
        cues = parseSRT(text);
        active = cues.length > 0;
        if (active) cb(cues.length); else err && err('الملف لا يحتوي ترجمة مفهومة');
      }, function () { err && err('تعذر قراءة الملف'); }, 'UTF-8');
    }
  }

  function textAt(sec) {
    if (!active) return '';
    for (var i = 0; i < cues.length; i++) {
      if (sec >= cues[i].start && sec <= cues[i].end) return cues[i].text;
      if (cues[i].start > sec) break;
    }
    return '';
  }

  return {
    loadFromUrl: loadFromUrl,
    listUsbFiles: listUsbFiles,
    loadFromFile: loadFromFile,
    textAt: textAt,
    off: function () { active = false; cues = []; },
    isActive: function () { return active; }
  };
})();
