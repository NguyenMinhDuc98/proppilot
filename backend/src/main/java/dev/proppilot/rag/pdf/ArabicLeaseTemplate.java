package dev.proppilot.rag.pdf;

import static dev.proppilot.rag.pdf.RootFormat.format;

import dev.proppilot.agent.MoneyText;
import dev.proppilot.rag.DocumentLanguage;
import dev.proppilot.rag.LeaseFacts;
import dev.proppilot.rag.LeaseTerms;
import dev.proppilot.rag.LeaseTerms.LateFeeKind;
import dev.proppilot.rag.LeaseTerms.RenewalMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The Arabic lease document. It is written as an Arabic contract reads, not translated word for word, and keeps ASCII
 * digits like the rest of the app. Building names and addresses stay in the Latin letters they are stored in.
 */
final class ArabicLeaseTemplate implements LeaseTemplate {

    private static final String[] MONTHS = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس",
            "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
    private static final String LANDLORD = "شركة بروبايلوت التجريبية للعقارات";

    @Override
    public String title(LeaseFacts facts) {
        return "عقد إيجار سكني – الوحدة " + facts.unitCode();
    }

    @Override
    public LeaseText write(LeaseFacts facts, LeaseTerms terms) {
        var clauses = new Clauses()
                .add("الإيجار وطريقة السداد", rent(facts))
                .add("مبلغ التأمين", deposit(facts, terms))
                .add("مدة العقد والتجديد", renewal(terms))
                .add("مدة الإشعار", notice(terms))
                .add("غرامة التأخير", lateFee(facts, terms))
                .add("الصيانة والإصلاحات", maintenance(terms))
                .add("الحيوانات الأليفة", pets(terms))
                .add("الإنهاء المبكر", earlyTermination(facts, terms))
                .add("التأجير من الباطن", subletting(terms))
                .add("استعمال العقار", use(facts))
                .add("المعاينة ودخول العقار", inspection(terms))
                .add("القانون الواجب التطبيق", governingLaw(facts));
        if (terms.houseRulesAnnex()) {
            clauses.add("قواعد السكن وأنظمة المبنى", houseRules());
        }
        if (terms.plantedInstruction()) {
            clauses.add("شروط خاصة", specialConditions());
        }
        return new LeaseText(DocumentLanguage.AR, title(facts), "رقم العقد: " + facts.documentCode(),
                preamble(facts), clauses.build(), "البند %d: %s", "التوقيعات", signatures(facts), "صفحة %d من %d");
    }

    private static List<LeaseText.Section> preamble(LeaseFacts f) {
        return List.of(
                new LeaseText.Section("الأطراف", List.of(
                        format("المؤجر: %s، وتمثلها إدارة العقار (ويشار إليها فيما يلي باسم \"المؤجر\").", LANDLORD),
                        format("المستأجر: %s (ويشار إليه فيما يلي باسم \"المستأجر\").", f.tenantName()))),
                new LeaseText.Section("العقار المؤجر", List.of(
                        format("الوحدة %s في الدور %d، وهي شقة مكونة من %s بمساحة %d متر مربع، في مبنى %s بمدينة %s "
                                + "(ويشار إليها فيما يلي باسم \"العقار\").",
                                f.unitCode(), f.floor(), bedrooms(f.bedrooms()), f.areaSqm(),
                                        f.buildingName(), f.city()),
                        format("العنوان: %s.", f.address()))),
                new LeaseText.Section("مدة العقد والإيجار", List.of(
                        format("تبدأ مدة العقد في %s وتنتهي في %s (ويشار إليها فيما يلي باسم \"المدة\").",
                                date(f.startDate()), date(f.endDate())),
                        format("الإيجار الشهري %s ريال سعودي، يدفع مقدما في اليوم الأول من كل شهر.",
                                MoneyText.of(f.monthlyRent())),
                        "اتفق الطرفان على ما يلي.")));
    }

    private static List<String> rent(LeaseFacts f) {
        return List.of(
                format("يلتزم المستأجر بدفع الإيجار الشهري البالغ %s ريال سعودي مقدما، في موعد أقصاه اليوم الأول من كل "
                        + "شهر، بالتحويل البنكي إلى الحساب الذي يحدده المؤجر كتابة.",
                        MoneyText.of(f.monthlyRent())),
                "يعد السداد قد تم في اليوم الذي يصل فيه المبلغ إلى حساب المؤجر. ويبقى الإيجار ثابتا طوال المدة، "
                        + "إلا في الحدود التي يسمح بها بند المدة والتجديد.");
    }

