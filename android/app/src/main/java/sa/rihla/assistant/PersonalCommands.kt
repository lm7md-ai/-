package sa.rihla.assistant

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import org.json.JSONArray
import org.json.JSONObject

const val PERSONAL_MODEL_MAX_BYTES = 32 * 1024 * 1024

data class AppIntent(
    val id: String,
    val title: String,
    val action: String,
    val value: String = "",
    val examples: List<String>,
)

data class CommandExample(val text: String, val intent: String)

data class CommandPrediction(val intent: AppIntent, val confidence: Double)

data class AssistantProfile(
    val name: String = "مساعد مشواري",
    val instructions: String = "",
    val notes: String = "",
    val speak: Boolean = true,
    val networkVoice: Boolean = false,
)

object AppCommands {
    val all =
        listOf(
            // Original 26 intents (preserved verbatim for 100% backward compatibility)
            AppIntent(
                "tracking_health",
                "فتح مركز التتبع الذكي",
                "navigate",
                "tracking_health",
                listOf("افحص التتبع", "افتح مركز التتبع", "جودة تتبع المشوار", "فحص إشارة الموقع"),
            ),
            AppIntent(
                "spent_today",
                "مصروفات اليوم",
                "summary",
                "expenses_today",
                listOf("كم صرفت اليوم", "مصروفات اليوم", "كم مصاريفي اليوم", "احسب مصروف اليوم"),
            ),
            AppIntent(
                "spent_month",
                "مصروفات الشهر",
                "summary",
                "expenses_month",
                listOf("كم صرفت هذا الشهر", "مصروفات الشهر", "احسب مصاريف الشهر"),
            ),
            AppIntent(
                "trips_today",
                "ملخص مشاوير اليوم",
                "summary",
                "trips_today",
                listOf("ملخص مشاوير اليوم", "كم مشوار اليوم", "مسافة مشاويري اليوم"),
            ),
            AppIntent(
                "home",
                "فتح الرئيسية",
                "navigate",
                "dashboard",
                listOf("الرئيسية", "افتح الرئيسية", "ارجع للرئيسية", "روح الصفحة الرئيسية"),
            ),
            AppIntent(
                "trips",
                "عرض المشاوير",
                "navigate",
                "history",
                listOf("افتح المشاوير", "عرض رحلاتي", "سجل المشاوير", "وين مشاويري"),
            ),
            AppIntent(
                "map",
                "فتح الخريطة",
                "navigate",
                "map",
                listOf(
                    "الخريطة",
                    "افتح الخريطة",
                    "ورني الخريطة",
                    "عرض خريطة السعودية",
                    "روح للخريطة",
                ),
            ),
            AppIntent(
                "audio",
                "فتح الصوتيات",
                "navigate",
                "audio",
                listOf("افتح الصوتيات", "مكتبة الصوتيات", "روح للمكتبة", "عرض المحاضرات"),
            ),
            AppIntent(
                "models",
                "فتح نماذج الذكاء الاصطناعي",
                "navigate",
                "models",
                listOf(
                    "افتح النماذج",
                    "نماذج الذكاء الاصطناعي",
                    "تنزيل نموذج",
                    "وين النماذج",
                    "تحميل الذكاء الاصطناعي",
                ),
            ),
            AppIntent(
                "settings",
                "فتح الإعدادات",
                "navigate",
                "settings",
                listOf("الإعدادات", "افتح الاعدادات", "إعدادات التطبيق", "المزيد"),
            ),
            AppIntent(
                "expenses",
                "عرض المصروفات",
                "navigate",
                "expenses",
                listOf("افتح المصروفات", "ورني المصاريف", "عرض عمليات الشراء", "مصروفاتي"),
            ),
            AppIntent(
                "stats",
                "فتح الإحصاءات",
                "navigate",
                "statistics",
                listOf("افتح الاحصاءات", "احصائيات المشاوير", "عرض الاحصائيات", "تقرير الرحلات"),
            ),
            AppIntent(
                "calendar",
                "فتح التقويم",
                "navigate",
                "calendar",
                listOf("افتح التقويم", "التقويم", "ورني تقويم المشاوير"),
            ),
            AppIntent(
                "vehicle",
                "عرض السيارة",
                "navigate",
                "vehicle",
                listOf("افتح السيارة", "سيارتي", "بيانات السيارة", "معلومات السيارة"),
            ),
            AppIntent(
                "studio",
                "فتح استوديو المحاضرات",
                "navigate",
                "studio",
                listOf(
                    "افتح الاستوديو",
                    "استوديو المحاضرات",
                    "تحسين صوت المحاضرات",
                    "فصل الموسيقى",
                    "تنظيم المحاضرات",
                ),
            ),
            AppIntent(
                "profile",
                "تخصيص مساعدي",
                "navigate",
                "assistant_profile",
                listOf("تخصيص مساعدي", "درب النموذج", "تدريب نموذجي", "اسمي الخاص للمساعد"),
            ),
            AppIntent(
                "pause",
                "إيقاف الصوت مؤقتًا",
                "pause_audio",
                examples = listOf("وقف الصوت", "ايقاف مؤقت", "وقف المقطع", "اسكت", "وقف التشغيل"),
            ),
            AppIntent(
                "resume",
                "متابعة التشغيل",
                "resume_audio",
                examples =
                    listOf(
                        "كمل التشغيل",
                        "شغل الصوت",
                        "تابع المقطع",
                        "كمل المحاضرة",
                        "شغل",
                        "تابع التشغيل",
                    ),
            ),
            AppIntent(
                "next",
                "المقطع التالي",
                "next_audio",
                examples =
                    listOf(
                        "المقطع التالي",
                        "التالي",
                        "غير المقطع",
                        "شغل اللي بعده",
                        "انتقل للتالي",
                    ),
            ),
            AppIntent(
                "previous",
                "المقطع السابق",
                "previous_audio",
                examples =
                    listOf("المقطع السابق", "السابق", "ارجع للمقطع اللي قبله", "شغل اللي قبله"),
            ),
            AppIntent(
                "favorite",
                "إضافة المقطع الحالي للمفضلة",
                "favorite_current",
                examples =
                    listOf("اضف للمفضلة", "احفظ المقطع في المفضلة", "حط المحاضرة في المفضلة"),
            ),
            AppIntent(
                "natural",
                "الصوت الطبيعي",
                "scene",
                "natural",
                listOf("الصوت الطبيعي", "رجع الصوت طبيعي", "بدون مؤثرات"),
            ),
            AppIntent(
                "soft",
                "تخفيف حدة المحاضرة",
                "scene",
                "lecture_soft",
                listOf("خفف حدة الصوت", "خفف الصوت الحاد", "تخفيف الحدة", "الصوت حاد"),
            ),
            AppIntent(
                "clear",
                "وضوح الكلام",
                "scene",
                "lecture_clear",
                listOf("وضح الكلام", "وضوح الكلام", "حسن وضوح المحاضرة", "الصوت مو واضح"),
            ),
            AppIntent(
                "3d",
                "صوت ثلاثي الأبعاد",
                "scene",
                "3d",
                listOf("صوت ثلاثي الابعاد", "شغل ثري دي", "وضع ثلاثة دي", "وضع 3d"),
            ),
            AppIntent(
                "7",
                "محاكاة صوت 7.1",
                "scene",
                "7.1",
                listOf("صوت سبعة واحد", "وضع سبعة فاصلة واحد", "شغل 7.1"),
            ),
            // v7.0 Personal Power-User Expansion (+18 new commands -> 44 total built-in commands!)
            AppIntent(
                "cockpit",
                "وضع قيادة اللانسر Cockpit",
                "navigate",
                "cockpit",
                listOf("وضع القيادة", "شاشة الكوكبيت", "عداد اللانسر", "لوحة القيادة الليلية"),
            ),
            AppIntent(
                "daily_briefing",
                "قراءة موجز اليوم الصوتي",
                "daily_briefing",
                "",
                listOf("اقرأ موجز اليوم", "سمعني الموجز الصوتي", "موجز الصباح", "ملخص يومي صوتي"),
            ),
            AppIntent(
                "where_is_my_car",
                "إرشاد إلى موقف سيارتي",
                "where_is_my_car",
                "",
                listOf("وين وقفت سيارتي", "ارشدني لسيارتي", "موقع موقف سيارتي", "بوصله موقف السياره"),
            ),
            AppIntent(
                "save_parking",
                "حفظ موقف سيارتي الآن",
                "save_parking",
                "",
                listOf("احفظ موقف سيارتي", "ثبت موقف اللانسر", "سجل مكان الوقوف هنا"),
            ),
            AppIntent(
                "smart_queue",
                "تشغيل قائمة على قد مشواري",
                "smart_queue",
                "",
                listOf("قائمة على قد مشواري", "رتب قائمة على وقت المشوار", "شغل قائمة ذكية للمشوار"),
            ),
            AppIntent(
                "bookmark_here",
                "حفظ علامة صوتية فورية",
                "bookmark_here",
                "",
                listOf("احفظ علامة صوتية هنا", "ضع اشارة مرجعية بالمحاضرة", "علم هذه الدقيقة بالصوت"),
            ),
            AppIntent(
                "next_lecture",
                "موعد المحاضرة القادمة والانطلاق",
                "summary",
                "next_lecture",
                listOf("متى محاضرتي الجاية", "موعد المحاضرة القادمة", "متى اطلع للجامعة اليوم"),
            ),
            AppIntent(
                "uqu_schedule",
                "فتح جدول محاضرات أم القرى",
                "navigate",
                "temporary_places",
                listOf("جدول محاضرات ام القرى", "افتح جدول الكلاسات", "مواعيد محاضرات الجامعة"),
            ),
            AppIntent(
                "remaining_balance",
                "الرصيد الفعلي المتبقي من الحوالة",
                "summary",
                "remaining_balance",
                listOf("كم باقي من الحوالة", "الرصيد الفعلي المتبقي", "كم بقي من رصيد الوالد"),
            ),
            AppIntent(
                "university_day_cost",
                "متوسط تكلفة يوم الجامعة",
                "summary",
                "university_day_cost",
                listOf("كم تكلفة يوم الجامعة", "متوسط صرف يوم الدوام", "تكلفة دوام العابدية اليومي"),
            ),
            AppIntent(
                "delete_last_expense",
                "حذف آخر مصروف مسجل",
                "delete_last_expense",
                "",
                listOf("احذف اخر مصروف", "الغ اخر عملية شراء", "امسح المصروف الاخير"),
            ),
            AppIntent(
                "unified_backup",
                "إنشاء نسخة احتياطية موحدة",
                "unified_backup",
                "",
                listOf("اصنع نسخة احتياطية موحدة", "احفظ نسخة شاملة الان", "تصدير النسخة الموحدة"),
            ),
            AppIntent(
                "daily_story",
                "عرض قصة اليوم الزمنية",
                "navigate",
                "calendar",
                listOf("اعرض قصة اليوم", "الخط الزمني ليومي", "تسلسل احداث اليوم"),
            ),
            AppIntent(
                "bottleneck_map",
                "خريطة الاختناقات المرورية",
                "navigate",
                "map",
                listOf("خريطة الاختناقات المرورية", "اماكن الزحمة المتكررة", "بؤر الازدحام في طريقي"),
            ),
            AppIntent(
                "transcript_search",
                "فهرسة وبحث نص المحاضرات",
                "navigate",
                "studio",
                listOf("فهرسة نص المحاضرة", "ابحث داخل كلام المحاضرات", "تحويل المحاضرة الى نص مكتوب"),
            ),
            AppIntent(
                "adaptive_boost_toggle",
                "تفعيل تعزيز الصوت مع السرعة",
                "adaptive_boost",
                "on",
                listOf("فعل تعزيز الصوت مع السرعة", "تشغيل الصوت التكيفي بالخط", "ارفع وضوح المحاضرة مع السرعة"),
            ),
            AppIntent(
                "rewind_15",
                "إرجاع المحاضرة 15 ثانية",
                "rewind_15",
                "",
                listOf("رجع خمسطعش ثانية", "ارجع بالمحاضرة خمسة عشر ثانية", "عيد اخر فقرة صوتية"),
            ),
            AppIntent(
                "father_transfers_page",
                "فتح أرشيف حوالات الوالد",
                "navigate",
                "father_transfers",
                listOf("ارشيف حوالات الوالد", "سجل التحويلات الواردة", "صفحة حوالات ابوي"),
            ),
        )

