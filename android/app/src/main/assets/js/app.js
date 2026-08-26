/* تطبيق تلفزيون أبو فيصل — التنقل والشاشات */
(function () {
  'use strict';

  var profiles = Storage.load();
  var current = null;          // الاشتراك المفتوح حالياً
  var screenStack = [];        // لتتبع زر الرجوع
  var focus = { list: [], idx: 0, onEnter: null, cols: 1, onMove: null };
  var enterHold = { down: false, longFired: false, timer: null };
  var session = { adultShown: {} };   // إظهار المحتوى المحجوب — لجلسة التشغيل فقط
  var searchCache = {};               // كاش القوائم الكاملة للبحث: key = profileId:kind

  /* ---------- تنظيف لمرة واحدة: حذف قاعدة بيانات "الفهرسة المحلية" (MovieDB/IndexedDB) القديمة ----------
     كانت ميزة فهرسة خلفية للأفلام (IndexedDB لكل اشتراك) أُضيفت 2026-08-14 ثم أُلغيت بالكامل
     2026-08-16 لأنها أثقلت التلفزيون فعلياً أثناء التشغيل. هذا لا يحذف الكود فقط — أي قاعدة
     IndexedDB سبق إنشاؤها على الجهاز تبقى موجودة وتستهلك مساحة/ذاكرة حتى لو حذفنا الكود الذي
     بناها، فنحذفها هنا صراحة (مرة واحدة فقط، بعلامة بالـlocalStorage كي لا نكرر المحاولة كل فتح). */
  (function purgeOldMovieDbOnce() {
    var FLAG = 'aftv_moviedb_purged_v1';
    try {
      if (localStorage.getItem(FLAG) === '1' || !window.indexedDB) return;
      profiles.forEach(function (p) {
        try { indexedDB.deleteDatabase('aftv_moviedb_' + p.id); } catch (e) {}
      });
      localStorage.setItem(FLAG, '1');
    } catch (e) {}
  })();

  /* ---------- أدوات عامة ---------- */
  function $(id) { return document.getElementById(id); }

  function show(id) {
    var screens = document.querySelectorAll('.screen');
    for (var i = 0; i < screens.length; i++) screens[i].classList.add('hidden');
    $(id).classList.remove('hidden');
    // خلفية الصفحة تصبح شفافة فقط في شاشة المشغّل كي تظهر صورة ExoPlayer الحقيقية خلف الويب
    document.body.classList.toggle('in-player', id === 'screen-player');
  }

  function toast(msg, ms) {
    var t = $('toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(t._tm);
    t._tm = setTimeout(function () { t.classList.add('hidden'); }, ms || 2500);
  }

  function loader(on) { $('loader').classList.toggle('hidden', !on); }

  function setFocusList(items, onEnter, cols) {
    focus.list = items;
    focus.idx = 0;
    focus.onEnter = onEnter;
    focus.cols = cols || 1;
    focus.onMove = null;
    paintFocus();
  }

  function paintFocus() {
    for (var i = 0; i < focus.list.length; i++)
      focus.list[i].classList.toggle('focused', i === focus.idx);
    if (focus.list[focus.idx])
      focus.list[focus.idx].scrollIntoView({ block: 'nearest' });
  }

  function moveFocus(delta) {
    if (!focus.list.length) return;
    var n = focus.idx + delta;
    if (n < 0 || n >= focus.list.length) return;
    focus.idx = n;
    paintFocus();
    if (focus.onMove) focus.onMove(focus.idx);
  }

  function makeItem(html, cls) {
    var d = document.createElement('div');
    d.className = 'item' + (cls ? ' ' + cls : '');
    d.innerHTML = html;
    return d;
  }

  /* تحميل كسول للصور: يؤجل الجلب حتى تقترب الصورة من الظهور — مهم للأداء
     مع شبكات كبيرة تضم مئات البطاقات في اشتراكات مثل World 8K */
  var lazyObserver = (typeof IntersectionObserver !== 'undefined') ? new IntersectionObserver(function (entries) {
    entries.forEach(function (en) {
      if (en.isIntersecting) {
        var img = en.target;
        if (img.dataset.src) { img.src = img.dataset.src; img.removeAttribute('data-src'); }
        lazyObserver.unobserve(img);
      }
    });
  }, { rootMargin: '250px' }) : null;

  function lazyImg(url, cls) {
    var img = document.createElement('img');
    img.className = cls || '';
    img.alt = '';
    if (url) {
      if (lazyObserver) { img.dataset.src = url; lazyObserver.observe(img); }
      else img.src = url;
    } else img.style.visibility = 'hidden';
    img.onerror = function () { img.style.visibility = 'hidden'; };
    return img;
  }

  /* بطاقة شبكة الأفلام/المسلسلات: صورة + اسم + معلومة سريعة (سنة/تقييم/نوع)
     تظهر المعلومة بحركة تكبير بسيطة عند التركيز — فكرة سريعة عن العمل دون الدخول إليه */
  function makeTile(imgUrl, label, meta) {
    var d = document.createElement('div');
    d.className = 'item tile';
    d.appendChild(lazyImg(imgUrl, 'tile-img'));
    var lbl = document.createElement('div');
    lbl.className = 'tile-label';
    lbl.innerHTML = label;
    d.appendChild(lbl);
    var m = document.createElement('div');
    m.className = 'tile-meta';
    m.innerHTML = meta || '';
    d.appendChild(m);
    return d;
  }

  function tileMeta(it) {
    var bits = [];
    var y = (it.name || '').match(/\((19|20)\d\d\)/);
    if (y) bits.push(y[0].replace(/[()]/g, ''));
    var r = parseFloat(it.rating_5based || it.rating || 0);
    if (r > 0) bits.push('⭐'.repeat(Math.max(1, Math.round(r))));
    if (it.genre) bits.push(esc(it.genre));
    return bits.join(' · ');
  }

  /* صف قناة مباشرة: شعار + نقطة خضراء إن دعمت إعادة البث + الاسم */
  function makeChannelItem(it, isFavd, mark) {
    var d = document.createElement('div');
    d.className = 'item channel-row';
    d.appendChild(lazyImg(it.stream_icon, 'channel-logo'));
    var span = document.createElement('span');
    span.className = 'channel-name';
    var dot = it.tv_archive ? '<span class="archive-dot" title="تدعم إعادة البث">●</span>' : '';
    var star = isFavd ? (mark || '⭐') + ' ' : '';
    span.innerHTML = dot + star + esc(it.name);
    d.appendChild(span);
    return d;
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  /* المحتوى المحجوب: يُخفى كل اسم يحوي XXX ما لم يُفتح بكلمة المرور */
  function adultHidden() {
    return current && current.adultPinHash && !session.adultShown[current.id];
  }
  function filterNames(list, key) {
    if (!adultHidden()) return list;
    return (list || []).filter(function (x) { return !/xxx/i.test(x[key] || ''); });
  }

  /* ---------- شاشة الاشتراكات ---------- */
  function showProfiles() {
    screenStack = [];
    show('screen-profiles');
    var box = $('profiles-list');
    box.innerHTML = '';
    var els = [];
    profiles.forEach(function (p) {
      // لا نعرض أي رمز قفل هنا كي لا يثير الفضول
      var el = makeItem(esc(p.name));
      box.appendChild(el); els.push(el);
    });
    var settings = makeItem('⚙ إدارة الاشتراكات');
    box.appendChild(settings); els.push(settings);

    setFocusList(els, function (idx) {
      if (idx === profiles.length) { openSettings(); return; }
      var p = profiles[idx];
      if (p.pinHash) askPin('أدخل كلمة مرور «' + p.name + '»', p.pinHash, function () { openProfile(p); });
      else openProfile(p);
    });
  }

  /* ---------- كلمة المرور ---------- */
  var pin = { value: '', target: null, ok: null };
  var PIN_KEYS = ['1','2','3','4','5','6','7','8','9','مسح','0','موافق'];

  function pinScreen(title, targetHash, ok) {
    pin = { value: '', target: targetHash, ok: ok };
    show('screen-pin');
    $('pin-title').textContent = title;
    renderPinDots();
    var pad = $('pin-pad');
    pad.innerHTML = '';
    var els = PIN_KEYS.map(function (k) { var el = makeItem(k); pad.appendChild(el); return el; });
    setFocusList(els, function (idx) { pinPress(PIN_KEYS[idx]); }, 3);
  }

  function askPin(title, targetHash, ok) {
    screenStack.push(showProfiles);
    pinScreen(title, targetHash, ok);
  }

  function renderPinDots() {
    $('pin-dots').textContent = pin.value ? Array(pin.value.length + 1).join('●') : '−';
  }

  function pinPress(k) {
    if (k === 'مسح') pin.value = pin.value.slice(0, -1);
    else if (k === 'موافق') return pinSubmit();
    else if (pin.value.length < 8) pin.value += k;
    renderPinDots();
  }

  function pinSubmit() {
    if (pin.target === null) { var cb = pin.ok; cb(pin.value); return; }
    if (Storage.hash(pin.value) === pin.target) { var ok = pin.ok; ok(); }
    else { toast('كلمة المرور خاطئة'); pin.value = ''; renderPinDots(); }
  }

  /* ---------- الشاشة الرئيسية ---------- */
  function openProfile(p) {
    if (current && current.id !== p.id) purgeOtherProfilesCache(p.id);
    current = p;
    Storage.setLastProfile(p.id);
    screenStack = [showProfiles];
    homeScreen();
  }

  /* يمسح من ذاكرة الجلسة (searchCache) كل القوائم الكاملة (أفلام/مسلسلات/قنوات) العائدة
     لاشتراكات غير الاشتراك المفتوح حالياً — بدون هذا، فتح أكثر من اشتراك بنفس الجلسة (خصوصاً
     مع مكتبات كبيرة) يراكم كل القوائم الكاملة بالذاكرة للأبد فتُثقّل التطبيق أو تُسبّب تجمّد/كراش
     على الأجهزة الضعيفة (رام 1GB). عند الرجوع لاشتراك سابق تُعاد القائمة من السيرفر فقط عند الحاجة. */
  function purgeOtherProfilesCache(keepId) {
    Object.keys(searchCache).forEach(function (key) {
      if (key.indexOf(keepId + ':') !== 0) delete searchCache[key];
    });
  }

  function homeScreen() {
    show('screen-home');
    $('home-title').textContent = current.name;
    var box = $('home-menu');
    box.innerHTML = '';
    var entries = [
      ['📺 القنوات المباشرة', function () { openBrowse('live', '📺 القنوات المباشرة'); }],
      ['🎬 الأفلام', function () { openBrowse('vod', '🎬 الأفلام'); }],
      ['📀 المسلسلات', function () { openBrowse('series', '📀 المسلسلات'); }]
    ];
    if (current.adultPinHash) {
      // مدخل تمويهي: اسمه "الإعدادات" ولا يوحي بشيء — كلمة المرور تُظهر المحتوى المحجوب
      entries.push([
        '⚙ الإعدادات',
        function () {
          if (adultHidden()) {
            screenStack.push(homeScreen);
            pinScreen('أدخل كلمة المرور', current.adultPinHash, function () {
              session.adultShown[current.id] = true;
              searchCache = {};
              toast('تم ✓');
              screenStack.pop(); homeScreen();
            });
          } else {
            session.adultShown[current.id] = false;
            searchCache = {};
            toast('تم ✓');
            homeScreen();
          }
        }
      ]);
    }
    var menuEls = entries.map(function (e) { var el = makeItem(e[0]); box.appendChild(el); return el; });
    // أيقونة تبديل الاشتراك أعلى الشاشة — تعيدنا لقائمة الاشتراكات دون الحاجة لاختياره كل مرة
    var switchBtn = $('switch-profile-btn');
    var els = [switchBtn].concat(menuEls);
    var actions = [function () { showProfiles(); }].concat(entries.map(function (e) { return e[1]; }));
    setFocusList(els, function (idx) { actions[idx](); });
    focus.idx = 1; paintFocus();
  }

  /* ---------- التصفح: بحث أعلى + تصنيفات + عناصر + معاينة ----------
     ثلاث مناطق تنقل (zones): search (شريط البحث) / cats (التصنيفات) / items (المحتوى).
     الأسهم تتحرك داخل المنطقة الحالية، وتنتقل بين المناطق عند الحواف. */
  var GRID_COLS = 4;
  var browse = {
    kind: null, zone: 'cats', catEls: [], itemEls: [], items: [], rawItems: [], catIdx: 0,
    catMap: {}, rawCats: [], mode: 'normal', itemsLayoutCols: 1, searchIdx: 0, catFilterText: '', itemFilterText: ''
  };

  function openBrowse(kind, title) {
    stopPreviewVideo();
    screenStack.push(homeScreen);
    browse = {
      kind: kind, zone: 'cats', catEls: [], itemEls: [], items: [], rawItems: [], catIdx: 0,
      catMap: {}, catCounts: {}, rawCats: [], mode: 'normal', itemsLayoutCols: 1, searchIdx: 0, catFilterText: '', itemFilterText: ''
    };
    show('screen-browse');
    $('browse-title').textContent = title + ' — ' + current.name;
    $('cats-list').innerHTML = '';
    $('items-list').innerHTML = '';
    $('items-list').classList.remove('grid');
    $('preview-panel').classList.toggle('hidden', kind !== 'live');
    $('search-content-box').textContent = '🔍 بحث بالتصفية…';
    $('search-cats-box').textContent = '🔎 بحث في التصنيفات…';
    $('search-items-box').textContent = '🔍 بحث في هذا القسم…';
    loader(true);
    var fn = kind === 'live' ? Xtream.liveCategories : kind === 'vod' ? Xtream.vodCategories : Xtream.seriesCategories;
    fn(current, function (cats) {
      loader(false);
      cats = filterNames(cats, 'category_name');
      browse.rawCats = cats || [];
      browse.catMap = {};
      browse.rawCats.forEach(function (c) { browse.catMap[c.category_id] = c.category_name || ''; });
      renderCatsList('');
      if (!browse.catEls.length) { toast('لا توجد تصنيفات'); back(); return; }
      focusCats();
      loadCatCounts(kind);
    }, function (e) { loader(false); toast(e); back(); });
  }

  /* يجلب كل مواد القسم مرة واحدة لحساب عدد المواد بكل تصنيف — ويُخزَّن بذاكرة الجلسة
     فيُستخدم أيضاً لاحقاً بتصنيف "الكل" والبحث دون طلب مكرر من السيرفر */
  function loadCatCounts(kind) {
    var key = current.id + ':' + kind;
    function apply(all) {
      var counts = {};
      (all || []).forEach(function (it) { var c = it.category_id; counts[c] = (counts[c] || 0) + 1; });
      browse.catCounts = counts;
      if (browse.kind === kind && browse.zone === 'cats') {
        var keepIdx = browse.catIdx;
        renderCatsList(browse.catFilterText || '');
        browse.catIdx = keepIdx;
        focusCats();
      }
    }
    if (searchCache[key]) { apply(searchCache[key]); return; }
    var listFn = kind === 'live' ? Xtream.allLive : kind === 'vod' ? Xtream.allVod : Xtream.allSeries;
    listFn(current, function (all) { searchCache[key] = all || []; apply(all); }, function () {});
  }

  function clearSearchFocus() {
    $('search-content-box').classList.remove('focused');
    $('search-cats-box').classList.remove('focused');
  }
  function paintSearchFocus() {
    $('search-content-box').classList.toggle('focused', browse.searchIdx === 0);
    $('search-cats-box').classList.toggle('focused', browse.searchIdx === 1);
  }
  function clearColFocus() {
    browse.catEls.forEach(function (e) { e.classList.remove('focused'); });
    browse.itemEls.forEach(function (e) { e.classList.remove('focused'); });
    $('search-items-box').classList.remove('focused');
  }
  function clearAllBrowseFocus() { clearSearchFocus(); clearColFocus(); }

  function enterSearchZone() {
    clearAllBrowseFocus();
    browse.zone = 'search';
    paintSearchFocus();
  }

  /* شريط بحث سريع داخل القسم/التصنيف الحالي فقط (يفلتر ما هو محمَّل بلا نداء جديد للسيرفر) */
  function focusItemSearch() {
    stopPreviewVideo();
    clearAllBrowseFocus();
    browse.zone = 'itemsearch';
    $('search-items-box').classList.add('focused');
  }

  function startItemSearch() {
    var host = $('search-items-box');
    promptInput(host, browse.itemFilterText || '', 'ابحث داخل هذا القسم…', function (v) {
      if (v === null) { focusItemSearch(); return; }
      browse.itemFilterText = v;
      host.textContent = v ? ('🔍 ' + v) : '🔍 بحث في هذا القسم…';
      var list = v ? browse.rawItems.filter(function (it) { return has(itemText(it), v); }) : browse.rawItems;
      renderItems(list, true);
    });
  }

  /* تصنيفات قنوات مثبَّتة أعلى القائمة (بعد المفضلة مباشرة) لكل اشتراك — بطلب أبو فيصل 2026-07-26 */
  var LIVE_CAT_PRIORITY = {
    'p1': ['SAUDI ARABIA - السعودية', 'NEWS - أخبار', 'KIDS - أطفال']
  };

  function addCatItem(box, c) {
    var n = browse.catCounts && browse.catCounts[c.category_id];
    var el = makeItem(esc(c.category_name) + (n ? ' <span class="sub">(' + n + ')</span>' : ''));
    el._cat = c;
    box.appendChild(el);
    browse.catEls.push(el);
  }

  /* بناء قائمة التصنيفات — المفضلة والمشاهدات الأخيرة تظهران فقط بلا فلترة نشطة،
     وتصنيفات القنوات المثبَّتة (إن وُجدت لهذا الاشتراك) تُعرض مباشرة بعد المفضلة */
  function renderCatsList(filterText) {
    var box = $('cats-list');
    box.innerHTML = '';
    browse.catEls = [];
    var ft = (filterText || '').trim().toLowerCase();
    var pinned = (!ft && browse.kind === 'live') ? (LIVE_CAT_PRIORITY[current.id] || []) : [];

    /* أول تصنيف لقسمي الأفلام/المسلسلات: "الكل" — يعرض كل المحتوى بلا تصفية تصنيف */
    if (!ft && (browse.kind === 'vod' || browse.kind === 'series')) {
      var allN = browse.catCounts && Object.keys(browse.catCounts).reduce(function (s, k) { return s + browse.catCounts[k]; }, 0);
      var allEl = makeItem('🗂 الكل' + (allN ? ' <span class="sub">(' + allN + ')</span>' : '')); allEl._mode = 'all';
      box.appendChild(allEl); browse.catEls.push(allEl);
    }

    if (!ft) {
      var favN = Storage.favList(current.id, browse.kind).length;
      var favEl = makeItem('⭐ المفضلة' + (favN ? ' <span class="sub">(' + favN + ')</span>' : '')); favEl._mode = 'fav';
      box.appendChild(favEl); browse.catEls.push(favEl);
    }

    var rest = browse.rawCats.slice();
    if (pinned.length) {
      pinned.forEach(function (name) {
        var idx = rest.findIndex(function (c) { return c.category_name === name; });
        if (idx >= 0) { addCatItem(box, rest[idx]); rest.splice(idx, 1); }
      });
    }

    if (!ft) {
      var recN = Storage.recentList(current.id, browse.kind).length;
      var recEl = makeItem('🕘 المشاهدات الأخيرة' + (recN ? ' <span class="sub">(' + recN + ')</span>' : '')); recEl._mode = 'recent';
      box.appendChild(recEl); browse.catEls.push(recEl);
    }

    /* قوائم مخصّصة للقنوات المباشرة فقط — كل قائمة بشاشتها الخاصة (عرض) + خيار إضافة قنوات إليها */
    if (!ft && browse.kind === 'live') {
      Storage.listAllCustom(current.id).forEach(function (l) {
        var n = Storage.customListItems(current.id, l.id).length;
        var viewEl = makeItem('☰ ' + esc(l.name) + (n ? ' <span class="sub">(' + n + ')</span>' : ''));
        viewEl._mode = 'customView'; viewEl._listId = l.id;
        box.appendChild(viewEl); browse.catEls.push(viewEl);

        var addEl = makeItem('➕ أضف قنوات لـ«' + esc(l.name) + '»');
        addEl._mode = 'customAdd'; addEl._listId = l.id;
        box.appendChild(addEl); browse.catEls.push(addEl);
      });
      var newListEl = makeItem('🆕 إنشاء قائمة جديدة');
      newListEl._mode = 'newlist';
      box.appendChild(newListEl); browse.catEls.push(newListEl);
    }

    var list = ft ? rest.filter(function (c) { return (c.category_name || '').toLowerCase().indexOf(ft) >= 0; }) : rest;
    list.forEach(function (c) { addCatItem(box, c); });

    if (!browse.catEls.length) {
      var none = makeItem('لا توجد نتائج');
      box.appendChild(none);
      browse.catEls.push(none);
    }
  }

  function startCatSearch() {
    var host = $('search-cats-box');
    promptInput(host, browse.catFilterText || '', 'اكتب اسم التصنيف…', function (v) {
      host.textContent = '🔎 بحث في التصنيفات…';
      if (v === null) { enterSearchZone(); return; }
      browse.catFilterText = v;
      browse.catIdx = 0;
      renderCatsList(v);
      focusCats();
    });
  }

  /* يُستدعى عند دخول تصنيف/مفضلة/مشاهدات/الكل جديد: يمسح شريط البحث داخل القسم من المرة السابقة */
  function resetItemSearchBox() {
    browse.itemFilterText = '';
    $('search-items-box').textContent = '🔍 بحث في هذا القسم…';
  }

  function focusCats() {
    stopPreviewVideo();
    clearAllBrowseFocus();
    browse.zone = 'cats';
    setFocusList(browse.catEls, function (idx) {
      var el = browse.catEls[idx];
      if (el._mode) {
        browse.catIdx = idx;
        browse.mode = el._mode;
        resetItemSearchBox();
        if (el._mode === 'all') {
          var key = current.id + ':' + browse.kind;
          if (searchCache[key]) { browse.rawItems = searchCache[key]; renderItems(searchCache[key], true); return; }
          loader(true);
          toast('جارٍ تحميل كل المكتبة… قد يستغرق دقيقة أو أكثر مع المكتبات الضخمة', 5000);
          var fn = browse.kind === 'vod' ? Xtream.allVod : Xtream.allSeries;
          fn(current, function (list) {
            loader(false);
            searchCache[key] = list || [];
            browse.rawItems = list || [];
            renderItems(list, true);
          }, function (e) { loader(false); toast(e, 6000); });
          return;
        }
        if (el._mode === 'newlist') {
          promptInput(null, '', 'اسم القائمة الجديدة…', function (name) {
            if (!name) { focusCats(); return; }
            Storage.createCustomList(current.id, name);
            renderCatsList('');
            focusCats();
          });
          return;
        }
        if (el._mode === 'customView') {
          browse.customListId = el._listId;
          var citems = Storage.customListItems(current.id, el._listId);
          if (!citems.length) { toast('القائمة فارغة — استخدم «➕ أضف قنوات» لتعبئتها'); return; }
          browse.rawItems = citems;
          renderItems(citems, true);
          return;
        }
        if (el._mode === 'customAdd') {
          browse.customListId = el._listId;
          browse.mode = 'customPick';
          var pkey = current.id + ':live';
          function showPicker(list) {
            browse.rawItems = list || [];
            renderItems(list, true);
            toast('موافق: إضافة/إزالة من القائمة — رجوع للخروج');
          }
          if (searchCache[pkey]) { showPicker(searchCache[pkey]); }
          else {
            loader(true);
            Xtream.allLive(current, function (list) {
              loader(false);
              searchCache[pkey] = list || [];
              showPicker(list);
            }, function (e) { loader(false); toast(e); });
          }
          return;
        }
        var list = el._mode === 'fav'
          ? Storage.favList(current.id, browse.kind)
          : Storage.recentList(current.id, browse.kind);
        if (!list.length) { toast(el._mode === 'fav' ? 'المفضلة فارغة — الزر الأحمر يضيف إليها' : 'لا توجد مشاهدات بعد'); return; }
        browse.rawItems = list;
        renderItems(list, true);
        return;
      }
      if (!el._cat) return;
      browse.catIdx = idx;
      browse.mode = 'normal';
      resetItemSearchBox();
      loadCatItems(idx, true);
    });
    focus.idx = Math.max(0, Math.min(browse.catIdx, browse.catEls.length - 1));
    paintFocus();
  }

  function nameKeyOf(kind) { return 'name'; }

  /* لوحة معاينة القناة (للقنوات المباشرة فقط): تتحدث مع كل تحريك للتركيز */
  /* معاينة فيديو حقيقية مباشرة أثناء تصفح القنوات (طلب صريح 2026-08-27، بعد تحذير من
     مخاطرة تجمّد سابقة مع ExoPlayer المُضمَّن على أجهزة ضعيفة — هذا مختلف تماماً: تشغيل
     داخل WebView نفسه عبر hls.js (موجود أصلاً بالمشروع)، بلا أي محرك أندرويد أصلي مُضمَّن،
     مع تأخير قبل البدء (حتى لا يُحمَّل بث لكل قناة يمر عليها التركيز أثناء التنقل السريع)
     وإيقاف/تحرير فوري وصريح عند أي تغيير تركيز أو مغادرة الشاشة. */
  var previewHls = null;
  var previewTimer = null;

  function stopPreviewVideo() {
    clearTimeout(previewTimer);
    previewTimer = null;
    var v = $('preview-video');
    try { if (previewHls) { previewHls.destroy(); } } catch (e) {}
    previewHls = null;
    try { v.pause(); v.removeAttribute('src'); v.load(); } catch (e) {}
    v.classList.add('hidden');
    $('preview-logo').classList.remove('hidden');
  }

  function startPreviewVideo(idx, it) {
    var v = $('preview-video');
    try {
      var url = Xtream.streamUrl(current, 'live', it.stream_id, null);
      if (window.Hls && Hls.isSupported()) {
        previewHls = new Hls({ maxBufferLength: 8, maxMaxBufferLength: 12, liveSyncDurationCount: 2 });
        previewHls.on(Hls.Events.ERROR, function (ev, data) { if (data && data.fatal) stopPreviewVideo(); });
        previewHls.loadSource(url);
        previewHls.attachMedia(v);
      } else {
        v.src = url;
      }
      v.muted = true;
      v.play().catch(function () {});
      v.classList.remove('hidden');
      $('preview-logo').classList.add('hidden');
    } catch (e) { stopPreviewVideo(); }
  }

  function updatePreview(idx) {
    stopPreviewVideo();
    if (browse.kind !== 'live') return;
    var it = browse.items[idx];
    if (!it) return;
    var img = $('preview-logo');
    if (it.stream_icon) { img.src = it.stream_icon; img.style.visibility = 'visible'; }
    else img.style.visibility = 'hidden';
    $('preview-name').textContent = it.name || '';
    $('preview-cat').textContent = browse.catMap[it.category_id] || '';
    $('preview-archive').textContent = it.tv_archive ? '● تدعم إعادة البث' : '';
    previewTimer = setTimeout(function () {
      if (browse.kind === 'live' && browse.zone === 'items' && browse.items[idx] === it) startPreviewVideo(idx, it);
    }, 600);
  }

  /* أقصى عدد بطاقات تُرسم دفعة واحدة — قوائم بعض الاشتراكات تتجاوز 170 ألف مادة، ورسمها
     كاملة كعناصر DOM يجمّد الجهاز فوراً (خصوصاً برام 1GB). القائمة الكاملة تبقى محمَّلة
     بالذاكرة (browse.items) جاهزة فوراً — البحث داخل القسم يفلترها كلها مباشرة، وزر
     "تحميل المزيد" يرسم الدفعة التالية من نفس القائمة بلا أي طلب جديد من السيرفر.
     العدد قابل للتعديل من الإعدادات (Storage.getPageSize). */
  function PAGE_SIZE() { return Storage.getPageSize(); }

  function renderItems(items, thenFocus) {
    browse.items = filterNames(items || [], nameKeyOf(browse.kind));
    browse.renderedCount = 0;
    browse._moreEl = null;
    var box = $('items-list');
    box.innerHTML = '';
    var isGrid = browse.kind !== 'live';
    box.classList.toggle('grid', isGrid);
    browse.itemsLayoutCols = isGrid ? GRID_COLS : 1;
    $('preview-panel').classList.toggle('hidden', browse.kind !== 'live');
    browse.itemEls = [];

    if (!browse.items.length) { toast('لا توجد نتائج'); return; }
    appendItemsPage();
    if (thenFocus) focusItems();
  }

  /* يرسم الدفعة التالية (PAGE_SIZE) من browse.items المحمَّلة أصلاً بالذاكرة، ويضيف
     بطاقة "تحميل المزيد" في النهاية إن بقي شيء لم يُرسم بعد */
  function appendItemsPage() {
    var box = $('items-list');
    if (browse._moreEl) { browse._moreEl.remove(); browse.itemEls.pop(); browse._moreEl = null; }

    var start = browse.renderedCount;
    var end = Math.min(start + PAGE_SIZE(), browse.items.length);
    for (var i = start; i < end; i++) {
      var it = browse.items[i];
      var isFavd = browse.mode === 'customPick'
        ? Storage.isInCustomList(current.id, browse.customListId, it)
        : Storage.isFav(current.id, browse.kind, it);
      var el;
      if (browse.kind === 'live') {
        el = makeChannelItem(it, isFavd, browse.mode === 'customPick' ? '✅' : '⭐');
      } else {
        var star = isFavd ? '⭐ ' : '';
        el = makeTile(it.stream_icon || it.cover || '', star + esc(it.name), tileMeta(it));
      }
      box.appendChild(el);
      browse.itemEls.push(el);
    }
    browse.renderedCount = end;

    if (browse.renderedCount < browse.items.length) {
      var remaining = browse.items.length - browse.renderedCount;
      var moreEl = makeItem('⬇️ تحميل ' + Math.min(PAGE_SIZE(), remaining) + ' أخرى <span class="sub">(المتبقي ' + remaining + ')</span>');
      moreEl._more = true;
      box.appendChild(moreEl);
      browse.itemEls.push(moreEl);
      browse._moreEl = moreEl;
    }
  }

  function loadCatItems(idx, thenFocus) {
    browse.catIdx = idx;
    browse.mode = 'normal';
    var cat = browse.catEls[idx]._cat;
    loader(true);
    var fn = browse.kind === 'live' ? Xtream.liveStreams : browse.kind === 'vod' ? Xtream.vodStreams : Xtream.seriesList;
    fn(current, cat.category_id, function (items) {
      loader(false);
      browse.rawItems = items || [];
      renderItems(items, thenFocus);
    }, function (e) { loader(false); toast(e); });
  }

  /* ---------- البحث بالتصفية المتعددة ----------
     كل الحقول اختيارية: الاسم، السنة، اللغة، النوع، البطل، المخرج، الشركة المنتجة.
     الاسم/السنة/اللغة تُصفّى فوراً من القائمة. البطل/المخرج/الشركة/النوع للأفلام
     تتطلب جلب تفاصيل كل مرشح، لذا تُطبَّق على أول 80 نتيجة بعد التصفية الأولية. */
  var FILTER_FIELDS = [
    ['الاسم', 'name'], ['السنة', 'year'], ['اللغة (مثال: AR أو عربي)', 'lang'],
    ['النوع (أكشن، دراما…)', 'genre'], ['البطل / ممثل', 'cast'],
    ['المخرج', 'director'], ['الجنسية / الدولة', 'country'],
    ['الشركة المنتجة / الاستوديو', 'company']
  ];

  function startSearch() {
    clearAllBrowseFocus();
    browse.zone = 'items';
    browse.itemsLayoutCols = 1;
    $('items-list').classList.remove('grid');
    $('preview-panel').classList.add('hidden');
    var filters = browse.lastFilters || {};
    var box = $('items-list');

    function render() {
      box.innerHTML = '';
      var els = FILTER_FIELDS.map(function (f) {
        var el = makeItem(f[0] + ': <span class="sub">' + esc(filters[f[1]] || '(بلا شرط)') + '</span>');
        box.appendChild(el);
        return el;
      });
      var go = makeItem('🔍 بدء البحث');
      box.appendChild(go); els.push(go);
      var clear = makeItem('🧹 مسح الشروط');
      box.appendChild(clear); els.push(clear);

      setFocusList(els, function (idx) {
        if (idx < FILTER_FIELDS.length) {
          var f = FILTER_FIELDS[idx];
          promptInput(els[idx], filters[f[1]] || '', f[0], function (val) {
            if (val !== null) filters[f[1]] = val;
            render();
            focus.idx = idx; paintFocus();
          });
        } else if (idx === FILTER_FIELDS.length) {
          browse.lastFilters = filters;
          runSearch(filters);
        } else {
          filters = {};
          browse.lastFilters = {};
          render();
        }
      });
    }
    render();
  }

  function itemText(it) {
    var cat = (browse.catMap && browse.catMap[it.category_id]) || '';
    return ((it.name || '') + ' ' + cat).toLowerCase();
  }

  function itemYear(it) {
    var m = (it.name || '').match(/\((19|20)\d\d\)/);
    var y = m ? m[0] : '';
    return y + ' ' + (it.releaseDate || it.release_date || it.year || '');
  }

  function deepText(obj) {
    if (!obj) return '';
    return ((obj.genre || '') + ' ' + (obj.cast || '') + ' ' + (obj.actors || '') + ' ' +
      (obj.director || '') + ' ' + (obj.plot || '') + ' ' + (obj.description || '') + ' ' +
      (obj.country || '') + ' ' + (obj.studio || '') + ' ' + (obj.o_name || '')).toLowerCase();
  }

  /* مطابقة بكل الكلمات: «bein 1» تطابق كل اسم يحوي bein و1 معاً بأي ترتيب */
  function has(hay, needle) {
    var tokens = needle.toLowerCase().replace(/["'«»]/g, ' ').split(/\s+/).filter(Boolean);
    if (!tokens.length) return true;
    return tokens.every(function (t) { return hay.indexOf(t) >= 0; });
  }

  function runSearch(filters) {
    var f = {};
    FILTER_FIELDS.forEach(function (fd) {
      var v = (filters[fd[1]] || '').trim();
      if (v) f[fd[1]] = v;
    });
    if (!Object.keys(f).length) { toast('حدد شرطاً واحداً على الأقل'); return; }

    var key = current.id + ':' + browse.kind;

    function stage1(all) {
      return (all || []).filter(function (it) {
        if (f.name && !has(itemText(it), f.name)) return false;
        if (f.year && !has(itemText(it) + ' ' + itemYear(it), f.year)) return false;
        if (f.lang && !has(itemText(it), f.lang)) return false;
        // للمسلسلات: البيانات العميقة موجودة في القائمة نفسها
        var deep = deepText(it);
        if (f.genre && deep && !has(deep + ' ' + itemText(it), f.genre)) return false;
        if (f.cast && deep && !has(deep, f.cast)) return false;
        if (f.director && deep && !has(deep, f.director)) return false;
        if (f.country && deep && !has(deep, f.country)) return false;
        if (f.company && deep && !has(deep + ' ' + itemText(it), f.company)) return false;
        return true;
      });
    }

    var needsDeep = (f.genre || f.cast || f.director || f.country || f.company) && browse.kind === 'vod';

    function finish(hits) {
      hits = hits.slice(0, 300);
      renderItems(hits, true);
      toast('عدد النتائج: ' + hits.length + (hits.length === 300 ? '+' : ''));
    }

    function matchesDeep(deep, okkInit) {
      var okk = okkInit === undefined ? true : okkInit;
      if (f.genre && !has(deep, f.genre)) okk = false;
      if (f.cast && !has(deep, f.cast)) okk = false;
      if (f.director && !has(deep, f.director)) okk = false;
      if (f.country && !has(deep, f.country)) okk = false;
      if (f.company && !has(deep, f.company)) okk = false;
      return okk;
    }

    // المرحلة الثانية: تصفية المرشحين بجلب تفاصيلهم من السيرفر (حتى 80 عنصراً كحد أقصى).
    function deepFetchRemaining(cand, matched) {
      if (!cand.length) { finish(matched); return; }
      loader(true);
      toast('جارٍ فحص تفاصيل ' + cand.length + ' فيلماً…', 4000);
      var out = matched.slice(), done = 0, inFlight = 0, next = 0;

      function step() {
        while (inFlight < 6 && next < cand.length) {
          (function (it) {
            inFlight++;
            Xtream.vodInfo(current, it.stream_id, function (info) {
              if (matchesDeep(deepText(info && info.info))) out.push(it);
              tick();
            }, function () { tick(); });
          })(cand[next++]);
        }
      }
      function tick() {
        inFlight--; done++;
        if (done >= cand.length) { loader(false); finish(out); }
        else step();
      }
      step();
    }

    function afterList(all) {
      var hits = stage1(all);
      if (!needsDeep) { finish(hits); return; }
      deepFetchRemaining(hits.slice(0, 80), []);
    }

    if (searchCache[key]) { afterList(searchCache[key]); return; }
    loader(true);
    toast('جارٍ تحميل كل المكتبة للبحث فيها… قد يستغرق دقيقة أو أكثر مع المكتبات الضخمة', 5000);
    var fn = browse.kind === 'live' ? Xtream.allLive : browse.kind === 'vod' ? Xtream.allVod : Xtream.allSeries;
    fn(current, function (all) {
      loader(false);
      searchCache[key] = all || [];
      afterList(all);
    }, function (e) { loader(false); toast(e, 6000); });
  }

  function itemsOnEnter(idx) {
    var el = browse.itemEls[idx];
    if (el && el._more) {
      appendItemsPage();
      setFocusList(browse.itemEls, itemsOnEnter, browse.itemsLayoutCols);
      focus.idx = idx; paintFocus();
      return;
    }
    var it = browse.items[idx];
    if (browse.mode === 'customPick') { redButton(); return; }
    stopPreviewVideo();
    if (browse.kind === 'live') {
      // القناة المباشرة تُشغَّل فوراً عند الاختيار — فتسجيلها بالمشاهدات الأخيرة هنا صحيح
      Storage.addRecent(current.id, browse.kind, it);
      player.channelList = browse.items.slice(0, browse.renderedCount); // المحمَّل فعلياً فقط — يكفي لقائمة M3U
      player.channelIndex = idx;
      playStream('live', it.stream_id, null, it.name, it);
    }
    // الأفلام/المسلسلات: فتح شاشة المعلومات فقط لا يُحسب مشاهدة — التسجيل بالمشاهدات
    // الأخيرة يحدث عند الضغط الفعلي على "تشغيل" (openInfo) أو اختيار حلقة (openSeriesEpisodes)
    else openInfo(browse.kind, it);
  }

  function focusItems() {
    if (!browse.itemEls.length) return;
    clearAllBrowseFocus();
    browse.zone = 'items';
    setFocusList(browse.itemEls, itemsOnEnter, browse.itemsLayoutCols);
    focus.onMove = (browse.kind === 'live') ? updatePreview : null;
    if (browse.kind === 'live') updatePreview(focus.idx);
  }

  /* التنقل داخل شاشة التصفح: ثلاث مناطق (بحث/تصنيفات/عناصر) بحدود واضحة بينها */
  function handleBrowseKeys(k, ev) {
    if (browse.zone === 'search') {
      switch (k) {
        case 37: ev.preventDefault(); browse.searchIdx = Math.min(1, browse.searchIdx + 1); paintSearchFocus(); return;
        case 39: ev.preventDefault(); browse.searchIdx = Math.max(0, browse.searchIdx - 1); paintSearchFocus(); return;
        case 40: ev.preventDefault(); focusCats(); return;
        case 13: ev.preventDefault(); if (browse.searchIdx === 0) startSearch(); else startCatSearch(); return;
        case 10009: case 27: ev.preventDefault(); back(); return;
      }
      return;
    }
    if (browse.zone === 'cats') {
      switch (k) {
        case 38: ev.preventDefault(); if (focus.idx <= 0) enterSearchZone(); else moveFocus(-1); return;
        case 40: ev.preventDefault(); moveFocus(1); return;
        case 37: ev.preventDefault(); if (browse.itemEls.length) focusItems(); return;
        case 39: ev.preventDefault(); return;
        case 13: ev.preventDefault(); if (focus.onEnter) focus.onEnter(focus.idx); return;
        case 10009: case 27: ev.preventDefault(); back(); return;
      }
      return;
    }
    if (browse.zone === 'items') {
      var cols = browse.itemsLayoutCols || 1;
      switch (k) {
        case 38: ev.preventDefault(); if (focus.idx < cols) focusItemSearch(); else moveFocus(-cols); return;
        case 40: ev.preventDefault(); moveFocus(cols); return;
        case 37: ev.preventDefault(); if (cols > 1) moveFocus(1); return;
        case 39: ev.preventDefault();
          if (cols > 1) { if (focus.idx % cols === 0) focusCats(); else moveFocus(-1); }
          else focusCats();
          return;
        case 13: ev.preventDefault(); startEnterHold(); return;
        case 10009: case 27: ev.preventDefault(); back(); return;
        case 403: case 70: redButton(); return;
      }
      return;
    }
    if (browse.zone === 'itemsearch') {
      switch (k) {
        case 40: ev.preventDefault(); focusItems(); return;
        case 38: ev.preventDefault(); return;
        case 13: ev.preventDefault(); startItemSearch(); return;
        case 10009: case 27: ev.preventDefault(); back(); return;
      }
      return;
    }
  }

  /* الزر الأحمر (أو حرف F): إضافة/إزالة مفضلة، وفي قوائم المفضلة/الأخيرة = حذف */
  function redButton() {
    if ($('screen-browse').classList.contains('hidden')) return;
    if (browse.zone !== 'items' || !browse.items.length) return;
    var it = browse.items[focus.idx];
    if (!it) return;
    var keepIdx = focus.idx;
    if (browse.mode === 'recent') {
      Storage.removeRecent(current.id, browse.kind, it);
      toast('حُذف من المشاهدات الأخيرة');
      var list = Storage.recentList(current.id, browse.kind);
      if (list.length) { renderItems(list, true); focus.idx = Math.min(keepIdx, focus.list.length - 1); paintFocus(); }
      else { $('items-list').innerHTML = ''; browse.itemEls = []; browse.items = []; focusCats(); }
      return;
    }
    if (browse.mode === 'customView') {
      Storage.toggleCustomListItem(current.id, browse.customListId, it);
      toast('أُزيل من القائمة');
      var litems = Storage.customListItems(current.id, browse.customListId);
      if (litems.length) { renderItems(litems, true); focus.idx = Math.min(keepIdx, focus.list.length - 1); paintFocus(); }
      else { $('items-list').innerHTML = ''; browse.itemEls = []; browse.items = []; focusCats(); }
      return;
    }
    if (browse.mode === 'customPick') {
      var addedToList = Storage.toggleCustomListItem(current.id, browse.customListId, it);
      toast(addedToList ? 'أُضيف للقائمة ✓' : 'أُزيل من القائمة');
      var span2 = browse.itemEls[keepIdx].querySelector('.channel-name');
      var dot2 = it.tv_archive ? '<span class="archive-dot" title="تدعم إعادة البث">●</span>' : '';
      span2.innerHTML = dot2 + (addedToList ? '✅ ' : '') + esc(it.name);
      return;
    }
    var added = Storage.toggleFav(current.id, browse.kind, it);
    toast(added ? 'أُضيف إلى المفضلة ⭐' : 'أُزيل من المفضلة');
    if (browse.mode === 'fav') {
      var favs = Storage.favList(current.id, browse.kind);
      if (favs.length) { renderItems(favs, true); focus.idx = Math.min(keepIdx, focus.list.length - 1); paintFocus(); }
      else { $('items-list').innerHTML = ''; browse.itemEls = []; browse.items = []; focusCats(); }
    } else {
      var star = added ? '⭐ ' : '';
      if (browse.kind === 'live') {
        var span = browse.itemEls[keepIdx].querySelector('.channel-name');
        var dot = it.tv_archive ? '<span class="archive-dot" title="تدعم إعادة البث">●</span>' : '';
        span.innerHTML = dot + star + esc(it.name);
      } else {
        var label = browse.itemEls[keepIdx].querySelector('.tile-label');
        label.innerHTML = star + esc(it.name);
      }
    }
  }

  /* ضغطة طويلة على "موافق" (نحو 550ms) داخل قائمة القنوات/الأفلام كبديل موثوق لزر الريموت
     الأحمر — ريموتات كثيرة (بما فيها ريموت TCL الفعلي) ليس فيها زر أحمر حقيقي أصلاً، بل زر
     "123" يفتح قائمة اختيار للأزرار الملوّنة، والنظام أحياناً يعترض الضغطة قبل وصولها للتطبيق
     أصلاً ("هذا الزر لا يعمل في هذه الصفحة"). الضغطة الطويلة تصل دائماً لأنها نفس زر موافق
     العادي المُستخدَم أصلاً للتنقل، فتعمل على أي ريموت بلا استثناء. */
  function startEnterHold() {
    if (enterHold.down) return; // تجاهل تكرار keydown التلقائي أثناء الاستمرار بالضغط
    enterHold.down = true;
    enterHold.longFired = false;
    enterHold.timer = setTimeout(function () {
      enterHold.longFired = true;
      redButton();
    }, 550);
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) stopPreviewVideo(); // التطبيق بالخلفية (مثلاً فتح مشغّل خارجي) — لا داعي لمعاينة تعمل بلا فائدة
  });

  document.addEventListener('keyup', function (ev) {
    if (ev.keyCode !== 13 || !enterHold.down) return;
    clearTimeout(enterHold.timer);
    var wasLong = enterHold.longFired;
    enterHold.down = false;
    enterHold.longFired = false;
    if (!wasLong && browse.zone === 'items' && focus.onEnter) focus.onEnter(focus.idx);
  });

  /* ---------- المسلسلات ----------
     مسلسل بأكثر من موسم: تُعرض قائمة المواسم أولاً (بدل تسطيح كل حلقات كل المواسم بقائمة واحدة
     طويلة بلا تمييز) — هذا أيضاً يحل مشكلة كانت تظهر ببعض المسلسلات الطويلة (عشرات المواسم/
     مئات الحلقات): رسم كل الحلقات دفعة واحدة بقائمة مسطّحة كان يُنتج مئات عناصر DOM على شاشة
     واحدة فيُخرّب رسم النص على الأجهزة الضعيفة (نفس فئة مشكلة تجمّد مكتبة الـ177 ألف فيلم
     السابقة) — تقسيمها بالموسم يبقي كل شاشة بعدد حلقات معقول (عادة أقل من 30). */
  function openSeriesEpisodes(series, info) {
    var seasons = (info && info.episodes) || {};
    var seasonKeys = Object.keys(seasons).filter(function (s) { return (seasons[s] || []).length; })
      .sort(function (a, b) { return a - b; });
    if (!seasonKeys.length) { toast('لا توجد حلقات'); return; }
    // كل حلقات المسلسل بكل مواسمه، بترتيب متسلسل — تُستخدم فقط عند التشغيل (قائمة التشغيل
    // الخارجية) وليس للعرض، فلا تتعارض مع تقسيم شاشة العرض بالموسم أعلاه
    var allEps = [];
    seasonKeys.forEach(function (s) {
      seasons[s].slice().sort(function (a, b) { return (Number(a.episode_num) || 0) - (Number(b.episode_num) || 0); })
        .forEach(function (ep) { allEps.push(ep); });
    });
    screenStack.push(function () { openInfo('series', series); });
    if (seasonKeys.length > 1) renderSeasonsList(series, seasons, seasonKeys, allEps);
    else renderEpisodesList(series, seasons, seasonKeys[0], seasonKeys, allEps);
  }

  function renderSeasonsList(series, seasons, seasonKeys, allEps) {
    show('screen-series');
    $('series-title').textContent = series.name;
    var box = $('episodes-list');
    box.innerHTML = '';
    var els = seasonKeys.map(function (s) {
      var el = makeItem('📀 الموسم ' + esc(s) +
        ' <span class="sub">' + seasons[s].length + ' حلقة</span>');
      box.appendChild(el);
      return el;
    });
    setFocusList(els, function (idx) {
      screenStack.push(function () { renderSeasonsList(series, seasons, seasonKeys, allEps); });
      renderEpisodesList(series, seasons, seasonKeys[idx], seasonKeys, allEps);
    });
  }

  function renderEpisodesList(series, seasons, seasonKey, seasonKeys, allEps) {
    show('screen-series');
    $('series-title').textContent = series.name +
      (seasonKeys.length > 1 ? ' — الموسم ' + seasonKey : '');
    var box = $('episodes-list');
    box.innerHTML = '';
    var eps = (seasons[seasonKey] || []).slice().sort(function (a, b) {
      return (Number(a.episode_num) || 0) - (Number(b.episode_num) || 0);
    });
    if (!eps.length) { toast('لا توجد حلقات'); back(); return; }
    var els = eps.map(function (ep) {
      var el = makeItem('الحلقة ' + esc(ep.episode_num) +
        (ep.title ? ' <span class="sub">' + esc(ep.title) + '</span>' : ''));
      box.appendChild(el);
      return el;
    });
    setFocusList(els, function (idx) {
      Storage.addRecent(current.id, 'series', series);
      var ep = eps[idx];
      var allIdx = allEps.indexOf(ep);
      playSeriesEpisode(series, allEps, allIdx === -1 ? 0 : allIdx);
    });
  }

  /* تشغيل حلقة: نسلّم المشغّل الخارجي (VLC/MX) قائمة تشغيل M3U بكل حلقات المسلسل من كل
     مواسمه بترتيبها (بدءاً من الحلقة المختارة) — بنفس أسلوب تبديل القنوات المباشرة، فزر
     "الملف التالي" المدمج أصلاً بـVLC/MX ينقل تلقائياً للحلقة التالية حتى لو كانت بموسم لاحق،
     بلا أي تدخل من تطبيقنا أثناء العرض */
  function playSeriesEpisode(series, allEps, idx) {
    if (allEps.length > 1 && playExternalEpisodePlaylist(series, allEps, idx)) return;
    var ep = allEps[idx];
    playStream('series', ep.id, ep.container_extension, series.name + ' — حلقة ' + ep.episode_num);
  }

  function playExternalEpisodePlaylist(series, eps, startIdx) {
    if (typeof AndroidOpen === 'undefined' || !AndroidOpen.playPlaylist) return false;
    var ordered = eps.slice(startIdx).concat(eps.slice(0, startIdx));
    var m3u = '#EXTM3U\n' + ordered.map(function (ep) {
      var label = (series.name + ' — م' + ep.season + ' ح' + ep.episode_num).replace(/[\r\n]/g, ' ');
      return '#EXTINF:-1,' + label + '\n' + Xtream.streamUrl(current, 'series', ep.id, ep.container_extension);
    }).join('\n') + '\n';
    AndroidOpen.playPlaylist(m3u, Storage.getPreferredPlayer());
    return true;
  }

  /* ---------- شاشة معلومات الفيلم/المسلسل: تفاصيل + تفضيل قبل التشغيل ---------- */
  function infoMetaLine(fields) {
    var bits = [];
    if (fields.year) bits.push(fields.year);
    var r = parseFloat(fields.rating || 0);
    if (r > 0) bits.push('⭐'.repeat(Math.max(1, Math.round(r / (r > 5 ? 2 : 1)))));
    if (fields.genre) bits.push(fields.genre);
    if (fields.director) bits.push('إخراج: ' + fields.director);
    if (fields.cast) bits.push('تمثيل: ' + fields.cast);
    return bits.join(' · ');
  }

  /* إعلان الفيلم/المسلسل: حقل youtube_trailer من Xtream عادة رقم فيديو يوتيوب فقط، أحياناً رابط كامل */
  function openYoutubeTrailer(trailer) {
    var url = /^https?:\/\//.test(trailer) ? trailer : 'https://www.youtube.com/watch?v=' + trailer;
    try { AndroidOpen.url(url); } catch (e) { window.open(url, '_blank'); }
  }

  /* ترجمة القصة إلى العربية عبر خدمة ترجمة جوجل المجانية (بلا مفتاح API) */
  function translatePlot(text) {
    loader(true);
    var url = 'https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ar&dt=t&q=' + encodeURIComponent(text);
    fetch(url).then(function (r) { return r.json(); }).then(function (data) {
      loader(false);
      var out = ((data && data[0]) || []).map(function (seg) { return seg[0]; }).join('');
      $('info-plot').textContent = out || text;
      toast('تُرجمت القصة ✓');
    }).catch(function () { loader(false); toast('تعذّرت الترجمة — تحقق من الإنترنت'); });
  }

  function openInfo(kind, item) {
    screenStack.push(function () { show('screen-browse'); renderItems(browse.items, true); });
    show('screen-info');
    $('info-title').textContent = item.name || '';
    $('info-meta').textContent = '';
    $('info-plot').textContent = '';
    var poster = $('info-poster');
    var posterUrl = item.stream_icon || item.cover || '';
    if (posterUrl) { poster.src = posterUrl; poster.style.visibility = 'visible'; }
    else poster.style.visibility = 'hidden';

    function renderActions(info) {
      var opts = [];
      opts.push([function () { return (Storage.isFav(current.id, kind, item) ? '⭐ إزالة من المفضلة' : '☆ إضافة إلى المفضلة'); },
        function () {
          Storage.toggleFav(current.id, kind, item);
          renderActions(info);
        }]);
      if (kind === 'vod') {
        opts.push(['▶ تشغيل الفيلم', function () {
          Storage.addRecent(current.id, 'vod', item);
          playStream('movie', item.stream_id, item.container_extension, item.name);
        }]);
      } else {
        opts.push(['📺 عرض المواسم والحلقات', function () { openSeriesEpisodes(item, info); }]);
      }
      var trailer = info && info.info && info.info.youtube_trailer;
      if (trailer) {
        opts.push(['🎞 مشاهدة الإعلان (يوتيوب)', function () { openYoutubeTrailer(trailer); }]);
      }
      var plotText = info && info.info && (info.info.plot || info.info.description);
      if (plotText && !/[؀-ۿ]/.test(plotText)) {
        opts.push(['🌐 ترجمة القصة للعربية', function () { translatePlot(plotText); }]);
      }
      var box = $('info-actions');
      var keepIdx = Math.min(focus.idx, opts.length - 1);
      box.innerHTML = '';
      var els = opts.map(function (o) {
        var el = makeItem(typeof o[0] === 'function' ? o[0]() : o[0]);
        box.appendChild(el);
        return el;
      });
      setFocusList(els, function (idx) { opts[idx][1](); });
      focus.idx = Math.max(0, keepIdx); paintFocus();
    }

    renderActions(null);
    loader(true);
    var fn = kind === 'vod' ? Xtream.vodInfo : Xtream.seriesInfo;
    var vid = kind === 'vod' ? item.stream_id : item.series_id;
    fn(current, vid, function (info) {
      loader(false);
      var d = (info && info.info) || {};
      $('info-meta').textContent = infoMetaLine({
        year: d.year || d.releaseDate || d.release_date || '',
        rating: d.rating_5based || d.rating || 0,
        genre: d.genre || '',
        director: d.director || '',
        cast: d.cast || d.actors || ''
      });
      $('info-plot').textContent = d.plot || d.description || '';
      if (!posterUrl && d.cover) { poster.src = d.cover; poster.style.visibility = 'visible'; }
      renderActions(info);
    }, function () { loader(false); });
  }

  /* ---------- المشغّل ----------
     التشغيل الفعلي على أندرويد يتم خارجياً بمشغل مثبَّت أصلاً على الجهاز (VLC/MX Player عادة
     موجودان على أي تلفزيون/بوكس) بدل تضمين محرك فيديو داخل التطبيق — أبسط وأثبت من أي محرك
     مُضمَّن على أجهزة برام محدودة. على تلفزيونات Tizen (لا يوجد AndroidOpen) يبقى المشغّل الداخلي. */
  function playExternally(url) {
    if (typeof AndroidOpen !== 'undefined' && AndroidOpen.playVideo) {
      AndroidOpen.playVideo(url, Storage.getPreferredPlayer());
      return true;
    }
    return false;
  }

  /* قنوات مباشرة فقط: بدل تسليم رابط قناة واحدة، نبني قائمة تشغيل M3U بكل قنوات نفس
     التصنيف/القائمة المخصّصة (القناة المختارة أولاً) — فتصير أزرار التالي/السابق بالريموت
     تنقل بين القنوات داخل VLC/MX نفسه، بلا أي تدخل من تطبيقنا أثناء العرض */
  function playExternalPlaylist(items, startIdx) {
    if (typeof AndroidOpen === 'undefined' || !AndroidOpen.playPlaylist) return false;
    var ordered = items.slice(startIdx).concat(items.slice(0, startIdx));
    var m3u = '#EXTM3U\n' + ordered.map(function (it) {
      return '#EXTINF:-1,' + (it.name || 'قناة').replace(/[\r\n]/g, ' ') + '\n' +
        Xtream.streamUrl(current, 'live', it.stream_id, null);
    }).join('\n') + '\n';
    AndroidOpen.playPlaylist(m3u, Storage.getPreferredPlayer());
    return true;
  }

  var player = { isVod: false, osdTimer: null, tick: null, menuOpen: false, speed: 1, fwdStep: 30, backStep: 10, advanced: false, zoom: 1 };

  function playStream(kind, id, ext, name, srcItem) {
    var url = Xtream.streamUrl(current, kind, id, ext);
    if (kind === 'live' && player.channelList && player.channelList.length > 1
        && playExternalPlaylist(player.channelList, player.channelIndex)) return;
    if (playExternally(url)) return;
    var ret = screenStack[screenStack.length - 1];
    screenStack.push(function () { stopPlayback(); if (ret) ret(); });
    // بيانات الإعادة للقنوات المباشرة
    player.liveItem = (kind === 'live') ? (srcItem || null) : null;
    player.liveId = (kind === 'live') ? id : null;
    if (kind !== 'live') { player.channelList = null; player.channelIndex = -1; }
    player.catchupBack = 0;
    player.isVod = kind !== 'live';
    player.speed = 1;
    player.fwdStep = 30; player.backStep = 10;
    player.advanced = false; player.zoom = 1;
    Player.setZoom(1);
    Subs.off();
    $('subtitle-text').textContent = '';
    $('player-menu').classList.add('hidden');
    player.menuOpen = false;
    show('screen-player');
    $('player-channel-name').textContent = name || '';
    $('osd-progress').classList.toggle('hidden', !player.isVod);
    updateHint();
    Player.onStatus(function (t) { $('player-status').textContent = t; });
    Player.play(url);
    setFocusList([], null);
    showOsd();
    clearInterval(player.tick);
    player.tick = setInterval(updatePlayback, 500);
  }

  function stopPlayback() {
    clearInterval(player.tick);
    if (Player.isRecording()) Player.stopRecording();
    Player.stop();
    Subs.off();
  }

  function fmtTime(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), c = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (c < 10 ? '0' : '') + c;
  }

  function updatePlayback() {
    var t = Player.time();
    if (player.isVod && t.dur > 0) {
      $('osd-cur').textContent = fmtTime(t.cur);
      $('osd-dur').textContent = fmtTime(t.dur);
      $('osd-fill').style.width = Math.min(100, t.cur / t.dur * 100) + '%';
    }
    $('subtitle-text').textContent = Subs.isActive() ? Subs.textAt(t.cur) : '';
  }

  function showOsd() {
    var osd = $('player-osd');
    osd.classList.add('visible');
    clearTimeout(player.osdTimer);
    player.osdTimer = setTimeout(function () { osd.classList.remove('visible'); }, 4000);
  }

  /* قائمة المشغّل: الوضع المتقدم والزوم والسرعة والترجمة */
  var SPEEDS = [1.0, 1.25, 1.3, 1.5, 1.75, 2.0, 2.5, 3.0];
  var ZOOMS = [1, 1.25, 1.5, 2];

  function updateHint() {
    $('osd-hint').textContent = player.isVod
      ? '◀ تقديم ' + player.fwdStep + 'ث | إرجاع ' + player.backStep + 'ث ▶ | ▲ القائمة | موافق: إيقاف/تشغيل'
      : '▲ القناة السابقة | ▼ القناة التالية | 🔴 القائمة المتقدمة | موافق: إيقاف/تشغيل';
  }

  /* تبديل القناة أثناء العرض المباشر (▲/▼) — يستخدم نفس قائمة القنوات التي جئنا منها */
  function switchChannel(delta) {
    if (!player.channelList || !player.channelList.length) { toast('لا توجد قائمة قنوات لهذا العرض'); return; }
    var n = (player.channelIndex + delta + player.channelList.length) % player.channelList.length;
    player.channelIndex = n;
    var it = player.channelList[n];
    player.liveItem = it;
    player.liveId = it.stream_id;
    player.catchupBack = 0;
    Storage.addRecent(current.id, 'live', it);
    $('player-channel-name').textContent = it.name || '';
    Subs.off();
    $('subtitle-text').textContent = '';
    toast(it.name);
    Player.play(Xtream.streamUrl(current, 'live', it.stream_id, null));
  }

  function openPlayerMenu() {
    var menu = $('player-menu');
    var opts = [];

    // الإعادة للقنوات المباشرة (Catch-up / Time-shift)
    if (!player.isVod && player.liveItem && player.liveItem.tv_archive) {
      var maxMin = (player.liveItem.tv_archive_duration || 3) * 24 * 60; // الأيام إلى دقائق
      [10, 30, 60, 180].forEach(function (m) {
        if (m > maxMin) return;
        var label = m < 60 ? m + ' دقيقة' : (m / 60) + ' ساعة';
        opts.push(['⏪ إعادة ' + label + ' إلى الوراء', function () { catchupSeek(m); }]);
      });
      opts.push(['⌚ إعادة بوقت أكتبه (بالدقائق)…', function () { catchupCustom(); }]);
      if (player.catchupBack > 0) {
        opts.push(['📡 مزامنة إلى البث المباشر الآن', function () { syncToLive(); }]);
      }
    }
    if (player.isVod) {
      opts.push(['⏮ إعادة الفيلم من البداية', function () {
        Player.seekTo(0);
        toast('أُعيد الفيلم من البداية');
        closePlayerMenu();
      }]);
    }
    if (player.isVod) {
      // الوضع المتقدم: الأسهم تتحرك 5 ثوانٍ فقط للضبط الدقيق
      opts.push([player.advanced ? '✓ الأزرار المتقدمة (5 ثوانٍ) — اضغط للإيقاف' : '🎛 الأزرار المتقدمة (تقديم/إرجاع 5 ثوانٍ)', function () {
        player.advanced = !player.advanced;
        if (player.advanced) { player.fwdStep = 5; player.backStep = 5; }
        else { player.fwdStep = 30; player.backStep = 10; }
        updateHint();
        toast(player.advanced ? 'الأسهم الآن: 5 ثوانٍ' : 'عاد الوضع العادي: تقديم 30 / إرجاع 10');
        closePlayerMenu();
      }]);
      opts.push(['⏩ تقديم بعدد ثوانٍ أكتبه…', function () { customSeek(1); }]);
      opts.push(['⏪ إرجاع بعدد ثوانٍ أكتبه…', function () { customSeek(-1); }]);
      opts.push(['⏭ الذهاب إلى الدقيقة…', function () { gotoMinute(); }]);
    }
    // الزوم متاح للقنوات والأفلام معاً
    ZOOMS.forEach(function (z) {
      opts.push([(z === player.zoom ? '✓ ' : '') + '🔍 زوم ' + (z === 1 ? 'عادي' : z + 'x'), function () {
        player.zoom = z;
        var okz = Player.setZoom(z);
        toast(okz ? (z === 1 ? 'زوم عادي' : 'زوم ' + z + 'x') : 'هذا الجهاز لا يدعم الزوم');
        closePlayerMenu();
      }]);
    });
    if (player.isVod) {
      SPEEDS.forEach(function (sp) {
        opts.push([(sp === player.speed ? '✓ ' : '') + '🚀 السرعة ' + sp + 'x', function () {
          var exact = Player.setSpeed(sp);
          player.speed = sp;
          toast(exact ? 'السرعة: ' + sp + 'x' : 'هذا التلفزيون قد لا يدعم السرعات الكسرية');
          closePlayerMenu();
        }]);
      });
    }
    opts.push(['💬 ترجمة من الفلاش (USB)', chooseUsbSubtitle]);
    opts.push(['🌐 ترجمة من رابط إنترنت', chooseUrlSubtitle]);
    opts.push([Player.isRecording() ? '⏹ إيقاف التسجيل على الفلاش' : '⏺ تسجيل البث على الفلاش', function () {
      if (Player.isRecording()) {
        Player.stopRecording();
        toast('أُوقف التسجيل — الملف محفوظ على الفلاش');
        closePlayerMenu();
      } else {
        Player.startRecording($('player-channel-name').textContent, function (bytes) {
          $('player-status').textContent = 'جارٍ التسجيل… ' + (bytes / 1048576).toFixed(1) + ' MB';
        }, function (err) { toast(err); });
        toast('بدأ التسجيل على الفلاش');
        closePlayerMenu();
      }
    }]);
    if (Subs.isActive()) opts.push(['🚫 إيقاف الترجمة', function () {
      Subs.off(); $('subtitle-text').textContent = ''; toast('أُوقفت الترجمة'); closePlayerMenu();
    }]);

    menu.innerHTML = '';
    var els = opts.map(function (o) { var el = makeItem(o[0]); menu.appendChild(el); return el; });
    menu.classList.remove('hidden');
    player.menuOpen = true;
    setFocusList(els, function (idx) { opts[idx][1](); });
  }

  /* الإعادة للقنوات المباشرة: تشغيل نفس القناة من وقت سابق عبر رابط timeshift */
  function catchupSeek(minutesBack) {
    player.catchupBack = minutesBack;
    var start = new Date(Date.now() - minutesBack * 60000);
    var url = Xtream.timeshiftUrl(current, player.liveId, start, minutesBack + 15);
    toast('جارٍ تشغيل الإعادة (' + minutesBack + ' دقيقة)…');
    Player.play(url);
    closePlayerMenu();
  }

  function catchupCustom() {
    var menu = $('player-menu');
    menu.innerHTML = '';
    var host = makeItem('');
    menu.appendChild(host);
    promptInput(host, '', 'عدد الدقائق إلى الوراء…', function (v) {
      var n = parseInt(v, 10);
      if (v !== null && n > 0) catchupSeek(n);
      else closePlayerMenu();
    });
  }

  /* العودة إلى البث المباشر الحقيقي بعد استخدام الإعادة */
  function syncToLive() {
    player.catchupBack = 0;
    toast('العودة إلى البث المباشر…');
    Player.play(Xtream.streamUrl(current, 'live', player.liveId, null));
    closePlayerMenu();
  }

  function customSeek(dir) {
    var menu = $('player-menu');
    menu.innerHTML = '';
    var host = makeItem('');
    menu.appendChild(host);
    promptInput(host, '', 'عدد الثواني…', function (v) {
      var n = parseInt(v, 10);
      if (v !== null && n > 0) {
        Player.seek(dir * n);
        toast((dir > 0 ? 'تقديم ' : 'إرجاع ') + n + ' ثانية');
      }
      closePlayerMenu();
    });
  }

  function gotoMinute() {
    var menu = $('player-menu');
    menu.innerHTML = '';
    var host = makeItem('');
    menu.appendChild(host);
    promptInput(host, '', 'رقم الدقيقة…', function (v) {
      var n = parseFloat(v);
      if (v !== null && n >= 0) {
        Player.seekTo(Math.floor(n * 60));
        toast('الانتقال إلى الدقيقة ' + n);
      }
      closePlayerMenu();
    });
  }

  function closePlayerMenu() {
    $('player-menu').classList.add('hidden');
    player.menuOpen = false;
    setFocusList([], null);
    showOsd();
  }

  function chooseUsbSubtitle() {
    loader(true);
    Subs.listUsbFiles(function (files) {
      loader(false);
      var menu = $('player-menu');
      menu.innerHTML = '';
      var els = files.map(function (f) {
        var el = makeItem('💾 ' + esc(f.name));
        menu.appendChild(el);
        return el;
      });
      setFocusList(els, function (idx) {
        loader(true);
        Subs.loadFromFile(files[idx], function (n) {
          loader(false); toast('حُمّلت الترجمة (' + n + ' سطراً)'); closePlayerMenu();
        }, function (e) { loader(false); toast(e); });
      });
    }, function (e) { loader(false); toast(e); });
  }

  function chooseUrlSubtitle() {
    var menu = $('player-menu');
    menu.innerHTML = '';
    var host = makeItem('');
    menu.appendChild(host);
    promptInput(host, '', 'رابط ملف الترجمة srt…', function (url) {
      if (!url) { closePlayerMenu(); return; }
      loader(true);
      Subs.loadFromUrl(url, function (n) {
        loader(false); toast('حُمّلت الترجمة (' + n + ' سطراً)'); closePlayerMenu();
      }, function (e) { loader(false); toast(e); closePlayerMenu(); });
    });
  }

  /* ---------- الإعدادات ---------- */
  function openSettings() {
    screenStack.push(showProfiles);
    renderSettings();
  }

  /* رسم شاشة الإعدادات بلا لمس screenStack — يُستخدم من openSettings (فتح جديد) ومن
     __onAndroidResume (تحديث حالة "تبديل القنوات بالريموت" فوراً بعد رجوع المستخدم من
     شاشة إتاحة أندرويد، بلا إضافة خطوة رجوع مكرّرة) */
  function renderSettings() {
    show('screen-settings');
    var box = $('settings-list');
    box.innerHTML = '';
    var els = [], actions = [];
    profiles.forEach(function (p, i) {
      var el = makeItem(esc(p.name) + ' <span class="sub">' + esc(p.host) + '</span>');
      box.appendChild(el); els.push(el);
      actions.push(function () { profileActions(p, i); });
    });
    var add = makeItem('➕ إضافة اشتراك جديد');
    box.appendChild(add); els.push(add);
    actions.push(function () { openForm(null); });

    var bucketSub = Cloud.hasBucket() ? ' <span class="sub">رمز جهازك: ' + esc(Cloud.getBucketId()) + '</span>' : '';
    var backupEl = makeItem('☁️ نسخ احتياطي أونلاين (مشفّر)' + bucketSub);
    box.appendChild(backupEl); els.push(backupEl);
    actions.push(cloudBackup);

    var restoreEl = makeItem('⬇️ استيراد نسخة احتياطية');
    box.appendChild(restoreEl); els.push(restoreEl);
    actions.push(cloudRestore);

    var playerEl = makeItem('▶ المشغّل المفضل: <span class="sub">' + PLAYER_CHOICE_NAMES[Storage.getPreferredPlayer()] + '</span>');
    box.appendChild(playerEl); els.push(playerEl);
    actions.push(cyclePreferredPlayer);

    var pageSizeEl = makeItem('🔢 عدد البطاقات بالدفعة: <span class="sub">' + Storage.getPageSize() + '</span>');
    box.appendChild(pageSizeEl); els.push(pageSizeEl);
    actions.push(changePageSize);

    if (typeof AndroidSystem !== 'undefined' && AndroidSystem.openAccessibilitySettings) {
      var zapOn = AndroidSystem.isRemoteZapEnabled();
      var zapEl = makeItem('🔀 تبديل القنوات بسهم فوق/تحت أثناء العرض: <span class="sub">' +
        (zapOn ? 'مفعّلة ✓' : 'غير مفعّلة — اضغط للتفعيل') + '</span>');
      box.appendChild(zapEl); els.push(zapEl);
      actions.push(openRemoteZapSettings);
    }

    setFocusList(els, function (idx) { actions[idx](); });
  }

  /* يفتح شاشة "الإتاحة" الحقيقية بإعدادات أندرويد — التفعيل نفسه يدوي إجبارياً (لا يوجد API
     يفعّل خدمة إتاحة برمجياً لأسباب أمنية)، فقط نوصل المستخدم للمكان الصحيح ونشرح له بالتوست */
  function openRemoteZapSettings() {
    toast('بشاشة الإتاحة التالية: فعّل «myTv+ — تبديل القنوات بالريموت»، ثم ارجع بزر الرجوع', 6000);
    AndroidSystem.openAccessibilitySettings();
  }

  /* ---------- المشغّل المفضل لفتح البث خارجياً ---------- */
  var PLAYER_CHOICES = ['', 'org.videolan.vlc', 'com.mxtech.videoplayer.ad'];
  var PLAYER_CHOICE_NAMES = {
    '': 'اسأل دائماً',
    'org.videolan.vlc': 'VLC',
    'com.mxtech.videoplayer.ad': 'MX Player'
  };
  function cyclePreferredPlayer() {
    var cur = Storage.getPreferredPlayer();
    var idx = PLAYER_CHOICES.indexOf(cur);
    var next = PLAYER_CHOICES[(idx + 1) % PLAYER_CHOICES.length];
    Storage.setPreferredPlayer(next);
    toast('المشغّل المفضل: ' + PLAYER_CHOICE_NAMES[next]);
    openSettings();
  }

  /* عدد البطاقات المرسومة دفعة واحدة بالقوائم الضخمة — أقل = أخف على الذاكرة، أكثر = تحميل أقل */
  function changePageSize() {
    promptInput(null, String(Storage.getPageSize()), 'عدد البطاقات بالدفعة (20 - 2000)…', function (v) {
      if (v === null) { openSettings(); return; }
      var n = parseInt(v, 10);
      if (!n || n < 20) n = 20;
      if (n > 2000) n = 2000;
      Storage.setPageSize(n);
      toast('عدد البطاقات بالدفعة: ' + n);
      openSettings();
    });
  }

  /* ---------- النسخ الاحتياطي السحابي (انظر cloud.js) ---------- */
  function cloudBackup() {
    promptInput(null, '', 'كلمة مرور لتشفير النسخة الاحتياطية…', function (pw) {
      if (!pw) { openSettings(); return; }
      loader(true);
      Cloud.backup(pw, function (bucketId) {
        loader(false);
        // الرمز يبقى ظاهراً دائماً تحت خيار النسخ الاحتياطي بشاشة الإعدادات، لا داعي لحفظه فوراً
        toast('تم الحفظ ✓ — الرمز: ' + bucketId, 6000);
        openSettings();
      }, function (err) { loader(false); toast(err); openSettings(); });
    });
  }

  function cloudRestore() {
    function askPasswordAndRestore() {
      promptInput(null, '', 'كلمة مرور النسخة الاحتياطية…', function (pw) {
        if (!pw) { openSettings(); return; }
        loader(true);
        Cloud.restore(pw, function () {
          loader(false);
          profiles = Storage.load();
          toast('تم الاستيراد ✓');
          openSettings();
        }, function (err) { loader(false); toast(err); openSettings(); });
      });
    }
    if (Cloud.hasBucket()) { askPasswordAndRestore(); return; }
    promptInput(null, '', 'رمز النسخة الاحتياطية (من الجهاز الأول)…', function (code) {
      if (!code) { openSettings(); return; }
      Cloud.setBucketId(code);
      askPasswordAndRestore();
    });
  }

  function profileActions(p, i) {
    screenStack.push(openSettings);
    show('screen-settings');
    var box = $('settings-list');
    box.innerHTML = '';
    var opts = [
      ['✏ تعديل البيانات', function () { openForm(p); }],
      [p.pinHash ? '🔓 إزالة كلمة مرور الفتح' : '🔒 وضع كلمة مرور للفتح', function () {
        if (p.pinHash) {
          screenStack.push(function () { profileActions(p, i); });
          pinScreen('أدخل كلمة المرور الحالية', p.pinHash, function () {
            p.pinHash = null; Storage.save(profiles); toast('أُزيلت كلمة المرور'); screenStack.pop(); openSettings();
          });
        } else {
          pinSetNew('كلمة مرور فتح الاشتراك', function (v) {
            p.pinHash = Storage.hash(v); Storage.save(profiles); toast('وُضعت كلمة المرور'); openSettings();
          });
        }
      }],
      [p.adultPinHash ? 'إلغاء تصفية المحتوى' : 'تفعيل تصفية المحتوى', function () {
        if (p.adultPinHash) {
          screenStack.push(function () { profileActions(p, i); });
          pinScreen('أدخل كلمة المرور', p.adultPinHash, function () {
            p.adultPinHash = null; Storage.save(profiles); toast('تم ✓'); screenStack.pop(); openSettings();
          });
        } else {
          pinSetNew('كلمة مرور التصفية', function (v) {
            p.adultPinHash = Storage.hash(v); Storage.save(profiles); toast('تم ✓'); openSettings();
          });
        }
      }],
      ['🗑 حذف الاشتراك', function () {
        profiles.splice(i, 1); Storage.save(profiles); toast('حُذف الاشتراك'); openSettings();
      }]
    ];
    var els = opts.map(function (o) { var el = makeItem(o[0]); box.appendChild(el); return el; });
    setFocusList(els, function (idx) { opts[idx][1](); });
  }

  function pinSetNew(title, ok) {
    screenStack.push(openSettings);
    pin = { value: '', target: null, ok: function (v) {
      if (!v) { toast('كلمة المرور فارغة'); return; }
      screenStack.pop();
      ok(v);
    } };
    show('screen-pin');
    $('pin-title').textContent = title;
    renderPinDots();
    var pad = $('pin-pad');
    pad.innerHTML = '';
    var els = PIN_KEYS.map(function (k) { var el = makeItem(k); pad.appendChild(el); return el; });
    setFocusList(els, function (idx) { pinPress(PIN_KEYS[idx]); }, 3);
  }

  /* ---------- نموذج إضافة/تعديل ---------- */
  function openForm(existing) {
    screenStack.push(openSettings);
    show('screen-form');
    $('form-title').textContent = existing ? 'تعديل الاشتراك' : 'اشتراك جديد';
    var data = existing ? JSON.parse(JSON.stringify(existing)) : { name: '', host: '', username: '', password: '' };
    var fields = [
      ['الاسم', 'name'], ['الرابط (http://...)', 'host'],
      ['اسم المستخدم', 'username'], ['كلمة مرور الاشتراك', 'password']
    ];
    var box = $('form-fields');

    function render() {
      box.innerHTML = '';
      var els = fields.map(function (f) {
        var el = makeItem(f[0] + ': <span class="sub">' + esc(data[f[1]] || '(فارغ)') + '</span>');
        box.appendChild(el);
        return el;
      });
      var saveEl = makeItem('💾 حفظ');
      box.appendChild(saveEl); els.push(saveEl);

      setFocusList(els, function (idx) {
        if (idx < fields.length) {
          promptInput(els[idx], data[fields[idx][1]] || '', fields[idx][0], function (val) {
            if (val !== null) data[fields[idx][1]] = val;
            render();
          });
        } else {
          if (!data.host || !data.username) { toast('الرابط واسم المستخدم مطلوبان'); return; }
          if (!/^https?:\/\//.test(data.host)) data.host = 'http://' + data.host;
          if (!data.name) data.name = data.host.replace(/^https?:\/\//, '');
          if (existing) {
            for (var k in data) existing[k] = data[k];
          } else {
            data.id = Storage.newId(); data.pinHash = null; data.adultPinHash = null;
            profiles.push(data);
          }
          Storage.save(profiles);
          toast('تم الحفظ ✓');
          verifyProfile(existing || data);
          openSettings();
        }
      });
    }
    render();
  }

  /* ---------- لوحة مفاتيح على الشاشة (عربي/إنجليزي) ----------
     بديل عن حقل <input> حقيقي: الريموت/البوكس لا يفتح لوحة مفاتيح النظام دوماً،
     فنكتب بالتنقل بالأسهم واختيار الحرف بموافق، مع زر لتبديل اللغة عربي↔إنجليزي */
  var KB_LETTERS = {
    ar: ['ا', 'ب', 'ت', 'ث', 'ج', 'ح', 'خ', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض',
      'ط', 'ظ', 'ع', 'غ', 'ف', 'ق', 'ك', 'ل', 'م', 'ن', 'ه', 'و', 'ي', 'ة', 'ء', 'ئ', 'ؤ'],
    en: ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P', 'A', 'S', 'D', 'F', 'G',
      'H', 'J', 'K', 'L', 'Z', 'X', 'C', 'V', 'B', 'N', 'M']
  };
  var KB_DIGITS = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
  var KB_COLS = 10;
  var kb = { open: false, value: '', lang: 'ar', done: null, prevId: null };

  function promptInput(hostEl, initial, placeholder, done) {
    kb.open = true;
    kb.value = initial || '';
    kb.lang = 'ar';
    kb.done = done;
    kb.prevId = document.querySelector('.screen:not(.hidden)').id;
    $('kb-label').textContent = placeholder || '';
    show('screen-keyboard');
    renderKeyboard();
  }

  function closeKeyboard(result) {
    kb.open = false;
    var cb = kb.done; kb.done = null;
    show(kb.prevId);
    cb(result);
  }

  function renderKeyboard() {
    $('kb-preview').textContent = kb.value || '‌';
    var box = $('kb-grid');
    var keepIdx = focus.idx || 0;
    box.innerHTML = '';

    var keys = KB_LETTERS[kb.lang].concat(KB_DIGITS);
    var els = keys.map(function (ch) {
      var el = makeItem(ch);
      box.appendChild(el);
      return el;
    });
    var actions = keys.map(function (ch) {
      return function () { kb.value += ch; renderKeyboard(); };
    });

    var ctrl = [
      ['🌐 ' + (kb.lang === 'ar' ? 'English' : 'عربي'), function () { kb.lang = kb.lang === 'ar' ? 'en' : 'ar'; renderKeyboard(); }],
      ['␣ مسافة', function () { kb.value += ' '; renderKeyboard(); }],
      ['⌫ حذف', function () { kb.value = kb.value.slice(0, -1); renderKeyboard(); }],
      ['🧹 مسح الكل', function () { kb.value = ''; renderKeyboard(); }],
      ['✔ تم', function () { closeKeyboard(kb.value.trim()); }],
      ['✕ إلغاء', function () { closeKeyboard(null); }]
    ];
    ctrl.forEach(function (c) {
      var el = makeItem(c[0], 'kb-ctrl');
      box.appendChild(el);
      els.push(el);
      actions.push(c[1]);
    });

    setFocusList(els, function (idx) { actions[idx](); }, KB_COLS);
    focus.idx = Math.max(0, Math.min(keepIdx, els.length - 1));
    paintFocus();
  }

  function verifyProfile(p) {
    Xtream.account(p, function (info) {
      if (info && info.user_info && info.user_info.auth === 1) toast('الاشتراك «' + p.name + '» يعمل ✓');
      else toast('تنبيه: بيانات «' + p.name + '» غير صحيحة');
    }, function () { toast('تنبيه: تعذر الاتصال بسيرفر «' + p.name + '»'); });
  }

  /* ---------- صندوق تأكيد عام (نعم/لا) ---------- */
  var confirmDialog = { open: false };

  function showConfirm(text, onYes) {
    confirmDialog.open = true;
    $('confirm-text').textContent = text;
    var box = $('confirm-actions');
    box.innerHTML = '';
    // "لا" أولاً لتكون هي التركيز الافتراضي — حماية من خروج بالخطأ
    var opts = [['لا', function () { closeConfirm(); }], ['نعم', function () { closeConfirm(); onYes(); }]];
    var els = opts.map(function (o) { var el = makeItem(o[0]); box.appendChild(el); return el; });
    $('confirm-dialog').classList.remove('hidden');
    setFocusList(els, function (idx) { opts[idx][1](); }, 2);
  }

  function closeConfirm() {
    confirmDialog.open = false;
    $('confirm-dialog').classList.add('hidden');
    homeScreen();
  }

  /* إغلاق التطبيق فعلياً: عبر tizen على التلفزيون، أو جسر أندرويد على البوكسات */
  function exitApp() {
    try { tizen.application.getCurrentApplication().exit(); } catch (e) {}
    try { AndroidExit.exit(); } catch (e) {}
  }

  /* ---------- زر الرجوع ---------- */
  function back() {
    stopPreviewVideo();
    if (kb.open) { closeKeyboard(null); return; }
    if (confirmDialog.open) { closeConfirm(); return; }
    if (player.menuOpen) { closePlayerMenu(); return; }
    // من الشاشة الرئيسية: نسأل عن الخروج بدل الرجوع مباشرة لقائمة الاشتراكات
    if (!$('screen-home').classList.contains('hidden')) { showConfirm('هل تريد الخروج من التطبيق؟', exitApp); return; }
    var fn = screenStack.pop();
    if (fn) fn();
    else exitApp();
  }

  /* ---------- المفاتيح ---------- */
  try {
    ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaRewind', 'MediaFastForward', 'ColorF0Red'].forEach(function (k) {
      tizen.tvinputdevice.registerKey(k);
    });
  } catch (e) {}

  document.addEventListener('keydown', function (ev) {
    var k = ev.keyCode;

    // إدخال أرقام مباشرة في شاشة كلمة المرور
    if (!$('screen-pin').classList.contains('hidden') && k >= 48 && k <= 57) {
      pinPress(String(k - 48)); return;
    }

    // شاشة التصفح لها منطق تنقل خاص بثلاث مناطق (بحث/تصنيفات/عناصر)
    if (!$('screen-browse').classList.contains('hidden')) { handleBrowseKeys(k, ev); return; }

    var inPlayer = !$('screen-player').classList.contains('hidden');

    // مفاتيح المشغّل (خارج قائمة المشغّل)
    if (inPlayer && !player.menuOpen) {
      showOsd();
      switch (k) {
        case 37: ev.preventDefault(); if (player.isVod) Player.seek(player.fwdStep); return;    // يسار = تقديم (RTL)
        case 39: ev.preventDefault(); if (player.isVod) Player.seek(-player.backStep); return;  // يمين = إرجاع
        case 417: case 10233: if (player.isVod) Player.seek(player.fwdStep); return;            // FF
        case 412: case 10232: if (player.isVod) Player.seek(-player.backStep); return;          // REW
        case 38: ev.preventDefault(); if (player.isVod) openPlayerMenu(); else switchChannel(-1); return;  // أعلى: قائمة (فيلم) أو قناة سابقة (مباشر)
        case 40: ev.preventDefault(); if (!player.isVod) switchChannel(1); return;                          // أسفل: القناة التالية (مباشر)
        case 13: ev.preventDefault(); Player.togglePause(); return;
        case 10252: case 415: case 19: Player.togglePause(); return;
        case 403: case 70: ev.preventDefault(); openPlayerMenu(); return;    // الزر الأحمر: القائمة المتقدمة (يعمل دوماً)
        case 10009: case 27: ev.preventDefault(); back(); return;
      }
      return;
    }

    switch (k) {
      case 38: ev.preventDefault(); moveFocus(-focus.cols); break;          // أعلى
      case 40: ev.preventDefault(); moveFocus(focus.cols); break;           // أسفل
      case 37: ev.preventDefault(); if (focus.cols > 1) moveFocus(1); break; // يسار
      case 39: ev.preventDefault(); if (focus.cols > 1) moveFocus(-1); break; // يمين
      case 13: ev.preventDefault();                                          // موافق
        if (focus.onEnter) focus.onEnter(focus.idx);
        break;
      case 10009: case 27: ev.preventDefault(); back(); break;               // رجوع
      case 10252: case 415: case 19: Player.togglePause(); break;            // تشغيل/إيقاف
      case 403: case 70: redButton(); break;                                 // الزر الأحمر أو F: مفضلة/حذف
    }
  });

  /* ---------- البداية ----------
     نفتح مباشرة على آخر اشتراك استُخدم (إن وُجد) بدل إجبار الاختيار في كل مرة؛
     أيقونة "تبديل الاشتراك" أعلى الشاشة الرئيسية تعيد الاختيار متى شاء. */
  (function start() {
    var lastId = Storage.getLastProfile();
    var p = lastId && profiles.filter(function (pp) { return pp.id === lastId; })[0];
    if (!p) { showProfiles(); return; }
    if (p.pinHash) askPin('أدخل كلمة مرور «' + p.name + '»', p.pinHash, function () { openProfile(p); });
    else openProfile(p);
  })();

  // ينادى من MainActivity.onResume (أندرويد فقط) — يحدّث شاشة الإعدادات إن كانت مفتوحة فوراً
  // بعد رجوع المستخدم من شاشة إتاحة أندرويد، بدل ما يبقى يشوف حالة "تبديل القنوات" قديمة
  window.__onAndroidResume = function () {
    var s = $('screen-settings');
    if (s && !s.classList.contains('hidden')) renderSettings();
  };
})();