    private static List<String> deposit(LeaseFacts f, LeaseTerms t) {
        return List.of(
                format("يدفع المستأجر عند التوقيع مبلغ تأمين يعادل إيجار %s، وقدره %s ريال سعودي.",
                        months(t.securityDepositMonths()), amount(f, t.securityDepositMonths())),
                "يحتفظ المؤجر بمبلغ التأمين ضمانا لالتزامات المستأجر دون أن يدفع عنه أي فوائد، ويرده إلى المستأجر "
                        + "خلال 30 يوما من تسليم العقار، بعد خصم الإيجار غير المسدد وتكلفة الأضرار التي تتجاوز "
                        + "الاستعمال المعتاد.");
    }

    private static List<String> renewal(LeaseTerms t) {
        var renewal = t.renewal();
        if (renewal.mode() == RenewalMode.AUTOMATIC_12_MONTHS) {
            return List.of(format("يسري العقد للمدة المبينة في صدر هذا العقد. وما لم يخطر أحد الطرفين الآخر كتابة بعدم "
                    + "رغبته في التجديد قبل نهاية المدة بمدة لا تقل عن %d يوما، يتجدد العقد تلقائيا لمدة 12 شهرا "
                    + "أخرى. ولا يجوز أن تزيد زيادة الإيجار عند التجديد على %d بالمائة من الإيجار السابق.",
                    t.noticePeriodDays(), renewal.rentIncreaseCapPercent()));
        }
        return List.of(format("يسري العقد للمدة المبينة في صدر هذا العقد، ولا يتجدد تلقائيا. ويتطلب التجديد اتفاقا "
                + "كتابيا جديدا، على أن يقدم المؤجر عرض التجديد قبل نهاية المدة بمدة لا تقل عن %d يوما. ولا يجوز أن "
                + "يزيد الإيجار المعروض للتجديد على الإيجار الحالي بأكثر من %d بالمائة.",
                t.noticePeriodDays(), renewal.rentIncreaseCapPercent()));
    }

    private static List<String> notice(LeaseTerms t) {
        return List.of(format("على الطرف الذي يرغب في إنهاء العقد عند نهاية المدة، أو في اقتراح تعديل شروطه، أن يخطر "
                + "الطرف الآخر كتابة قبل %d يوما على الأقل. ويتم الإشعار بخطاب مسجل يسلم في العقار أو برسالة "
                + "بريد إلكتروني إلى العنوان الذي حدده الطرف المستلم كتابة، ويسري من يوم استلامه.",
                t.noticePeriodDays()));
    }

    private static List<String> lateFee(LeaseFacts f, LeaseTerms t) {
        var fee = t.lateFee();
        String first;
        if (fee.kind() == LateFeeKind.PERCENT_OF_RENT_PER_MONTH) {
            var sar = MoneyText.of(f.monthlyRent().multiply(BigDecimal.valueOf(fee.amount())).movePointLeft(2));
            first = format("إذا لم يصل الإيجار إلى المؤجر خلال %d أيام من تاريخ استحقاقه، تستحق غرامة تأخير قدرها %d "
                    + "بالمائة من الإيجار الشهري، أي %s ريال سعودي، عن كل شهر أو جزء من شهر يتأخر فيه السداد.",
                    fee.graceDays(), fee.amount(), sar);
        } else {
            first = format("إذا لم يصل الإيجار إلى المؤجر خلال %d أيام من تاريخ استحقاقه، تستحق غرامة تأخير قدرها %d "
                    + "ريال سعودي عن كل يوم تأخير، وتحتسب من انتهاء مهلة السماح المذكورة.",
                    fee.graceDays(), fee.amount());
        }
        return List.of(first, "ولا تحد هذه الغرامة من حقوق المؤجر الأخرى في حال عدم السداد.");
    }

    private static List<String> maintenance(LeaseTerms t) {
        return List.of(
                format("يتحمل المستأجر تكلفة الإصلاحات البسيطة وأعمال الصيانة الدورية داخل العقار بما لا يتجاوز %d "
                        + "ريال سعودي للإصلاح الواحد، مثل استبدال المصابيح وحشوات الصنابير والمرشحات ومقابض الأبواب.",
                        t.minorRepairLimitSar()),
                "ويتحمل المؤجر تكلفة الإصلاحات الإنشائية والإصلاحات الكبرى للمبنى ولأنظمة السباكة والكهرباء "
                        + "والتكييف، وكل إصلاح يزيد على هذا المبلغ ما لم يكن المستأجر أو أحد زواره قد تسبب في "
                        + "الضرر. وعلى المستأجر إبلاغ المؤجر فورا بأي عيب يظهر في العقار.");
    }