    fun normalized(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace(Regex("[أإآ]"), "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(Regex("[^\\p{L}\\p{N}. ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(text: String) = normalized(text).split(' ').filter { it.isNotBlank() }.toSet()

    fun features(text: String): Set<String> = buildSet {
        for (word in tokens(text)) {
            add("w:$word")
            val padded = "^$word$"
            for (n in 2..3) for (i in 0..padded.length - n) add("g:" + padded.substring(i, i + n))
        }
    }
}

/** Multinomial Naive Bayes with word/character features and an out-of-vocabulary rejection gate. */
class PersonalCommandModel(examples: List<CommandExample>) {
    private val classes = AppCommands.all.associateBy { it.id }
    private val training =
        AppCommands.all.flatMap { intent ->
            intent.examples.map { CommandExample(it, intent.id) }
        } + examples
    private val exact = training.associate { AppCommands.normalized(it.text) to it.intent }
    private val words = training.flatMap { AppCommands.tokens(it.text) }.toSet()
    private val counts = classes.keys.associateWith { mutableMapOf<String, Int>() }
    private val vocabulary = mutableSetOf<String>()
    val exampleCount
        get() = training.size

    init {
        for (row in training) {
            require(row.intent in classes)
            for (feature in AppCommands.features(row.text)) {
                vocabulary += feature
                counts.getValue(row.intent).merge(feature, 1, Int::plus)
            }
        }
    }

    private val totals = counts.mapValues { it.value.values.sum() }

    fun predict(text: String): CommandPrediction? {
        exact[AppCommands.normalized(text)]?.let {
            return CommandPrediction(classes.getValue(it), 1.0)
        }
        val cleaned =
            AppCommands.normalized(text)
                .split(' ')
                .filterNot {
                    it in
                        setOf(
                            "يا",
                            "مساعد",
                            "مساعدي",
                            "مشواري",
                            "من",
                            "فضلك",
                            "لو",
                            "سمحت",
                            "ممكن",
                            "لي",
                            "ابي",
                            "ابغي",
                            "اريد",
                            "please",
                        )
                }
                .map { if (it == "تفتح") "افتح" else it }
                .joinToString(" ")
        val clean = AppCommands.normalized(cleaned)
        if (clean.isBlank() || clean.length > 300) return null
        exact[clean]?.let {
            return CommandPrediction(classes.getValue(it), 1.0)
        }
        val tokens = AppCommands.tokens(cleaned)
        if (tokens.count { it in words }.toDouble() / tokens.size.coerceAtLeast(1) < .65)
            return null
        val features = AppCommands.features(cleaned).filter { it in vocabulary }
        if (features.isEmpty()) return null
        val scores =
            counts
                .map { (id, row) ->
                    id to
                        features.sumOf {
                            ln(((row[it] ?: 0) + .5) / (totals.getValue(id) + vocabulary.size * .5))
                        }
                }
                .sortedByDescending { it.second }
        val max = scores.first().second
        val probabilities = scores.map { exp((it.second - max) / 2.0) }
        val confidence = probabilities.first() / probabilities.sum()
        if (confidence < .78 || scores.first().second - scores[1].second < 2.0) return null
        return CommandPrediction(classes.getValue(scores.first().first), confidence)
    }

    fun weights(): JSONObject =
        JSONObject()
            .put("algorithm", "multinomial-naive-bayes-word-char-v1")
            .put("examples", exampleCount)
            .put("vocabulary", vocabulary.size)
            .put("counts", JSONObject(counts.mapValues { JSONObject(it.value as Map<*, *>) }))
}

class PersonalAssistantStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("personal_assistant", Context.MODE_PRIVATE)

    fun profile() =
        AssistantProfile(
            prefs.getString("name", "مساعد مشواري").orEmpty(),
            prefs.getString("instructions", "").orEmpty(),
            prefs.getString("notes", "").orEmpty(),
            prefs.getBoolean("speak", true),
            prefs.getBoolean("network_voice", false),
        )

    fun save(profile: AssistantProfile) {
        require(
            profile.name.trim().length in 1..50 &&
                profile.instructions.length <= 600 &&
                profile.notes.length <= 1000
        )
        prefs
            .edit()
            .putString("name", profile.name.trim())
            .putString("instructions", profile.instructions)
            .putString("notes", profile.notes)
            .putBoolean("speak", profile.speak)
            .putBoolean("network_voice", profile.networkVoice)
            .apply()
    }

    fun examples(): List<CommandExample> {
        val rows = JSONArray(prefs.getString("examples", "[]"))
        return (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            CommandExample(row.getString("text"), row.getString("intent"))
        }
    }

    fun train(rows: List<CommandExample>) {
        require(
            rows.size <= 2000 &&
                rows.all {
                    it.text.trim().length in 2..160 &&
                        AppCommands.normalized(it.text).length >= 2 &&
                        AppCommands.all.any { intent -> intent.id == it.intent }
                }
        )
        val clean = rows.distinctBy { AppCommands.normalized(it.text) }
        val model = PersonalCommandModel(clean)
        val file = AtomicFile(File(context.filesDir, "personal-command-model.json"))
        val output = file.startWrite()
        try {
            output.write(model.weights().toString().toByteArray())
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        prefs
            .edit()
            .putString(
                "examples",
                JSONArray(
                        clean.map {
                            JSONObject().put("text", it.text.trim()).put("intent", it.intent)
                        }
                    )
                    .toString(),
            )
            .putLong("trained", System.currentTimeMillis())
            .apply()
    }

    fun trainedAt() = prefs.getLong("trained", 0)

    fun model() = PersonalCommandModel(examples())

    fun export(): String {
        val profile = profile()
        return JSONObject()
            .put("format", "MASHWARI_PERSONAL_ASSISTANT")
            .put("version", 1)
            .put("name", profile.name)
            .put("instructions", profile.instructions)
            .put("notes", profile.notes)
            .put(
                "examples",
                JSONArray(
                    examples().map { JSONObject().put("text", it.text).put("intent", it.intent) }
                ),
            )
            .put("weights", model().weights())
            .toString()
    }

    fun restore(text: String) {
        require(text.toByteArray().size <= PERSONAL_MODEL_MAX_BYTES)
        val data = JSONObject(text)
        require(
            data.getString("format") == "MASHWARI_PERSONAL_ASSISTANT" && data.getInt("version") == 1
        )
        val profile =
            profile()
                .copy(
                    name = data.getString("name"),
                    instructions = data.getString("instructions"),
                    notes = data.getString("notes"),
                )
        require(
            profile.name.trim().length in 1..50 &&
                profile.instructions.length <= 600 &&
                profile.notes.length <= 1000
        )
        val values = data.getJSONArray("examples")
        val rows =
            (0 until values.length()).map {
                val row = values.getJSONObject(it)
                CommandExample(row.getString("text"), row.getString("intent"))
            }
        train(rows)
        save(profile)
    }
}
