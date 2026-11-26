package com.smarttvplus.iptvmtc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «المساعد الذكي» (طلب أبي فيصل 2026-11-26): يقرأ سجلّ التطبيق وحالته، يفحص الشبكة والاشتراك،
 * يطبّق إصلاحاً من قائمة محدودة (إعدادات فقط، كلها تُعاد من شاشة الإعدادات)، ويكتب تقريراً عربياً.
 * وما يحتاج تعديل الكود يكتبه في قسم «للمطوّر» ويُرفع إلى سجلّ التشخيص عن بُعد.
 *
 * العمل يملكه هذا الكائن لا الشاشة: يكمل بعد مغادرة شاشة المساعد، وحالته تُقرأ بـ{@link #stateJson()}.
 * الفحص التلقائي عند الأخطاء محدود: مرة كل 30 دقيقة، و4 مرات في اليوم، ولا يُعاد لخطأ دُرس خلال 24 ساعة.
 */
final class AiDoctor {

    private static final String MODEL = "claude-opus-5-5";
    private static final long AUTO_GAP_MS = 30 * 60 * 1000L;
    private static final int AUTO_PER_DAY = 4;
    private static final long SAME_ERROR_MS = 24 * 3600 * 1000L;
    private static final int MAX_ROUNDS = 8;
    private static final int MAX_NOTES = 30;
    private static final int MAX_HISTORY = 8;
    private static final int MAX_CHAT = 40;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static Context app;
    private static SharedPreferences prefs;

    private static volatile boolean running;
    private static volatile String step = "";
    private static volatile boolean autoPending;

    private AiDoctor() {}

    static void init(Context c) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences("ai_doctor", Context.MODE_PRIVATE);
    }

    static boolean hasKey() { return BuildConfig.ANTHROPIC_API_KEY.length() > 0; }

    static boolean autoEnabled() { return prefs != null && prefs.getBoolean("auto", true); }

    static void setAuto(boolean on) { prefs.edit().putBoolean("auto", on).apply(); }

    static void clearNotes() { prefs.edit().remove("notes").apply(); }

    /** محادثة جديدة: تُمسح الرسائل السابقة فلا تُرسل مع الرسالة القادمة */
    static void clearChat() { prefs.edit().remove("chat").apply(); }

    private static void addChatTurn(String role, String text, String error, JSONArray actions) {
        try {
            JSONArray old = new JSONArray(prefs.getString("chat", "[]"));
            JSONArray chat = new JSONArray();
            for (int i = Math.max(0, old.length() - (MAX_CHAT - 1)); i < old.length(); i++) chat.put(old.get(i));
            JSONObject t = new JSONObject();
            t.put("role", role);
            t.put("text", text);
            t.put("error", error);
            t.put("t", System.currentTimeMillis());
            if (actions != null) t.put("actions", actions);
            chat.put(t);
            prefs.edit().putString("chat", chat.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** الحالة كاملة للواجهة: هل يعمل، الخطوة الجارية، آخر التقارير، وما تعلّمه */
    static String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("hasKey", hasKey());
            o.put("running", running);
            o.put("step", step);
            o.put("auto", autoEnabled());
            o.put("history", new JSONArray(prefs.getString("history", "[]")));
            o.put("notes", new JSONArray(prefs.getString("notes", "[]")));
            o.put("chat", new JSONArray(prefs.getString("chat", "[]")));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /* ---------- الفحص التلقائي عند الأخطاء ---------- */

    private static boolean looksLikeError(String tag, String msg) {
        if ("jserr".equals(tag) || "crash".equals(tag) || "neterr".equals(tag)) return true;
        String m = msg.toLowerCase(java.util.Locale.US);
        return m.contains("failed") || m.contains("exception") || m.contains("too many returns");
    }

    /** يُنادى لكل سطر في السجلّ ({@link RemoteLog#log}) */
    static void observe(String tag, String msg) {
        if (prefs == null || "ai".equals(tag) || running || autoPending) return;
        if (!hasKey() || !autoEnabled() || !looksLikeError(tag, msg)) return;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("lastAuto", 0) < AUTO_GAP_MS) return;
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date(now));
        int count = day.equals(prefs.getString("autoDay", "")) ? prefs.getInt("autoCount", 0) : 0;
        if (count >= AUTO_PER_DAY) return;
        // توقيع الخطأ: الوسم والنص بلا أرقام، حتى لا يُدرس الخطأ نفسه مرتين في اليوم
        String sig = "sig_" + (tag + ":" + msg.replaceAll("[0-9]+", "#")).hashCode();
        if (now - prefs.getLong(sig, 0) < SAME_ERROR_MS) return;
        prefs.edit().putLong("lastAuto", now).putString("autoDay", day).putInt("autoCount", count + 1)
                .putLong(sig, now).apply();
        autoPending = true;
        final String trigger = tag + ": " + cut(msg, 300);
        worker.execute(() -> {
            // مهلة قصيرة لتتجمع الأسطر التالية للخطأ قبل قراءة السجلّ
            try { Thread.sleep(20000); } catch (InterruptedException ignored) {}
            autoPending = false;
            run("auto", trigger);
        });
    }

    /** بدء يدوي من شاشة المساعد. يعيد نصاً فارغاً إن بدأ، أو سبب الرفض. */
    static String start(final String mode, final String question) {
        if (!hasKey()) return "هذه النسخة بُنيت بلا مفتاح الذكاء الاصطناعي";
        if (running) return "المساعد يعمل الآن";
        running = true;   // قبل الدخول في الطابور، حتى تعرضه الواجهة فوراً
        step = "يبدأ…";
        if ("chat".equals(mode)) addChatTurn("user", question, "", null);
        worker.execute(() -> run(mode, question));
        return "";
    }

    /* ---------- التنفيذ ---------- */

    private static void run(String mode, String question) {
        running = true;
        step = "يجمع السجلّ والحالة…";
        MainActivity.notifyAi();
        long startedAt = SystemClock.elapsedRealtime();
        try { app.startForegroundService(new Intent(app, AiService.class)); } catch (Exception ignored) {}

        JSONArray actions = new JSONArray();
        String report = "", error = "";
        long[] tokens = new long[2];
        try {
            report = converse(mode, question, actions, tokens);
        } catch (AnthropicServiceException e) {
            error = friendly(e);
        } catch (AnthropicIoException e) {
            error = "تعذر الاتصال بخدمة الذكاء الاصطناعي — تحقق من الإنترنت";
        } catch (Throwable t) {
            error = "خطأ في المساعد: " + t;
        }

        if ("chat".equals(mode)) addChatTurn("ai", report, error, actions);
        else try {
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            entry.put("mode", mode);
            entry.put("question", question == null ? "" : question);
            entry.put("report", report);
            entry.put("error", error);
            entry.put("actions", actions);
            entry.put("tokensIn", tokens[0]);
            entry.put("tokensOut", tokens[1]);
            JSONArray old = new JSONArray(prefs.getString("history", "[]"));
            JSONArray hist = new JSONArray();
            hist.put(entry);
            for (int i = 0; i < old.length() && hist.length() < MAX_HISTORY; i++) hist.put(old.get(i));
            prefs.edit().putString("history", hist.toString()).apply();
        } catch (Exception ignored) {}

        // التقرير يصل المطوّر عبر سجلّ التشخيص عن بُعد
        RemoteLog.log("ai", "[" + mode + "] " + (error.length() > 0 ? "ERROR " + error : report)
                + (actions.length() > 0 ? "\nactions=" + actions : "")
                + "\ntokens in=" + tokens[0] + " out=" + tokens[1]);
        RemoteLog.flush();

        running = false;
        step = "";
        // الخدمة الأمامية يجب أن تبلغ startForeground قبل إيقافها، وإلا أسقط النظام التطبيق
        long ran = SystemClock.elapsedRealtime() - startedAt;
        if (ran < 2000) try { Thread.sleep(2000 - ran); } catch (InterruptedException ignored) {}
        try { app.stopService(new Intent(app, AiService.class)); } catch (Exception ignored) {}
        MainActivity.notifyAi();
    }

    private static final String SYSTEM =
            "أنت «المساعد الذكي» داخل تطبيق myTv+ على تلفاز أو بوكس أندرويد. التطبيق واجهة ويب (WebView) "
            + "لاشتراكات IPTV بنظام Xtream، يشغّل الفيديو في تطبيق مستقل اسمه Player+، وفيه نافذة HDMI "
            + "مصغّرة ونافذة قناة عائمة. صاحبه أبو فيصل، وهو مستخدم لا مبرمج، ويقرأ ردّك على شاشة التلفاز.\n\n"
            + "مهمتك: ادرس السجلّ والحالة المرفقين، وحدّد ما تعطّل ولماذا. استعمل الأدوات لتتحقق قبل أن تحكم: "
            + "افحص الشبكة والاشتراك إن كان الخطأ اتصالاً، واطلب أسطراً أكثر من السجلّ إن احتجت. فرّق بين ما "
            + "تأكدت منه وما ترجّحه.\n\n"
            + "الإصلاح: أداة apply_fix تغيّر إعدادات فقط، وكلها تُعاد من شاشة الإعدادات. طبّق إصلاحاً حين يدلّ "
            + "السجلّ بوضوح على أنه يعالج السبب، ولا تغيّر إعداداً اختاره المستخدم لمجرد الاحتمال. لا تستطيع "
            + "تعديل كود التطبيق؛ فإن كان العلاج تعديلاً في الكود فاكتبه في قسم «للمطوّر» بدقة (اسم الملف أو "
            + "الدالة إن ظهر في السجلّ، والأسطر الدالة، وما تقترحه)، وهذا القسم يُرفع للمطوّر تلقائياً.\n\n"
            + "التعلّم: «الدروس» المرفقة كتبتها أنت في فحوص سابقة. إن خرجت من هذا الفحص بدرس جديد ينفع في "
            + "فحص قادم (سبب متكرر، إصلاح نفع أو لم ينفع، خاصية في هذا الجهاز) فاحفظه بأداة remember في سطر "
            + "واحد. لا تحفظ ما هو موجود أصلاً في الدروس.\n\n"
            + "حقائق عن هذا التطبيق: رمز HTTP 458 أو 456 من سيرفر الاشتراك يعني أن حد الاتصالات بلغ أقصاه "
            + "(جهاز آخر يشاهد، أو جلسة القناة السابقة لم تُغلق بعد وتزول خلال دقيقة تقريباً). أسطر onPause و"
            + "onStop وonResume طبيعية وليست أخطاء. السطر «left front … return» يعني أن تطبيق التلفاز انتزع "
            + "الشاشة بعد التوليف على HDMI فأعاد التطبيق نفسه، و«too many returns» يعني أن ذلك تكرر حتى توقف "
            + "عن العودة. أسطر الوسم toast هي الرسائل التي رآها المستخدم على الشاشة. وعلى تلفاز TCL (سجلّ "
            + "2026-11-26): ما دامت نافذة HDMI تعمل ينتزع تطبيق التلفاز الشاشة إلى المدخل بملء الشاشة بعد كل "
            + "توليف تقريباً، ويتكرر ذلك عند كل عودة للتطبيق؛ والمرجّح غير المؤكد أنها تمنع Player+ من تشغيل "
            + "الفيديو (ERROR_CODE_DECODING_FAILED) لأن شريحة التلفاز لا تعالج المدخل وفيلماً معاً. العلاج "
            + "المتاح لك في الحالين إطفاء النافذة (hdmi_window_off)، وما سواه يحتاج تحديثاً من المطوّر.\n\n"
            + "الرد النهائي: نص عربي عادي بلا رموز تنسيق (لا نجوم ولا عناوين بعلامة #)، أسطر قصيرة، في ما لا "
            + "يزيد على 15 سطراً. ابدأ بالخلاصة في سطر، ثم «السبب»، ثم «ما فعلتُه» إن طبّقت شيئاً، ثم «ما "
            + "عليك فعله» إن بقي شيء بيد المستخدم، ثم «للمطوّر» إن لزم تعديل في الكود. وإن لم تجد خطأً فقل "
            + "ذلك في سطرين ولا تختلق مشكلة.\n\n"
            + "في المحادثة (حين تبدأ الرسالة بـ«محادثة»): أجب عن رسالة أبي فيصل مباشرة بأسلوب حوار، في أسطر "
            + "قليلة وبلا تقسيم التقرير أعلاه. إن طلب تغيير إعداد من قائمة apply_fix فنفّذه وأخبره بما تم. "
            + "وإن طلب ما لا تملكه الأدوات (ميزة جديدة، تغيير في سلوك التطبيق) فقل له بوضوح أنه يحتاج "
            + "تحديثاً من المطوّر، واكتب الطلب في سطر يبدأ بـ«للمطوّر:» ليصله. الحالة والسجلّ المرفقان "
            + "بكل رسالة محدَّثان لحظة إرسالها. وفي المحادثة تملك أداة tv_control للتحكم بالتلفاز: نفّذ بها ما "
            + "يطلبه أبو فيصل صراحةً (صوت، تطبيق، مدخل، النافذة المصغّرة، شاشة في myTv+)، وأخبره بالنتيجة كما "
            + "أعادتها الأداة، ولا تقل إن أمراً تمّ إن أعادت «لم يُنفَّذ». إن طلب تطبيقاً باسمه فاستعمل "
            + "list_apps أولاً لتعرف حزمته. رسائله قد تأتي من التعرف على الكلام فتحمل أخطاء سمع؛ افهم "
            + "المقصود من السياق، واسأله إن التبس عليك أمر له أثر.";

    private static String converse(String mode, String question, JSONArray actions, long[] tokens) throws Exception {
        final List<String> secrets = secrets();
        StringBuilder ctx = new StringBuilder();
        if ("chat".equals(mode)) {
            ctx.append("محادثة. رسالة أبي فيصل:\n").append(question).append("\n\n");
        } else if ("auto".equals(mode)) {
            ctx.append("فحص تلقائي أطلقه هذا السطر في السجلّ:\n").append(question).append("\n\n");
        } else if (question != null && question.length() > 0) {
            ctx.append("أبو فيصل يسأل أو يصف مشكلة:\n").append(question).append("\n\n");
        } else {
            ctx.append("فحص عام طلبه أبو فيصل: هل في التطبيق خطأ الآن أو في آخر استعمال؟\n\n");
        }
        ctx.append("الوقت الآن: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date())).append("\n");
        ctx.append("الجهاز: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("، أندرويد ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
                .append(")، النسخة ").append(BuildConfig.VERSION_NAME).append(' ').append(BuildConfig.FLAVOR)
                .append("، الذاكرة ").append(memory()).append("\n\n");
        ctx.append("حالة التطبيق وإعداداته:\n").append(MainActivity.callJs("AiTools.state()")).append("\n\n");
        ctx.append("الدروس من فحوص سابقة:\n").append(notesText()).append("\n\n");
        ctx.append("آخر أسطر السجلّ (الأقدم أولاً):\n").append(RemoteLog.recent(150, null));

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(BuildConfig.ANTHROPIC_API_KEY)
                .timeout(Duration.ofMinutes(3))
                .maxRetries(1)
                .build();

        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build());
        if ("chat".equals(mode)) {
            // ما سبق من المحادثة نصاً: كل رسالة تلاها ردّ ناجح (الرسالة الحالية آخر القائمة فتُستثنى)
            JSONArray chat = new JSONArray(prefs.getString("chat", "[]"));
            for (int i = 0; i + 1 < chat.length() - 1; i++) {
                JSONObject u = chat.getJSONObject(i), a = chat.getJSONObject(i + 1);
                if (!"user".equals(u.optString("role")) || !"ai".equals(a.optString("role"))) continue;
                if (a.optString("error").length() > 0 || a.optString("text").length() == 0) continue;
                b.addUserMessage(redact(u.optString("text"), secrets));
                b.addAssistantMessage(a.optString("text"));
                i++;
            }
        }
        b.addUserMessage(redact(ctx.toString(), secrets))
                // عند رفض نادر من مصنّف الأمان يُعاد الطلب على نموذج بديل من الخادم (كما في Player+)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (Tool t : tools("chat".equals(mode))) b.addTool(t);

        StringBuilder text = new StringBuilder();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            step = round == 0 ? "يدرس السجلّ…" : step;
            MainActivity.notifyAi();
            Message msg = client.messages().create(b.build());
            tokens[0] += msg.usage().inputTokens();
            tokens[1] += msg.usage().outputTokens();

            text.setLength(0);
            List<ToolUseBlock> calls = new ArrayList<>();
            for (ContentBlock block : msg.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
                block.toolUse().ifPresent(calls::add);
            }
            StopReason reason = msg.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(reason)) throw new Exception("رفضت الخدمة الطلب");
            if (StopReason.MAX_TOKENS.equals(reason)) throw new Exception("انقطع ردّ المساعد قبل اكتماله");
            if (!StopReason.TOOL_USE.equals(reason) || calls.isEmpty()) break;

            b.addMessage(msg);
            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock call : calls) {
                String out;
                boolean failed = false;
                try {
                    out = runTool(call.name(), toJson(call._input()), actions, secrets);
                } catch (Exception e) {
                    out = "فشلت الأداة: " + e;
                    failed = true;
                }
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id())
                        .content(redact(out, secrets))
                        .isError(failed)
                        .build()));
            }
            b.addMessage(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
            if (round == MAX_ROUNDS - 2) {
                b.addMessage(MessageParam.builder().role(MessageParam.Role.USER)
                        .content("هذه آخر جولة: اكتب التقرير النهائي الآن بلا أدوات.").build());
            }
        }
        String out = text.toString().trim();
        if (out.length() == 0) throw new Exception("لم يكتب المساعد تقريراً");
        return out;
    }

    /* ---------- الأدوات ---------- */

    private static Tool tool(String name, String description, Map<String, Object> props, String... required) {
        Tool.InputSchema.Properties.Builder p = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, Object> e : props.entrySet()) p.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder().properties(p.build()).required(Arrays.asList(required)).build())
                .build();
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    static final List<String> FIXES = Arrays.asList(
            "hdmi_window_off", "hdmi_sound_off", "hdmi_sound_on", "float_channel_off",
            "player_live_playerplus", "player_live_vlc", "player_live_mx",
            "player_movie_playerplus", "player_movie_vlc", "player_movie_mx",
            "page_size_100", "page_size_200", "clear_cache", "reload_ui", "check_updates");

    static final List<String> TV_ACTIONS = Arrays.asList(
            "volume_up", "volume_down", "volume_set", "mute", "unmute",
            "media_play_pause", "media_next", "media_previous", "home", "back",
            "list_apps", "launch_app", "list_inputs", "open_input",
            "window_on", "window_off", "window_corner", "window_size", "open_screen");

    /** @param control أداة التحكم بالتلفاز تُعطى في المحادثة فقط: لا يتحكم المساعد بالتلفاز من تلقاء نفسه */
    private static List<Tool> tools(boolean control) {
        List<Tool> list = new ArrayList<>();

        Map<String, Object> logProps = new HashMap<>();
        logProps.put("lines", prop("integer", "عدد الأسطر المطلوبة من آخر السجلّ، حتى 500"));
        logProps.put("contains", prop("string", "اختياري: نص تُصفّى به الأسطر (وسم مثل hdmi أو كلمة)"));
        list.add(tool("get_log", "أسطر أكثر من سجلّ التطبيق المحفوظ في الذاكرة (منذ فتح التطبيق، حتى 500 سطر). "
                + "استعملها حين لا تكفي الأسطر المرفقة أو تريد وسماً بعينه.", logProps, "lines"));

        list.add(tool("get_logcat", "آخر تحذيرات وأخطاء أندرويد لعملية التطبيق نفسها (logcat بمستوى W فأعلى). "
                + "تنفع في أعطال النظام: انهيار، TvView، الصوت، الأذونات.", Collections.<String, Object>emptyMap()));

        list.add(tool("check_network", "يفحص اتصال الجهاز الآن: الإنترنت العام، وخادم التطبيق amjd.law، ومستودع "
                + "التحديثات على GitHub. يعيد رمز HTTP والزمن بالمللي ثانية لكل واحد.",
                Collections.<String, Object>emptyMap()));

        list.add(tool("check_subscription", "يسأل سيرفر الاشتراك المفتوح الآن عن حالة الحساب (طلب معلومات فقط، "
                + "لا يفتح بثاً ولا يحجز اتصالاً). يعيد: حالة الحساب، تاريخ الانتهاء، الاتصالات النشطة والحد "
                + "الأقصى، ورمز HTTP والزمن. استعملها حين يشكو المستخدم من قنوات لا تفتح أو قوائم لا تُحمَّل.",
                Collections.<String, Object>emptyMap()));

        Map<String, Object> actEnum = prop("string", "الإصلاح المطلوب");
        actEnum.put("enum", FIXES);
        Map<String, Object> fixProps = new HashMap<>();
        fixProps.put("action", actEnum);
        fixProps.put("reason", prop("string", "لماذا يعالج هذا الإصلاح السبب، في جملة تُعرض للمستخدم"));
        list.add(tool("apply_fix", "يطبّق إصلاحاً واحداً على إعدادات التطبيق ويعيد النتيجة. "
                + "hdmi_window_off يطفئ نافذة HDMI المصغّرة؛ hdmi_sound_off/on يكتم صوتها أو يسمعه؛ "
                + "float_channel_off يغلق نافذة القناة العائمة ويحرر اتصال الاشتراك الذي تحجزه؛ "
                + "player_live_* وplayer_movie_* تختار مشغّل القنوات أو الأفلام (Player+ أو VLC أو MX)؛ "
                + "page_size_* عدد البطاقات في الدفعة (100 للأجهزة ضعيفة الذاكرة)؛ clear_cache يمسح قوائم "
                + "البحث المخزّنة في الذاكرة؛ reload_ui يعيد تحميل الواجهة؛ check_updates يفحص التحديثات.",
                fixProps, "action", "reason"));

        Map<String, Object> noteProps = new HashMap<>();
        noteProps.put("note", prop("string", "الدرس في سطر واحد، حتى 300 حرف"));
        list.add(tool("remember", "يحفظ درساً يُرفق بكل فحص قادم على هذا الجهاز.", noteProps, "note"));

        if (control) {
            Map<String, Object> tvEnum = prop("string", "الأمر");
            tvEnum.put("enum", TV_ACTIONS);
            Map<String, Object> tvProps = new HashMap<>();
            tvProps.put("action", tvEnum);
            tvProps.put("value", prop("string", "قيمة الأمر إن احتاجها، وإلا نص فارغ"));
            list.add(tool("tv_control", "يتحكم بالتلفاز بما يملكه التطبيق من صلاحيات، حين يطلب أبو فيصل ذلك. "
                    + "الصوت: volume_up، volume_down، volume_set (value من 0 إلى 100)، mute، unmute. "
                    + "الوسائط للتطبيق الذي يشغّل الصوت الآن: media_play_pause، media_next، media_previous. "
                    + "التنقل: home للشاشة الرئيسية للتلفاز، back (يتطلب خدمة الإتاحة). "
                    + "التطبيقات: list_apps يعدّد المثبّت باسمه وحزمته، launch_app يفتح تطبيقاً (value اسم الحزمة من "
                    + "list_apps). المداخل: list_inputs يعدّد مداخل HDMI بمعرّفاتها، open_input يحوّل التلفاز كله "
                    + "إلى مدخل بملء الشاشة (value معرّف المدخل). نافذة HDMI المصغّرة: window_on (value معرّف مدخل "
                    + "أو فارغ لآخر مدخل)، window_off، window_corner (value: 0 أعلى اليمين، 1 أعلى اليسار، 2 أسفل "
                    + "اليمين، 3 أسفل اليسار)، window_size (value: 25 أو 33 أو 50). داخل myTv+: open_screen "
                    + "(value: live للقنوات، movies، series، home، settings). يعيد نتيجة الأمر.",
                    tvProps, "action", "value"));
        }
        return list;
    }

    private static JSONObject toJson(JsonValue v) {
        try {
            Map<?, ?> m = v.convert(Map.class);
            return m == null ? new JSONObject() : new JSONObject(m);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String runTool(String name, JSONObject in, JSONArray actions, List<String> secrets) throws Exception {
        switch (name) {
            case "get_log":
                step = "يقرأ السجلّ…";
                MainActivity.notifyAi();
                return RemoteLog.recent(Math.max(1, Math.min(500, in.optInt("lines", 200))), in.optString("contains", ""));
            case "get_logcat":
                step = "يقرأ سجلّ النظام…";
                MainActivity.notifyAi();
                return logcat();
            case "check_network":
                step = "يفحص الشبكة…";
                MainActivity.notifyAi();
                return "الإنترنت العام: " + probe("https://www.gstatic.com/generate_204")
                        + "\nخادم التطبيق: " + probe("https://amjd.law/apps/ipv/")
                        + "\nمستودع التحديثات: " + probe("https://github.com/almobarakamjd/iptvmtc/releases/latest")
                        + "\n(أي رمز HTTP، ومنه 403 و302، يعني أن الخادم بُلِغ والاتصال سليم؛ العطل هو «فشل» فقط)";
            case "check_subscription":
                step = "يفحص الاشتراك…";
                MainActivity.notifyAi();
                return checkSubscription();
            case "apply_fix": {
                String action = in.optString("action", "");
                if (!FIXES.contains(action)) return "إصلاح غير معروف: " + action;
                step = "يطبّق إصلاحاً…";
                MainActivity.notifyAi();
                String res = MainActivity.callJs("AiTools.fix(" + JSONObject.quote(action) + ")");
                JSONObject a = new JSONObject();
                a.put("action", action);
                a.put("reason", in.optString("reason", ""));
                a.put("result", res);
                actions.put(a);
                return res;
            }
            case "remember": {
                String note = cut(redact(in.optString("note", "").replace('\n', ' ').trim(), secrets), 300);
                if (note.length() == 0) return "الدرس فارغ";
                JSONArray old = new JSONArray(prefs.getString("notes", "[]"));
                JSONArray notes = new JSONArray();
                for (int i = Math.max(0, old.length() - (MAX_NOTES - 1)); i < old.length(); i++) notes.put(old.get(i));
                notes.put(note);
                prefs.edit().putString("notes", notes.toString()).apply();
                return "حُفظ";
            }
            case "tv_control": {
                String action = in.optString("action", "");
                if (!TV_ACTIONS.contains(action)) return "أمر غير معروف: " + action;
                step = "ينفّذ أمراً على التلفاز…";
                MainActivity.notifyAi();
                String res = tvControl(action, in.optString("value", ""));
                if (!action.startsWith("list_")) {
                    JSONObject a = new JSONObject();
                    a.put("action", "tv:" + action);
                    a.put("reason", in.optString("value", ""));
                    a.put("result", cut(res, 200));
                    actions.put(a);
                }
                return res;
            }
            default:
                return "أداة غير معروفة: " + name;
        }
    }

    /* ---------- التحكم بالتلفاز ---------- */

    private static String tvControl(String action, String value) throws Exception {
        android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        final int music = android.media.AudioManager.STREAM_MUSIC, ui = android.media.AudioManager.FLAG_SHOW_UI;
        switch (action) {
            case "volume_up": am.adjustStreamVolume(music, android.media.AudioManager.ADJUST_RAISE, ui); return volume(am);
            case "volume_down": am.adjustStreamVolume(music, android.media.AudioManager.ADJUST_LOWER, ui); return volume(am);
            case "mute": am.adjustStreamVolume(music, android.media.AudioManager.ADJUST_MUTE, ui); return volume(am);
            case "unmute": am.adjustStreamVolume(music, android.media.AudioManager.ADJUST_UNMUTE, ui); return volume(am);
            case "volume_set": {
                int pct = Math.max(0, Math.min(100, Integer.parseInt(value.trim())));
                am.setStreamVolume(music, Math.round(am.getStreamMaxVolume(music) * pct / 100f), ui);
                return volume(am);
            }
            case "media_play_pause": return mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case "media_next": return mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
            case "media_previous": return mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case "home":
                return startOutside(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "فُتحت الشاشة الرئيسية للتلفاز");
            case "back": {
                RemoteKeyAccessibilityService svc = RemoteKeyAccessibilityService.instance;
                if (svc == null) return "لم يُنفَّذ: زر الرجوع يتطلب تفعيل خدمة myTv+ في «الإتاحة» من إعدادات التلفاز";
                return svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        ? "نُفّذ الرجوع" : "رفض النظام الرجوع";
            }
            case "list_apps": return listApps();
            case "launch_app": {
                android.content.pm.PackageManager pm = app.getPackageManager();
                Intent in = pm.getLeanbackLaunchIntentForPackage(value.trim());
                if (in == null) in = pm.getLaunchIntentForPackage(value.trim());
                if (in == null) return "لم يُفتح: لا تطبيق بهذه الحزمة (" + value + ")؛ استعمل list_apps";
                return startOutside(in, "فُتح التطبيق " + value);
            }
            case "list_inputs": return HdmiWindow.get(app).inputsJson();
            case "open_input":
                if (value.trim().length() == 0 || HdmiWindow.DEMO_ID.equals(value.trim())) return "لم يُنفَّذ: يلزم معرّف مدخل حقيقي من list_inputs";
                return startOutside(new Intent(Intent.ACTION_VIEW,
                        android.media.tv.TvContract.buildChannelUriForPassthroughInput(value.trim())), "حُوِّل التلفاز إلى المدخل " + value);
            default:   // النافذة المصغّرة وشاشات myTv+ في الواجهة
                return MainActivity.callJs("AiTools.tv(" + JSONObject.quote(action) + "," + JSONObject.quote(value) + ")");
        }
    }

    private static String volume(android.media.AudioManager am) {
        final int music = android.media.AudioManager.STREAM_MUSIC;
        return "الصوت الآن " + am.getStreamVolume(music) + " من " + am.getStreamMaxVolume(music)
                + (am.isStreamMute(music) ? " (مكتوم)" : "")
                + (am.isVolumeFixed() ? ". تنبيه: صوت هذا الجهاز ثابت من النظام فقد لا يتغير من التطبيق" : "");
    }

    private static String mediaKey(android.media.AudioManager am, int code) {
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0));
        return "أُرسل الأمر إلى التطبيق الذي يشغّل الوسائط الآن";
    }

    /** فتح نشاط خارج myTv+: مغادرة منا لا انتزاع من التلفاز */
    private static String startOutside(Intent in, String ok) {
        try {
            HdmiWindow.selfLeaveAt = SystemClock.elapsedRealtime();
            RemoteKeyAccessibilityService svc = RemoteKeyAccessibilityService.instance;
            (svc != null ? svc : app).startActivity(in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return ok;
        } catch (Exception e) {
            return "لم يُنفَّذ: " + e;
        }
    }

    private static String listApps() {
        android.content.pm.PackageManager pm = app.getPackageManager();
        Map<String, String> found = new java.util.TreeMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                found.put(ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm)));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : found.entrySet()) sb.append(e.getValue()).append(" = ").append(e.getKey()).append('\n');
        return sb.length() > 0 ? sb.toString() : "(لم يُعثر على تطبيقات)";
    }

    private static String notesText() {
        try {
            JSONArray n = new JSONArray(prefs.getString("notes", "[]"));
            if (n.length() == 0) return "(لا شيء بعد)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n.length(); i++) sb.append("- ").append(n.getString(i)).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "(لا شيء بعد)";
        }
    }

    private static String memory() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return (mi.availMem >> 20) + " من " + (mi.totalMem >> 20) + " ميغابايت متاحة" + (mi.lowMemory ? " (منخفضة)" : "");
        } catch (Exception e) {
            return "?";
        }
    }

    private static String logcat() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "600", "-v", "time", "*:W"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("RemoteLog") || line.contains("WebConsole")) continue;
                lines.add(line);
            }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.size() - 150); i < lines.size(); i++) sb.append(cut(lines.get(i), 400)).append('\n');
            return sb.length() > 0 ? sb.toString() : "(لا تحذيرات ولا أخطاء)";
        } catch (Exception e) {
            return "تعذرت قراءة logcat: " + e;
        }
    }

    private static String probe(String url) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            c.disconnect();
            return "HTTP " + code + " في " + (SystemClock.elapsedRealtime() - t0) + " مللي ثانية";
        } catch (Exception e) {
            return "فشل بعد " + (SystemClock.elapsedRealtime() - t0) + " مللي ثانية: " + e;
        }
    }

    /** معلومات الحساب من player_api.php للاشتراك المفتوح. بيانات الدخول لا تصل النموذج. */
    private static String checkSubscription() throws Exception {
        JSONObject cr = new JSONObject(MainActivity.callJs("AiTools.creds()"));
        if (cr.has("refuse")) return cr.getString("refuse");
        String base = cr.getString("host").replaceAll("/+$", "");
        long t0 = SystemClock.elapsedRealtime();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/player_api.php?username="
                    + java.net.URLEncoder.encode(cr.getString("username"), "UTF-8") + "&password="
                    + java.net.URLEncoder.encode(cr.getString("password"), "UTF-8")).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            long ms = SystemClock.elapsedRealtime() - t0;
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder body = new StringBuilder();
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0 && body.length() < 20000) body.append(buf, 0, n);
                r.close();
            }
            c.disconnect();
            String head = "الاشتراك «" + cr.optString("name") + "»: HTTP " + code + " في " + ms + " مللي ثانية\n";
            try {
                JSONObject u = new JSONObject(body.toString()).getJSONObject("user_info");
                long exp = u.optLong("exp_date", 0);
                return head + "auth=" + u.opt("auth") + " status=" + u.opt("status")
                        + " active_cons=" + u.opt("active_cons") + " max_connections=" + u.opt("max_connections")
                        + " is_trial=" + u.opt("is_trial") + " ينتهي=" + (exp > 0 ? new java.text.SimpleDateFormat(
                        "yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(exp * 1000)) : "غير محدد");
            } catch (Exception e) {
                return head + "الرد ليس معلومات حساب. أوله: " + cut(body.toString(), 200);
            }
        } catch (Exception e) {
            return "الاشتراك «" + cr.optString("name") + "»: فشل الاتصال بعد "
                    + (SystemClock.elapsedRealtime() - t0) + " مللي ثانية: " + e;
        }
    }

    /* ---------- حجب بيانات الدخول عن النموذج ---------- */

    private static List<String> secrets() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(MainActivity.callJs("AiTools.secrets()"));
            for (int i = 0; i < a.length(); i++) if (a.getString(i).length() >= 4) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    private static String redact(String s, List<String> secrets) {
        if (s == null) return "";
        for (String x : secrets) s = s.replace(x, "•••");
        return s;
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }

    private static String friendly(AnthropicServiceException e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
        int code = e.statusCode();
        if (m.contains("credit balance")) return "نفد رصيد مفتاح الذكاء الاصطناعي — يلزم شحن الرصيد";
        if (code == 401 || code == 403) return "مفتاح الذكاء الاصطناعي غير صالح";
        if (code == 429) return "طلبات كثيرة على الذكاء الاصطناعي — حاول بعد قليل";
        if (code == 529 || code >= 500) return "خدمة الذكاء الاصطناعي مشغولة الآن — حاول بعد قليل";
        return "خطأ من خدمة الذكاء الاصطناعي (" + code + ")";
    }
}