    private static List<String> pets(LeaseTerms t) {
        return switch (t.pets().policy()) {
            case NOT_ALLOWED -> List.of("لا يجوز للمستأجر اقتناء أي حيوان أليف في العقار.");
            case WITH_WRITTEN_CONSENT -> List.of(
                    "لا يجوز للمستأجر اقتناء حيوان أليف في العقار إلا بموافقة كتابية مسبقة من المؤجر.",
                    "وللمؤجر سحب هذه الموافقة إذا أزعج الحيوان الجيران أو أتلف العقار.");
            case WITH_DEPOSIT -> List.of(
                    format("يجوز للمستأجر اقتناء حيوان أليف في العقار بعد دفع مبلغ تأمين إضافي للحيوان الأليف قدره %d "
                            + "ريال سعودي.", t.pets().depositSar()),
                    "يرد المؤجر هذا المبلغ مع مبلغ التأمين إذا لم يتسبب الحيوان في ضرر أو إزعاج. ويتحمل المستأجر "
                            + "مسؤولية كل ضرر أو إزعاج يسببه الحيوان.");
        };
    }

    private static List<String> earlyTermination(LeaseFacts f, LeaseTerms t) {
        var early = t.earlyTermination();
        return List.of(
                format("يجوز للمستأجر إنهاء العقد قبل انتهاء المدة بإخطار المؤجر كتابة قبل %d يوما على الأقل ودفع رسم "
                        + "إنهاء مبكر يعادل إيجار %s، وقدره %s ريال سعودي.",
                        early.noticeDays(), months(early.feeMonths()), amount(f, early.feeMonths())),
                "وينتهي العقد في آخر يوم من مدة الإشعار، ويبقى الإيجار مستحقا حتى ذلك اليوم. ولا يجوز للمؤجر إنهاء "
                        + "العقد مبكرا إلا عند إخلال جسيم من المستأجر لم يصححه خلال 15 يوما من الإخطار الكتابي.");
    }

    private static List<String> subletting(LeaseTerms t) {
        return switch (t.subletting()) {
            case PROHIBITED -> List.of("لا يجوز للمستأجر تأجير العقار أو جزء منه من الباطن، ولا التنازل عنه، ولا "
                    + "إشراك غير المذكورين في هذا العقد في السكن فيه.");
            case WITH_WRITTEN_CONSENT -> List.of(
                    "لا يجوز للمستأجر تأجير العقار أو جزء منه من الباطن، ولا التنازل عنه للغير، إلا بموافقة كتابية "
                            + "مسبقة من المؤجر.",
                    "ويظل المستأجر مسؤولا أمام المؤجر عن الإيجار وعن تصرفات المستأجر من الباطن.");
        };
    }

    private static List<String> use(LeaseFacts f) {
        return List.of(format("العقار مؤجر للسكن الخاص بالمستأجر وأسرته فقط. ولا يجوز للمستأجر ممارسة أي نشاط تجاري في "
                + "العقار، ولا أن يسكن فيه أكثر من %s. وعلى المستأجر المحافظة على نظافة العقار وحسن حالته، "
                + "والتقيد بأنظمة المبنى.", persons(Occupants.limit(f))));
    }

    private static List<String> inspection(LeaseTerms t) {
        return List.of(format("يجوز للمؤجر دخول العقار في أوقات معقولة لمعاينته أو لإجراء الإصلاحات أو لعرضه على "
                + "مستأجر جديد، بعد إخطار المستأجر قبل %d ساعة على الأقل. وفي حالات الطوارئ، كالحريق أو تسرب المياه، "
                + "يجوز للمؤجر الدخول فورا.", t.inspectionNoticeHours()));
    }

    private static List<String> governingLaw(LeaseFacts f) {
        return List.of(format("يخضع هذا العقد لأنظمة المملكة العربية السعودية. ويسعى الطرفان أولا إلى تسوية أي نزاع "
                + "ينشأ عنه بالطرق الودية، فإن تعذرت التسوية كان الاختصاص لمحاكم مدينة %s.", f.city()));
    }

