/* عميل Xtream Codes API */
var Xtream = (function () {
  function api(profile, action, extra, cb, errCb, timeoutMs) {
    var url = profile.host.replace(/\/+$/, '') + '/player_api.php' +
      '?username=' + encodeURIComponent(profile.username) +
      '&password=' + encodeURIComponent(profile.password) +
      (action ? '&action=' + action : '') + (extra || '');
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.timeout = timeoutMs || 30000;
    xhr.onload = function () {
      try { cb(JSON.parse(xhr.responseText)); }
      catch (e) { errCb && errCb('رد غير مفهوم من السيرفر — قد تكون المكتبة كبيرة جداً على ذاكرة الجهاز'); }
    };
    xhr.onerror = xhr.ontimeout = function () { errCb && errCb('تعذر الاتصال بالسيرفر (أو استغرق وقتاً طويلاً جداً)'); };
    xhr.send();
  }
  /* جلب قوائم "الكل" (بلا تصنيف) قد يعيد عشرات آلاف السجلات في بعض الاشتراكات —
     يحتاج مهلة أطول بكثير من الطلبات العادية (تصنيف واحد) كي لا يفشل بمجرد التأخر */
  var BULK_TIMEOUT = 180000;

  function streamUrl(profile, kind, id, ext) {
    var host = profile.host.replace(/\/+$/, '');
    var cred = encodeURIComponent(profile.username) + '/' + encodeURIComponent(profile.password);
    if (kind === 'live') return host + '/live/' + cred + '/' + id + '.m3u8';
    if (kind === 'movie') return host + '/movie/' + cred + '/' + id + '.' + (ext || 'mp4');
    return host + '/series/' + cred + '/' + id + '.' + (ext || 'mp4');
  }

  /* رابط الإعادة (catch-up): يبدأ البث من لحظة سابقة بعدد دقائق محدد
     الصيغة القياسية: /streaming/timeshift.php?...&start=YYYY-MM-DD:HH-MM&duration=دقائق */
  function timeshiftUrl(profile, streamId, startDate, durationMin) {
    var host = profile.host.replace(/\/+$/, '');
    function p2(n) { return (n < 10 ? '0' : '') + n; }
    var s = startDate.getFullYear() + '-' + p2(startDate.getMonth() + 1) + '-' + p2(startDate.getDate()) +
      ':' + p2(startDate.getHours()) + '-' + p2(startDate.getMinutes());
    return host + '/streaming/timeshift.php?username=' + encodeURIComponent(profile.username) +
      '&password=' + encodeURIComponent(profile.password) +
      '&stream=' + streamId + '&start=' + s + '&duration=' + durationMin;
  }

  return {
    account: function (p, cb, err) { api(p, '', '', cb, err); },
    liveCategories: function (p, cb, err) { api(p, 'get_live_categories', '', cb, err); },
    vodCategories: function (p, cb, err) { api(p, 'get_vod_categories', '', cb, err); },
    seriesCategories: function (p, cb, err) { api(p, 'get_series_categories', '', cb, err); },
    liveStreams: function (p, catId, cb, err) { api(p, 'get_live_streams', '&category_id=' + catId, cb, err); },
    /* القوائم الكاملة بلا تصنيف — للبحث بالتصفية وتصنيف "الكل". مهلة أطول (BULK_TIMEOUT)
       لأن بعض الاشتراكات تعيد عشرات آلاف السجلات دفعة واحدة */
    allLive: function (p, cb, err) { api(p, 'get_live_streams', '', cb, err, BULK_TIMEOUT); },
    allVod: function (p, cb, err) { api(p, 'get_vod_streams', '', cb, err, BULK_TIMEOUT); },
    allSeries: function (p, cb, err) { api(p, 'get_series', '', cb, err, BULK_TIMEOUT); },
    vodStreams: function (p, catId, cb, err) { api(p, 'get_vod_streams', '&category_id=' + catId, cb, err); },
    seriesList: function (p, catId, cb, err) { api(p, 'get_series', '&category_id=' + catId, cb, err); },
    seriesInfo: function (p, seriesId, cb, err) { api(p, 'get_series_info', '&series_id=' + seriesId, cb, err); },
    vodInfo: function (p, vodId, cb, err) { api(p, 'get_vod_info', '&vod_id=' + vodId, cb, err); },
    streamUrl: streamUrl,
    timeshiftUrl: timeshiftUrl
  };
})();
