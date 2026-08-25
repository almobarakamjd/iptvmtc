/* مشغّل الفيديو: AVPlay على التلفزيون، و<video> في المتصفح أثناء التطوير.
   على أندرويد التشغيل الفعلي يتم خارجياً بمشغل مثبَّت على الجهاز (VLC/MX Player عبر app.js) —
   هذا الملف يُستخدم فقط لتلفزيونات Tizen وللاختبار داخل متصفح تطوير عادي. */
var Player = (function () {
  var onTV = typeof webapis !== 'undefined' && webapis.avplay;
  var statusCb = function () {};
  var currentUrl = null;
  var recorder = { active: false, xhr: null, chunks: [], path: null };
  var hls = null; // مشغّل hls.js لبث m3u8 في متصفح تطوير لا يدعم HLS أصلاً

  function setStatus(t) { statusCb(t); }
  function v() { return document.getElementById('html5-player'); }

  function playAV(url) {
    try { webapis.avplay.close(); } catch (e) {}
    webapis.avplay.open(url);
    webapis.avplay.setDisplayRect(0, 0, 1920, 1080);
    webapis.avplay.setListener({
      onbufferingstart: function () { setStatus('جارٍ التحميل…'); },
      onbufferingcomplete: function () { setStatus(''); },
      onstreamcompleted: function () { setStatus('انتهى العرض'); },
      onerror: function (e) { setStatus('خطأ في التشغيل: ' + e); }
    });
    webapis.avplay.prepareAsync(function () {
      webapis.avplay.play();
    }, function (e) { setStatus('تعذر تشغيل البث'); });
  }

  function destroyHls() {
    if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
  }

  function playHTML5(url) {
    var el = v();
    el.classList.remove('hidden');
    destroyHls();
    el.onerror = function () { setStatus('المتصفح لا يدعم هذه الصيغة — الاختبار الكامل على التلفزيون'); };
    el.onplaying = function () { setStatus(''); };

    var isM3u8 = /\.m3u8(\?.*)?$/i.test(url);
    var nativeHls = el.canPlayType('application/vnd.apple.mpegurl');
    if (isM3u8 && !nativeHls && typeof Hls !== 'undefined' && Hls.isSupported()) {
      hls = new Hls();
      hls.on(Hls.Events.ERROR, function (evt, data) {
        if (data.fatal) setStatus('خطأ في تشغيل البث: ' + data.type);
      });
      hls.loadSource(url);
      hls.attachMedia(el);
      el.play().catch(function () {});
    } else {
      el.src = url;
      el.play().catch(function () {});
    }
  }

  /* التسجيل على فلاش USB: يقرأ رابط البث الخام (ts) على شكل تدفق بيانات
     ويكتبه مباشرة إلى ملف على الفلاش أولاً بأول، دون تحميله كاملاً في الذاكرة */
  function pickUsbFolder(cb, err) {
    if (typeof tizen === 'undefined' || !tizen.filesystem) { err('التسجيل متاح على التلفزيون فقط'); return; }
    tizen.filesystem.listStorages(function (storages) {
      var usb = storages.filter(function (s) { return s.type === 'EXTERNAL' && s.state === 'MOUNTED'; });
      if (!usb.length) { err('لا يوجد فلاش موصول'); return; }
      tizen.filesystem.resolve(usb[0].label, function (dir) { cb(dir); }, function () { err('تعذر فتح الفلاش'); }, 'rw');
    }, function () { err('تعذر الوصول لوحدات التخزين'); });
  }

  function rawRecordUrl(url) {
    // بعض السيرفرات تعطي تدفق ts خام على نفس مسار m3u8 بتغيير الامتداد
    return url.replace(/\.m3u8(\?.*)?$/, '.ts$1');
  }

  return {
    onStatus: function (cb) { statusCb = cb; },
    play: function (url) {
      currentUrl = url;
      setStatus('جارٍ التحميل…');
      if (onTV) playAV(url); else playHTML5(url);
    },
    /* بدء التسجيل: يكتب البث الحالي إلى ملف على الفلاش باسم يحوي الوقت */
    startRecording: function (niceName, onProgress, onError) {
      if (recorder.active) return;
      if (!currentUrl) { onError('لا يوجد بث قيد التشغيل'); return; }
      pickUsbFolder(function (dir) {
        var fname = (niceName || 'تسجيل').replace(/[\\/:*?"<>|]/g, '_') + '_' +
          new Date().toISOString().replace(/[:.]/g, '-') + '.ts';
        var recDir;
        try { recDir = dir.getFile('Recordings'); } catch (e) {}
        if (!recDir) {
          try { recDir = dir.createDirectory('Recordings'); } catch (e2) { recDir = dir; }
        }
        var file;
        try { file = recDir.createFile(fname); } catch (e) { onError('تعذر إنشاء ملف التسجيل'); return; }

        recorder.active = true;
        recorder.path = recDir.fullPath + '/' + fname;
        var url = rawRecordUrl(currentUrl);
        var written = 0;

        fetch(url).then(function (resp) {
          if (!resp.ok || !resp.body) throw new Error('تعذر فتح البث للتسجيل');
          var reader = resp.body.getReader();
          recorder._reader = reader;
          function pump() {
            if (!recorder.active) { try { reader.cancel(); } catch (e) {} return; }
            reader.read().then(function (res) {
              if (res.done) { Player.stopRecording(); return; }
              try {
                file.openStream('a', function (fs) {
                  fs.write(Array.prototype.slice.call(res.value));
                  fs.close();
                  written += res.value.length;
                  onProgress && onProgress(written);
                  pump();
                }, function () { onError('خطأ في الكتابة على الفلاش'); recorder.active = false; }, 'BINARY');
              } catch (e) { onError('خطأ في الكتابة على الفلاش'); recorder.active = false; }
            }, function () { if (recorder.active) onError('انقطع تدفق البث'); recorder.active = false; });
          }
          pump();
        }).catch(function (e) { recorder.active = false; onError('تعذر بدء التسجيل: ' + e.message); });
      }, onError);
    },
    stopRecording: function () {
      recorder.active = false;
      try { recorder._reader && recorder._reader.cancel(); } catch (e) {}
    },
    isRecording: function () { return recorder.active; },
    recordingPath: function () { return recorder.path; },
    stop: function () {
      if (onTV) {
        try { webapis.avplay.stop(); webapis.avplay.close(); } catch (e) {}
      } else {
        destroyHls();
        var el = v();
        el.pause(); el.removeAttribute('src'); el.load();
        el.classList.add('hidden');
      }
    },
    togglePause: function () {
      if (onTV) {
        try {
          var st = webapis.avplay.getState();
          if (st === 'PLAYING') webapis.avplay.pause(); else webapis.avplay.play();
        } catch (e) {}
      } else {
        var el = v();
        if (el.paused) el.play(); else el.pause();
      }
    },
    /* تقديم/إرجاع بالثواني (موجب = تقديم) */
    seek: function (deltaSec) {
      if (onTV) {
        try {
          if (deltaSec >= 0) webapis.avplay.jumpForward(deltaSec * 1000);
          else webapis.avplay.jumpBackward(-deltaSec * 1000);
        } catch (e) {}
      } else {
        var el = v();
        if (isFinite(el.duration)) el.currentTime = Math.max(0, Math.min(el.duration - 1, el.currentTime + deltaSec));
      }
    },
    /* الزوم: تكبير الصورة مع قصّ الأطراف */
    setZoom: function (f) {
      if (onTV) {
        try {
          var w = Math.round(1920 * f), h = Math.round(1080 * f);
          webapis.avplay.setDisplayRect(Math.round((1920 - w) / 2), Math.round((1080 - h) / 2), w, h);
          return true;
        } catch (e) {
          try { webapis.avplay.setDisplayRect(0, 0, 1920, 1080); } catch (e2) {}
          return false;
        }
      }
      var el = v();
      el.style.transformOrigin = 'center center';
      el.style.transform = f === 1 ? '' : 'scale(' + f + ')';
      return true;
    },
    /* الانتقال إلى وقت محدد (بالثواني من البداية) */
    seekTo: function (sec) {
      if (onTV) {
        try { webapis.avplay.seekTo(sec * 1000); } catch (e) {}
      } else {
        var el = v();
        if (isFinite(el.duration)) el.currentTime = Math.max(0, Math.min(el.duration - 1, sec));
      }
    },
    /* سرعة التشغيل: 1.0 حتى 3.0 */
    setSpeed: function (rate) {
      if (onTV) {
        try { webapis.avplay.setSpeed(rate); return true; }
        catch (e) {
          // بعض التلفزيونات تقبل السرعات الصحيحة فقط
          try { webapis.avplay.setSpeed(Math.max(1, Math.round(rate))); return false; }
          catch (e2) { return false; }
        }
      }
      v().playbackRate = rate;
      return true;
    },
    /* الوقت الحالي والمدة بالثواني */
    time: function () {
      if (onTV) {
        try {
          return { cur: webapis.avplay.getCurrentTime() / 1000, dur: webapis.avplay.getDuration() / 1000 };
        } catch (e) { return { cur: 0, dur: 0 }; }
      }
      var el = v();
      return { cur: el.currentTime || 0, dur: isFinite(el.duration) ? el.duration : 0 };
    }
  };
})();