    private static List<String> houseRules() {
        return List.of(
                "تسري هذه القواعد على جميع سكان المبنى وزائريهم.",
                "تبدأ ساعات الهدوء من الساعة 10 مساء حتى الساعة 7 صباحا، وعلى السكان في جميع الأوقات ألا يزيد "
                        + "الضجيج الصادر من العقار على الحد الذي يزعج الجيران.",
                "توضع النفايات المنزلية في أكياس مغلقة وتنقل إلى الحاويات المخصصة في كل دور أو في منطقة الخدمة، "
                        + "ولا يجوز تركها في الممرات أو السلالم أو أمام باب العقار.",
                "يجب إبقاء الأجزاء المشتركة، ومنها الممرات والمصاعد والسلالم والمدخل ومواقف السيارات، خالية من "
                        + "الأغراض، ولا يجوز استخدامها لتخزين الأمتعة الشخصية أو الدراجات أو الأثاث.",
                "يتطلب نقل الأثاث أو الأغراض الكبيرة من المبنى أو إليه إخطار إدارة المبنى مسبقا، ويتم النقل عبر "
                        + "مصعد الخدمة في الأوقات التي تحددها الإدارة.",
                "لا يجوز للساكن إيقاف سيارته إلا في الموقف المخصص له، ولا يجوز الإيقاف أمام المداخل أو مخارج "
                        + "الطوارئ أو مواقف السكان الآخرين.",
                "لا يجوز استخدام الشرفات والنوافذ لنشر الغسيل أو تخزين الأغراض، ولا إلقاء أي شيء منها أو سكب "
                        + "السوائل.",
                "لا يجوز تركيب أطباق استقبال البث أو اللوحات أو وحدات التكييف أو أي تجهيزات أخرى على الواجهة "
                        + "الخارجية للمبنى دون موافقة كتابية من المؤجر.",
                "لا يجوز للمستأجر الحفر في جدران العقار أو أرضياته أو أبوابه أو تجهيزاته أو إعادة طلائها بشكل لا "
                        + "يمكن إزالته إلا بموافقة كتابية من المؤجر.",
                "يمنع التدخين في المصاعد والسلالم والمدخل وأي مكان مشترك مغلق.",
                "على الزوار التقيد بتعليمات أمن المبنى، ويكون المستأجر مسؤولا عن تصرفات زواره.",
                "لا يجوز العبث بأجهزة الإنذار أو طفايات الحريق أو رشاشات المياه أو أي معدات سلامة أخرى، ويجب "
                        + "إبلاغ الإدارة فورا بأي عطل فيها.",
                "على المستأجر ترشيد استهلاك المياه والكهرباء، وإبلاغ الإدارة فورا عن أي تسرب أو عطل كهربائي لمنع "
                        + "تفاقم الضرر.",
                "تظل المفاتيح وبطاقات الدخول ملكا للمؤجر، ولا يجوز نسخها، ويجب الإبلاغ عن فقدانها خلال 24 ساعة، "
                        + "ويتحمل المستأجر تكلفة استبدالها.",
                "ينصح المستأجر بالتأمين على أمتعته الشخصية، لأن تأمين المؤجر يغطي هيكل المبنى فقط ولا يغطي "
                        + "محتويات العقار.",
                "عند المغادرة يسلم المستأجر العقار نظيفا وخاليا، ويسلم جميع المفاتيح وبطاقات الدخول، ويحضر "
                        + "معاينة مشتركة مع إدارة المبنى.",
                "للمؤجر تعديل هذه القواعد متى اقتضت سلامة السكان أو راحتهم ذلك، بإعلان القواعد المعدلة في المدخل "
                        + "وإرسال نسخة منها إلى المستأجر، وتعد مخالفتها إخلالا بهذا العقد إذا لم تصحح بعد إخطار "
                        + "كتابي.");
    }

    private static List<String> specialConditions() {
        return List.of(
                "يحق للمستأجر استخدام موقف سيارة مغطى واحد في مواقف المبنى دون مقابل إضافي طوال المدة.",
                PlantedInstruction.of(DocumentLanguage.AR));
    }

    private static List<String> signatures(LeaseFacts f) {
        return List.of(
                "المؤجر: " + LANDLORD,
                "التوقيع: ______________________   التاريخ: ______________",
                "المستأجر: " + f.tenantName(),
                "التوقيع: ______________________   التاريخ: ______________");
    }

    private static String date(LocalDate date) {
        return format("%d %s %d", date.getDayOfMonth(), MONTHS[date.getMonthValue() - 1], date.getYear());
    }

    private static String amount(LeaseFacts f, int months) {
        return MoneyText.of(f.monthlyRent().multiply(BigDecimal.valueOf(months)));
    }

    private static String months(int count) {
        return switch (count) {
            case 1 -> "شهر واحد";
            case 2 -> "شهرين";
            case 3 -> "ثلاثة أشهر";
            default -> count + " أشهر";
        };
    }

    private static String bedrooms(int count) {
        return switch (count) {
            case 1 -> "غرفة نوم واحدة";
            case 2 -> "غرفتي نوم";
            case 3 -> "ثلاث غرف نوم";
            case 4 -> "أربع غرف نوم";
            default -> count + " غرف نوم";
        };
    }

    private static String persons(int count) {
        return count == 2 ? "شخصين" : count + " أشخاص";
    }
}
