/* إدارة الاشتراكات (الملفات الشخصية) في localStorage */
var Storage = (function () {
  var KEY = 'aftv_profiles_v1';

  // تجزئة بسيطة لكلمة المرور (قفل أبوي، ليست حماية تشفيرية)
  function hash(s) {
    var h = 5381, i;
    s = 'aftv:' + s;
    for (i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
    return String(h);
  }

  function defaults() {
    return [
      {
        id: 'p1',
        name: 'الاشتراك الأول',
        host: 'http://online1494.org',
        username: 'amjadmubarak',
        password: '25119975086115',
        pinHash: null
      },
      {
        id: 'p2',
        name: 'World 8K',
        host: 'http://cf.shop4uu.xyz',
        username: 'e33b6eb4c485',
        password: '4602631246',
        pinHash: hash('0000'),      // كلمة المرور الابتدائية 0000 — تُغيَّر من الإعدادات
        adultPinHash: hash('2135')  // إظهار المحتوى المحجوب (XXX) يتطلب 2135
      }
    ];
  }

  function load() {
    try {
      var raw = localStorage.getItem(KEY);
      if (raw) {
        var list = JSON.parse(raw);
        if (list && list.length) {
          // ترقية البيانات المحفوظة سابقاً: قفل المحتوى المحجوب للاشتراك الثاني
          list.forEach(function (p) {
            if (p.id === 'p2' && p.adultPinHash === undefined) p.adultPinHash = hash('2135');
            if (p.id === 'p1' && p.name === 'اشتراك أبو فيصل الأساسي') p.name = 'الاشتراك الأول';
          });
          save(list);
          return list;
        }
      }
    } catch (e) {}
    var d = defaults();
    save(d);
    return d;
  }

  function save(list) {
    localStorage.setItem(KEY, JSON.stringify(list));
  }

  /* المفضلة: لكل اشتراك ولكل قسم قائمة عناصر محفوظة كاملة */
  var FAV_KEY = 'aftv_favs_v1';

  function loadFavs() {
    try { return JSON.parse(localStorage.getItem(FAV_KEY)) || {}; } catch (e) { return {}; }
  }

  function favList(profileId, kind) {
    var all = loadFavs();
    return (all[profileId] && all[profileId][kind]) || [];
  }

  function favKeyOf(kind, it) { return String(kind === 'series' ? it.series_id : it.stream_id); }

  function isFav(profileId, kind, it) {
    var k = favKeyOf(kind, it);
    return favList(profileId, kind).some(function (x) { return favKeyOf(kind, x) === k; });
  }

  function toggleFav(profileId, kind, it) {
    var all = loadFavs();
    if (!all[profileId]) all[profileId] = {};
    if (!all[profileId][kind]) all[profileId][kind] = [];
    var list = all[profileId][kind];
    var k = favKeyOf(kind, it);
    var idx = -1;
    list.forEach(function (x, i) { if (favKeyOf(kind, x) === k) idx = i; });
    var added;
    if (idx >= 0) { list.splice(idx, 1); added = false; }
    else { list.unshift(it); added = true; }
    localStorage.setItem(FAV_KEY, JSON.stringify(all));
    return added;
  }

  /* المشاهدات الأخيرة: آخر 50 عنصراً لكل اشتراك وقسم */
  var RECENT_KEY = 'aftv_recent_v1';

  function loadRecents() {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY)) || {}; } catch (e) { return {}; }
  }

  function recentList(profileId, kind) {
    var all = loadRecents();
    return (all[profileId] && all[profileId][kind]) || [];
  }

  function addRecent(profileId, kind, it) {
    var all = loadRecents();
    if (!all[profileId]) all[profileId] = {};
    if (!all[profileId][kind]) all[profileId][kind] = [];
    var list = all[profileId][kind];
    var k = favKeyOf(kind, it);
    for (var i = list.length - 1; i >= 0; i--)
      if (favKeyOf(kind, list[i]) === k) list.splice(i, 1);
    list.unshift(it);
    if (list.length > 50) list.length = 50;
    localStorage.setItem(RECENT_KEY, JSON.stringify(all));
  }

  function removeRecent(profileId, kind, it) {
    var all = loadRecents();
    var list = (all[profileId] && all[profileId][kind]) || [];
    var k = favKeyOf(kind, it);
    for (var i = list.length - 1; i >= 0; i--)
      if (favKeyOf(kind, list[i]) === k) list.splice(i, 1);
    localStorage.setItem(RECENT_KEY, JSON.stringify(all));
  }

  /* آخر اشتراك مفتوح — لفتح التطبيق عليه مباشرة في المرة القادمة دون اختيار في كل مرة */
  var LAST_KEY = 'aftv_last_profile';
  function getLastProfile() { try { return localStorage.getItem(LAST_KEY); } catch (e) { return null; } }
  function setLastProfile(id) { try { localStorage.setItem(LAST_KEY, id); } catch (e) {} }

  /* المشغّل المفضل لفتح البث (VLC أو MX Player) — فارغ يعني "اسأل دائماً" (نافذة اختيار أندرويد) */
  var PLAYER_PREF_KEY = 'aftv_preferred_player_v1';
  function getPreferredPlayer() { try { return localStorage.getItem(PLAYER_PREF_KEY) || ''; } catch (e) { return ''; } }
  function setPreferredPlayer(pkg) { try { localStorage.setItem(PLAYER_PREF_KEY, pkg || ''); } catch (e) {} }

  /* عدد البطاقات المرسومة دفعة واحدة بالقوائم الضخمة (انظر PAGE_SIZE بـapp.js) */
  var PAGE_SIZE_KEY = 'aftv_page_size_v1';
  function getPageSize() {
    try { var n = parseInt(localStorage.getItem(PAGE_SIZE_KEY), 10); return (n > 0) ? n : 200; } catch (e) { return 200; }
  }
  function setPageSize(n) { try { localStorage.setItem(PAGE_SIZE_KEY, String(n)); } catch (e) {} }

  /* قوائم مخصّصة للقنوات المباشرة فقط — منفصلة عن المفضلة، بأسماء ينشئها المستخدم */
  var LISTS_KEY = 'aftv_customlists_v1';
  function loadLists() {
    try { return JSON.parse(localStorage.getItem(LISTS_KEY)) || {}; } catch (e) { return {}; }
  }
  function saveLists(all) { localStorage.setItem(LISTS_KEY, JSON.stringify(all)); }

  function listAllCustom(profileId) {
    var all = loadLists()[profileId] || {};
    return Object.keys(all).map(function (id) { return { id: id, name: all[id].name }; });
  }
  function createCustomList(profileId, name) {
    var all = loadLists();
    if (!all[profileId]) all[profileId] = {};
    var id = 'l' + Date.now();
    all[profileId][id] = { name: name, items: [] };
    saveLists(all);
    return id;
  }
  function renameCustomList(profileId, listId, name) {
    var all = loadLists();
    if (all[profileId] && all[profileId][listId]) { all[profileId][listId].name = name; saveLists(all); }
  }
  function deleteCustomList(profileId, listId) {
    var all = loadLists();
    if (all[profileId]) { delete all[profileId][listId]; saveLists(all); }
  }
  function customListItems(profileId, listId) {
    var all = loadLists();
    return (all[profileId] && all[profileId][listId] && all[profileId][listId].items) || [];
  }
  function isInCustomList(profileId, listId, it) {
    var k = favKeyOf('live', it);
    return customListItems(profileId, listId).some(function (x) { return favKeyOf('live', x) === k; });
  }
  function toggleCustomListItem(profileId, listId, it) {
    var all = loadLists();
    if (!all[profileId] || !all[profileId][listId]) return false;
    var list = all[profileId][listId].items;
    var k = favKeyOf('live', it);
    var idx = -1;
    list.forEach(function (x, i) { if (favKeyOf('live', x) === k) idx = i; });
    var added;
    if (idx >= 0) { list.splice(idx, 1); added = false; }
    else { list.unshift(it); added = true; }
    saveLists(all);
    return added;
  }

  /* تصدير/استيراد كل بيانات التطبيق دفعة واحدة — للنسخ الاحتياطي السحابي (انظر cloud.js) */
  function exportAll() {
    return {
      profiles: localStorage.getItem(KEY),
      favs: localStorage.getItem(FAV_KEY),
      recent: localStorage.getItem(RECENT_KEY),
      lastProfile: localStorage.getItem(LAST_KEY),
      lists: localStorage.getItem(LISTS_KEY)
    };
  }
  function importAll(data) {
    if (!data) return;
    if (data.profiles) localStorage.setItem(KEY, data.profiles);
    if (data.favs) localStorage.setItem(FAV_KEY, data.favs);
    if (data.recent) localStorage.setItem(RECENT_KEY, data.recent);
    if (data.lastProfile) localStorage.setItem(LAST_KEY, data.lastProfile);
    if (data.lists) localStorage.setItem(LISTS_KEY, data.lists);
  }

  return {
    load: load,
    save: save,
    hash: hash,
    newId: function () { return 'p' + Date.now(); },
    getLastProfile: getLastProfile,
    setLastProfile: setLastProfile,
    favList: favList,
    isFav: isFav,
    toggleFav: toggleFav,
    recentList: recentList,
    addRecent: addRecent,
    removeRecent: removeRecent,
    getPreferredPlayer: getPreferredPlayer,
    setPreferredPlayer: setPreferredPlayer,
    getPageSize: getPageSize,
    setPageSize: setPageSize,
    listAllCustom: listAllCustom,
    createCustomList: createCustomList,
    renameCustomList: renameCustomList,
    deleteCustomList: deleteCustomList,
    customListItems: customListItems,
    isInCustomList: isInCustomList,
    toggleCustomListItem: toggleCustomListItem,
    exportAll: exportAll,
    importAll: importAll
  };
})();
