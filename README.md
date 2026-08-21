# Sophy Admin

تطبيق Admin للهاتف يكتشف تطبيق **Sophy Receiver** على شاشة ARISTQN عبر شبكة Wi‑Fi المحلية ويرسل الصوت مباشرة عبر UDP بصيغة PCM16 Mono 24 kHz.

## المتطلبات
- Android 8.0+ للهاتف.
- الهاتف والشاشة على نفس شبكة Wi‑Fi.
- Sophy Receiver مثبت ويعمل على الشاشة.
- صلاحية الميكروفون.
- Android 14+ يتطلب `FOREGROUND_SERVICE_MICROPHONE` لخدمة التسجيل في الخلفية.

## التوافق مع Sophy Receiver
هذا المشروع يطابق بروتوكول Receiver السابق:
- mDNS/NSD service type: `_sophy._udp`
- UDP port: `45678`
- Codec: PCM16 little-endian
- Sample rate: 24000 Hz
- Channels: 1
- Packet header: 26 bytes
- Maximum UDP packet: 1300 bytes
- Audio frame: 20 ms = 960 PCM bytes

## الاستخدام
1. ثبّت Sophy Receiver على الشاشة وشغّله مرة واحدة.
2. ثبّت Sophy Admin على HONOR X9C.
3. وافق على صلاحية الميكروفون والإشعارات عند الطلب.
4. التطبيق يبحث تلقائياً عن `Sophy Receiver`.
5. عند ظهور `متصل` اضغط `بدء البث` وتحدث.
6. أغلق واجهة التطبيق إن أردت؛ سيستمر Foreground Service في إرسال الصوت ما دام البث مفعلاً.
7. لإيقاف الإرسال اضغط `إيقاف البث` من الواجهة أو من إشعار الخدمة.

## بث الكاميرا (جديد)
بالإضافة إلى الصوت، يمكن لتطبيق Admin الآن بث صورة الكاميرا مباشرة إلى Sophy Receiver:
- زر منفصل "بدء بث الكاميرا" وسويتش "بث الكاميرا تلقائياً عند العثور على الشاشة"، بنفس منطق الصوت تماماً.
- يعمل عبر Foreground Service بنفس خدمة الصوت (`AdminService`)، لذا يستمر البث حتى بعد إغلاق واجهة التطبيق.
- ينقل الفيديو كإطارات JPEG مجزأة عبر UDP (بروتوكول `VideoProtocol`) على نفس المنفذ 45678، بمعدل ~10 إطار/ثانية ودقة تقارب 960×540، وهو أسلوب خفيف (MJPEG-over-UDP) مناسب لشبكة محلية ولا يحتاج ترميز H.264 معقد.
- يتطلب صلاحية الكاميرا (`android.permission.CAMERA`)؛ سيُطلب عند أول تشغيل مع صلاحية الميكروفون.
- Android 14+ يتطلب أيضاً `FOREGROUND_SERVICE_CAMERA` لتشغيل الكاميرا من خدمة في الخلفية (مُضافة في الـ Manifest).
- يتم تدوير الإطار تلقائياً حسب وضعية الهاتف (عمودي/أفقي) عبر `OrientationEventListener` بحيث تصل الصورة معتدلة إلى الشاشة.

## البناء على GitHub
الـ workflow في `.github/workflows/build.yml` يبني APK تلقائياً ويضعه في Artifacts باسم `sophy-admin-debug`.

## ملاحظات
- الإصدار الحالي يستخدم PCM16 عمداً ليطابق Receiver الحالي ويجعل أول اختبار بسيطاً.
- بعد نجاح الاختبار يمكن إضافة Opus، اقتران الجهاز، تشفير، كلمة تنبيه، وواجهة تحكم متقدمة بدون تغيير أساس الاكتشاف.
- Android قد يوقف التسجيل إذا أوقف المستخدم التطبيق قسرياً من إعدادات النظام أو سحب صلاحية الميكروفون.

## Build fix
The Admin project uses `setContentView(root)` in `MainActivity`; do not pass width/height integers to Activity#setContentView.
The UDP target port defaults to `45678` and is replaced by the discovered receiver port when NSD resolves the TV.
