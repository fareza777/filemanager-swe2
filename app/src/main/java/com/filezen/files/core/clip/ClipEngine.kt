package com.filezen.files.core.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device CLIP semantic image search — text query → matching photos.
 *
 * Two ONNX models, downloaded on demand (~200 MB once, into internal storage):
 *  - vision: OpenAI CLIP ViT-B/32 image encoder (Xenova q4 build) →
 *    `image_embeds` [1,512] from `pixel_values` [1,3,224,224].
 *  - text: sentence-transformers/clip-ViT-B-32-multilingual-v1 — a
 *    multilingual transformer whose text space is aligned to that same vision
 *    space, so Indonesian queries ("foto rapat", "kucing di sofa") hit the
 *    same embeddings. It outputs `last_hidden_state`; FileZen does the
 *    sentence-transformers post-pipeline itself: masked mean-pool →
 *    768→512 linear projection (weights pulled from the model's safetensors
 *    blob) → L2 normalise.
 *
 * Preprocessing matches OpenAI CLIP: centre-crop-square → 224×224, RGB,
 * normalised with the CLIP mean/std.
 */
class ClipEngine(private val app: Context) {

    private val dir get() = File(app.filesDir, "clip")
    private val visionFile get() = File(dir, "vision_model_fp16.onnx")
    private val versionFile get() = File(dir, "model.ver")

    /** Bump when the encoder lineup changes — embeddings from a different
     *  model quality shouldn't mix in the index. v1 = q4, v2 = int8 (dropped:
     *  ConvInteger unsupported on Android ORT), v3 = fp16, v4 = +OCR text. */
    private val modelVersion = 5
    private val textFile get() = File(dir, "text_model_int8.onnx")
    private val vocabFile get() = File(dir, "vocab.txt")
    private val denseFile get() = File(dir, "dense.safetensors")

    /** Everything needed locally? (partial downloads resume — see [download]) */
    val isReady: Boolean get() =
        visionFile.length() > 1_000_000 && textFile.length() > 1_000_000 &&
            vocabFile.length() > 100_000 && denseFile.length() > 1_000_000

    data class DownloadProgress(val fileIx: Int, val fileCount: Int,
        val label: String, val bytesDone: Long, val bytesTotal: Long)
    private val _dl = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _dl
    @Volatile private var downloading = false

    private val http = OkHttpClient()

    private data class Part(val url: String, val file: File, val label: String, val size: Long)

    private fun parts(): List<Part> = listOf(
        Part("$HF/Xenova/clip-vit-base-patch32/resolve/main/onnx/vision_model_fp16.onnx",
            visionFile, "image encoder", 176_080_659),
        Part("$HF/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/main/onnx/model_qint8_arm64.onnx",
            textFile, "multilingual text encoder", 135_336_307),
        Part("$HF/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/main/vocab.txt",
            vocabFile, "tokenizer", 995_526),
        Part("$HF/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/main/2_Dense/model.safetensors",
            denseFile, "projection", 1_572_984),
    )

    /** Download every missing model part (resumable). Throws on failure. */
    suspend fun download() = withContext(Dispatchers.IO) {
        if (downloading || isReady) return@withContext
        downloading = true
        try {
            dir.mkdirs()
            val list = parts()
            val total = list.sumOf { it.size }
            val doneBefore = list.sumOf { it.file.length().coerceAtMost(it.size) }
            for ((ix, p) in list.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (p.file.length() >= p.size && p.file.length() - p.size < 1024) continue
                fetch(p, ix, list.size, total, doneBefore)
            }
            // sanity — every file must be fully present
            if (!isReady) error("Download incomplete — try again")
            // delete stale encoders from older versions — they only waste space
            File(dir, "vision_model_q4.onnx").delete()
            File(dir, "vision_model_int8.onnx").delete()
        } finally {
            downloading = false
            _dl.value = null
        }
    }

    private fun fetch(p: Part, ix: Int, count: Int, grandTotal: Long, doneBefore: Long) {
        val have = p.file.length()
        val req = Request.Builder().url(p.url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} downloading ${p.label}")
            val body = resp.body ?: error("empty body")
            val append = have > 0 && resp.header("Content-Range") != null
            RandomAccessFile(p.file, "rw").use { raf ->
                if (!append) raf.setLength(0) else raf.seek(have)
                val buf = ByteArray(256 * 1024)
                var n: Int
                var got = if (append) have else 0L
                while (body.byteStream().read(buf).also { n = it } > 0) {
                    raf.write(buf, 0, n); got += n
                    _dl.value = DownloadProgress(
                        ix, count, p.label,
                        doneBefore + got, grandTotal)
                }
            }
        }
    }

    /** True when a downloaded model predates [modelVersion] — the caller
     *  should wipe the image index once so rows get re-embedded. */
    fun needsReindex(): Boolean {
        if (!isReady) return false
        val v = runCatching { versionFile.readText().trim().toInt() }.getOrDefault(0)
        if (v >= modelVersion) return false
        runCatching { versionFile.writeText(modelVersion.toString()) }
        return true
    }

    // ---------- inference ----------

    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var vision: OrtSession? = null
    @Volatile private var text: OrtSession? = null
    @Volatile private var tokenizer: WordPieceTokenizer? = null
    @Volatile private var dense: FloatArray? = null // [512,768] row-major

    @Synchronized
    private fun ensureLoaded() {
        if (!isReady) error("model not downloaded")
        if (vision == null) vision = env.createSession(visionFile.absolutePath,
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            })
        if (text == null) text = env.createSession(textFile.absolutePath,
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
            })
        if (tokenizer == null) tokenizer = WordPieceTokenizer(vocabFile)
        if (dense == null) dense = loadDense(denseFile)
    }

    /** CLIP preprocessing: centre-crop square, resize 224, normalise. */
    suspend fun embedImage(src: File): FloatArray = withContext(Dispatchers.Default) {
        ensureLoaded()
        val bmp = decode(src) ?: error("cannot decode image")
        if (bmp.width < 96 || bmp.height < 96) { bmp.recycle(); error("too small to be a photo") }
        val px = preprocess(bmp)
        bmp.recycle()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(px), longArrayOf(1, 3, 224, 224))
            .use { t ->
                vision!!.run(mapOf("pixel_values" to t)).use { out ->
                    val v = (out.get("image_embeds").get().value
                        as Array<FloatArray>)[0]
                    normalize(v.copyOf())
                }
            }
    }

    /** Tokenise → transformer → masked mean-pool → Dense → L2-norm.
     *  The query is embedded twice — raw and inside a caption template — and
     *  averaged; CLIP was trained on captioned photos ("a photo of X"), so the
     *  template form pulls noticeably better matches for short queries. */
    suspend fun embedText(query: String): FloatArray = withContext(Dispatchers.Default) {
        ensureLoaded()
        // Embed raw + two caption templates and average — ensemble of phrasings
        // is more robust than any single one for short queries.
        val templates = if (query.all { it.code < 128 })
            listOf(query, "a photo of $query", "a picture of $query")
        else listOf(query, "foto $query", "gambar $query")
        val a = encodeOnce(templates[0])
        val b = encodeOnce(templates[1])
        val c = encodeOnce(templates[2])
        for (i in a.indices) a[i] = (a[i] + b[i] + c[i]) / 3f
        normalize(a)
    }

    /** Query expansion for Indonesian: the multilingual text encoder scores
     *  Indonesian phrases ~0.10 below their English equivalents on the same
     *  images (measured on the real index). Translating common photo-search
     *  terms first lifts them to English-level accuracy; the raw query stays
     *  as a variant so unmapped words still go through the multilingual model.
     *  Multiword phrases match before single tokens. */
    fun expandQueries(query: String): List<String> {
        val out = mutableListOf(query)
        val lower = query.lowercase()
        for ((id, en) in ID_PHRASES)
            if (lower.contains(id)) out += lower.replace(id, en)
        val toks = lower.split(Regex("\\s+"))
        fun translated(ts: List<String>): String? {
            val m = ts.map { lookupEn(it) ?: it }
            return if (m == ts) null else m.joinToString(" ")
        }
        translated(toks)?.let { out += it }
        // Filler-word-free variant: "foto ppsu lagi bersih-bersih" embeds
        // better as "ppsu bersih-bersih" — intent words dilute the vector.
        val clean = toks.filter { it !in STOPWORDS }
        if (clean.size in 1 until toks.size) {
            val joined = clean.joinToString(" ")
            out += joined
            translated(clean)?.let { out += it }
        }
        // Single-token variants: when a long mixed query matches nothing,
        // each translatable content word is tried alone ("rapat kemarin" →
        // "meeting" still finds meeting photos).
        for (t in clean) {
            val en = if (t.length >= 4) lookupEn(t) else null
            if (en != null && en != t) out += en
        }
        return out.distinct()
    }

    /** Filler words stripped before embedding — Indonesian intent/function
     *  words plus the usual English ones. "foto"/"gambar" are listed too:
     *  in a photo search they only ever mean "show me photos of…". */
    private val STOPWORDS = setOf(
        "foto", "gambar", "fotonya", "gambarnya", "cari", "carikan", "tolong",
        "mohon", "yang", "lagi", "di", "itu", "ini", "sama", "dan", "atau",
        "semua", "semuanya", "ada", "ku", "nya", "kok", "dong", "nih", "sih",
        "deh", "ya", "yah", "kan", "banget", "sekali", "pula", "juga", "udah",
        "sudah", "belum", "masih", "pernah", "akan", "bisa", "dapat", "mau",
        "ingin", "kayak", "kayaknya", "seperti", "mirip", "tentang", "soal",
        "the", "a", "an", "of", "my", "me", "show", "find", "please",
    )

    /** Indonesian→English lookup with light morphology: strips common
     *  affixes (ber-/me-/pe-/di-/-nya/-kan/…) but only accepts the stem
     *  when it actually exists in the glossary — so "kemarin" never maps
     *  to a bogus stem. */
    private fun lookupEn(t: String): String? {
        ID_WORDS[t]?.let { return it }
        for (suf in SUFFIXES)
            if (t.length > suf.length + 3 && t.endsWith(suf))
                ID_WORDS[t.dropLast(suf.length)]?.let { return it }
        for (pre in PREFIXES)
            if (t.length > pre.length + 3 && t.startsWith(pre)) {
                val stem = t.drop(pre.length)
                ID_WORDS[stem]?.let { return it }
                for (suf in SUFFIXES)
                    if (stem.length > suf.length + 2 && stem.endsWith(suf))
                        ID_WORDS[stem.dropLast(suf.length)]?.let { return it }
            }
        return null
    }

    private val SUFFIXES = listOf("lah", "kah", "nya", "kan", "an", "i")
    private val PREFIXES = listOf(
        "memper", "diper", "meny", "meng", "men", "mem", "pem", "peng",
        "pen", "per", "ber", "ter", "pel", "di", "ke", "se")

    private val ID_PHRASES = mapOf(
        "kerja bakti" to "community work", "gotong royong" to "community work",
        "bukti transfer" to "transfer receipt", "bukti pembayaran" to "payment receipt",
        "bukti transaksi" to "transaction receipt", "bukti pembelian" to "purchase receipt",
        "ulang tahun" to "birthday", "selamat ulang tahun" to "happy birthday",
        "tahun baru" to "new year", "selamat pagi" to "good morning",
        "tanda tangan" to "signature", "kartu keluarga" to "family card",
        "kartu identitas" to "id card", "kartu nama" to "business card",
        "pas foto" to "id photo", "foto bersama" to "group photo",
        "foto keluarga" to "family photo", "foto nikah" to "wedding photo",
        "tangkapan layar" to "screenshot", "foto lama" to "old photo",
        "matahari terbenam" to "sunset", "matahari terbit" to "sunrise",
        "rumah sakit" to "hospital", "pakaian adat" to "traditional costume",
        "pasar malam" to "night market", "kolam renang" to "swimming pool",
        "kebun binatang" to "zoo", "air terjun" to "waterfall",
        "sepeda motor" to "motorcycle", "mobil polisi" to "police car",
        "kebun teh" to "tea plantation", "pantai pasir" to "sandy beach",
        "anak sekolah" to "school children", "orang tua" to "parents",
        "hari raya" to "holiday celebration", "waktu kecil" to "childhood",
        "kerja kelompok" to "group work", "bersih bersih" to "cleaning",
        "bersih-bersih" to "cleaning", "pembersihan pantai" to "beach cleanup",
        "kembang api" to "fireworks", "taman nasional" to "national park",
        "lampu lalu lintas" to "traffic light", "pohon natal" to "christmas tree",
        "anak kucing" to "kitten", "anak anjing" to "puppy",
        "rumah makan" to "restaurant", "warung makan" to "food stall",
        "tempat wisata" to "tourist attraction", "gedung tinggi" to "skyscraper",
        "lautan" to "ocean", "pemandangan" to "scenery",
        "makan siang" to "lunch", "makan malam" to "dinner",
        "makan pagi" to "breakfast", "foto selfie" to "selfie",
        "main bola" to "playing football", "sepak bola" to "football",
    )

    private val ID_WORDS = mapOf(
        "foto" to "photo", "gambar" to "picture", "potret" to "portrait",
        "rapat" to "meeting", "kerja" to "work", "bakti" to "service",
        "transfer" to "transfer", "bukti" to "proof", "struk" to "receipt",
        "nota" to "receipt", "invoice" to "invoice", "kuitansi" to "receipt",
        "pantai" to "beach", "laut" to "sea", "gunung" to "mountain",
        "sawah" to "rice field", "kebun" to "garden", "hutan" to "forest",
        "danau" to "lake", "sungai" to "river", "kota" to "city",
        "desa" to "village", "kampung" to "village", "rumah" to "house",
        "kantor" to "office", "sekolah" to "school", "masjid" to "mosque",
        "gereja" to "church", "pura" to "temple", "candi" to "temple",
        "kucing" to "cat", "anjing" to "dog", "burung" to "bird",
        "ikan" to "fish", "ayam" to "chicken", "kambing" to "goat",
        "sapi" to "cow", "kerbau" to "buffalo", "kuda" to "horse",
        "bunga" to "flower", "pohon" to "tree", "daun" to "leaf",
        "makanan" to "food", "kue" to "cake", "kopi" to "coffee",
        "pasar" to "market", "toko" to "shop", "mall" to "mall",
        "jalan" to "street", "mobil" to "car", "motor" to "motorcycle",
        "sepeda" to "bicycle", "bus" to "bus", "kereta" to "train",
        "pesawat" to "airplane", "kapal" to "ship", "perahu" to "boat",
        "anak" to "child", "bayi" to "baby", "keluarga" to "family",
        "teman" to "friend", "orang" to "person", "pria" to "man",
        "wanita" to "woman", "selfie" to "selfie", "pesta" to "party",
        "nikah" to "wedding", "pernikahan" to "wedding", "wisuda" to "graduation",
        "lebaran" to "eid celebration", "natal" to "christmas",
        "senja" to "dusk", "sunset" to "sunset", "sunrise" to "sunrise",
        "langit" to "sky", "awan" to "cloud", "hujan" to "rain",
        "api" to "fire", "banjir" to "flood", "sampah" to "trash",
        "acara" to "event", "seminar" to "seminar", "presentasi" to "presentation",
        "kelas" to "classroom", "dokumen" to "document", "surat" to "letter",
        "uang" to "money", "dompet" to "wallet", "laptop" to "laptop",
        "komputer" to "computer", "layar" to "screen", "dokter" to "doctor",
        "polisi" to "police", "tentara" to "soldier", "guru" to "teacher",
        "petani" to "farmer", "nelayan" to "fisherman", "tukang" to "worker",
        "muda" to "young", "tua" to "old", "bersih" to "clean", "kotor" to "dirty",
        "besar" to "big", "kecil" to "small", "banyak" to "crowd of",
        "bermain" to "playing", "main" to "play", "tidur" to "sleeping",
        "makan" to "eating", "minum" to "drinking", "duduk" to "sitting",
        "berdiri" to "standing", "berlari" to "running", "lari" to "running",
        "berenang" to "swimming", "memasak" to "cooking", "masak" to "cooking",
        "membaca" to "reading", "menulis" to "writing", "belajar" to "studying",
        "tertawa" to "laughing", "tersenyum" to "smiling", "menari" to "dancing",
        "menyanyi" to "singing", "bernyanyi" to "singing", "olahraga" to "sport",
        "bersepeda" to "cycling", "memancing" to "fishing", "mancing" to "fishing",
        "belanja" to "shopping", "salat" to "praying",
        "beribadah" to "worship", "mengaji" to "reciting quran",
        // places & landmarks
        "vihara" to "temple", "klenteng" to "temple",
        "monumen" to "monument", "tugu" to "monument",
        "patung" to "statue", "jembatan" to "bridge", "menara" to "tower",
        "gedung" to "building", "hotel" to "hotel",
        "bandara" to "airport", "stasiun" to "station", "terminal" to "terminal",
        "pelabuhan" to "harbor", "dermaga" to "pier", "mercusuar" to "lighthouse",
        "kafe" to "cafe", "cafe" to "cafe", "dapur" to "kitchen",
        "kamar" to "room", "taman" to "park",
        "lapangan" to "field", "stadion" to "stadium", "gym" to "gym",
        // nature & outdoors
        "ladang" to "field",
        "bukit" to "hill", "lembah" to "valley", "tebing" to "cliff",
        "gua" to "cave", "goa" to "cave", "kawah" to "crater",
        "ombak" to "waves", "pasir" to "sand", "batu" to "rock",
        "kolam" to "pond",
        "waduk" to "reservoir", "salju" to "snow", "embun" to "dew",
        "kabut" to "fog", "mendung" to "overcast", "cerah" to "sunny",
        "pelangi" to "rainbow", "petir" to "lightning", "badai" to "storm",
        "rumput" to "grass", "tumbuhan" to "plant", "tanaman" to "plant",
        "kaktus" to "cactus", "palem" to "palm tree", "kelapa" to "coconut",
        // events & activities
        "upacara" to "ceremony", "perayaan" to "celebration", "pawai" to "parade",
        "karnaval" to "carnival", "festival" to "festival", "konser" to "concert",
        "pameran" to "exhibition", "lomba" to "competition", "pertandingan" to "match",
        "lamaran" to "engagement",
        "liburan" to "vacation", "mudik" to "homecoming trip", "piknik" to "picnic",
        "berkemah" to "camping", "kemah" to "camping", "mendaki" to "hiking",
        "pendakian" to "hiking", "perjalanan" to "journey", "wisata" to "tourism",
        "arisan" to "social gathering", "pengajian" to "religious gathering",
        "syukuran" to "thanksgiving feast", "kenduri" to "feast",
        "diskusi" to "discussion", "pelatihan" to "training", "lokakarya" to "workshop",
        "workshop" to "workshop", "webinar" to "webinar",
        "ujian" to "exam", "praktikum" to "lab work", "magang" to "internship",
        // documents & objects
        "sertifikat" to "certificate", "ijazah" to "diploma", "rapor" to "report card",
        "piagam" to "certificate",
        "ktp" to "id card", "sim" to "driver license", "paspor" to "passport",
        "tiket" to "ticket", "tagihan" to "bill",
        "jadwal" to "schedule", "poster" to "poster", "spanduk" to "banner",
        "kalender" to "calendar", "buku" to "book", "majalah" to "magazine",
        "koran" to "newspaper", "peta" to "map",
        "televisi" to "tv", "hp" to "phone", "ponsel" to "phone",
        "kamera" to "camera",
        "truk" to "truck", "becak" to "rickshaw",
        "delman" to "horse carriage", "gerobak" to "cart",
        "helikopter" to "helicopter", "drone" to "drone", "roket" to "rocket",
        // people & creatures
        "remaja" to "teenager", "dewasa" to "adult", "lansia" to "elderly",
        "kerabat" to "relative",
        "tetangga" to "neighbor", "warga" to "residents", "kerumunan" to "crowd",
        "penonton" to "audience", "pasukan" to "troops", "prajurit" to "soldier",
        "ustadz" to "cleric", "pendeta" to "priest", "biksu" to "monk",
        "hewan" to "animal",
        "kelinci" to "rabbit", "hamster" to "hamster",
        "bebek" to "duck", "itik" to "duck",
        "angsa" to "goose", "kalkun" to "turkey", "merpati" to "pigeon",
        "elang" to "eagle", "merak" to "peacock", "nuri" to "parrot",
        "kura" to "turtle", "penyu" to "sea turtle", "buaya" to "crocodile",
        "ular" to "snake", "kadal" to "lizard", "katak" to "frog",
        "domba" to "sheep", "keledai" to "donkey",
        "babi" to "pig", "gajah" to "elephant", "harimau" to "tiger",
        "singa" to "lion", "macan" to "tiger", "zebra" to "zebra",
        "jerapah" to "giraffe", "panda" to "panda", "beruang" to "bear",
        "monyet" to "monkey", "orangutan" to "orangutan", "simpanse" to "chimpanzee",
        "rusa" to "deer", "tupai" to "squirrel", "tikus" to "rat",
        "kelelawar" to "bat", "serigala" to "wolf", "rubah" to "fox",
        "kupu" to "butterfly", "capung" to "dragonfly", "lebah" to "bee",
        "semut" to "ant", "laba" to "spider", "nyamuk" to "mosquito",
        "lalat" to "fly", "kecoa" to "cockroach", "belalang" to "grasshopper",
        "siput" to "snail", "cacing" to "worm", "ubur" to "jellyfish",
        "gurita" to "octopus", "cumi" to "squid", "bintang" to "star",
        "paus" to "whale", "lumba" to "dolphin", "hiu" to "shark",
        // food & drink
        "minuman" to "drink", "nasi" to "rice", "sarapan" to "breakfast",
        "mie" to "noodles", "roti" to "bread",
        "teh" to "tea", "susu" to "milk",
        "jus" to "juice", "buah" to "fruit", "sayur" to "vegetable",
        "daging" to "meat", "telur" to "egg", "keju" to "cheese",
        "coklat" to "chocolate", "permen" to "candy", "es" to "ice",
        "cemilan" to "snack", "camilan" to "snack", "jajanan" to "snacks",
        "sate" to "satay", "rendang" to "rendang", "bakso" to "meatball soup",
        "soto" to "soto soup", "gado" to "gado-gado", "pecel" to "pecel",
        "pempek" to "pempek", "martabak" to "martabak", "pisang" to "banana",
        "mangga" to "mango", "jeruk" to "orange", "apel" to "apple",
        "semangka" to "watermelon", "melon" to "melon", "anggur" to "grape",
        "durian" to "durian", "rambutan" to "rambutan", "manggis" to "mangosteen",
        "salak" to "snakefruit", "nanas" to "pineapple", "jambu" to "guava",
        "pepaya" to "papaya", "cabai" to "chili", "bawang" to "onion",
        "jahe" to "ginger", "kunyit" to "turmeric", "tomat" to "tomato",
        "kentang" to "potato", "wortel" to "carrot", "jagung" to "corn",
        // misc descriptors
        "warna" to "color", "hitam" to "black", "putih" to "white",
        "merah" to "red", "biru" to "blue", "hijau" to "green",
        "kuning" to "yellow", "oranye" to "orange", "ungu" to "purple",
        "pink" to "pink", "cokelat" to "brown", "abu" to "gray",
        "malam" to "night", "siang" to "day", "sore" to "afternoon",
        "pagi" to "morning", "kemarin" to "yesterday", "dulu" to "past",
        "indah" to "beautiful", "cantik" to "pretty", "ganteng" to "handsome",
        "lucu" to "cute", "imut" to "cute", "seram" to "scary",
        "ramai" to "crowded", "sepi" to "quiet", "padat" to "dense",
        "jelas" to "clear", "buram" to "blurry", "dekat" to "close up",
        "jauh" to "distant", "dalam" to "inside", "luar" to "outside",
        "koleksi" to "collection", "suasana" to "atmosphere",
    )

    private fun encodeOnce(q: String): FloatArray {
        val (ids, mask) = tokenizer!!.encode(q)
        val shape = longArrayOf(1, ids.size.toLong())
        return OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                text!!.run(mapOf(
                    "input_ids" to idsT,
                    "attention_mask" to maskT,
                )).use { out ->
                    val hidden = (out.get("last_hidden_state").get().value
                        as Array<Array<FloatArray>>)[0]
                    val pooled = meanPool(hidden, mask)
                    val proj = matVec(dense!!, pooled) // [512,768]·[768] → [512]
                    normalize(proj)
                }
            }
        }
    }

    private fun decode(src: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 640 || bounds.outHeight / (sample * 2) >= 640)
            sample *= 2
        val bmp = BitmapFactory.decodeFile(src.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Photos store rotation in EXIF — BitmapFactory ignores it, and CLIP is
        // not rotation-invariant, so apply the orientation before embedding.
        val rot = runCatching {
            ExifInterface(src.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val deg = when (rot) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(deg) }, true)
        if (out != bmp) bmp.recycle()
        return out
    }

    private fun preprocess(bmp: Bitmap): FloatArray {
        val side = min(bmp.width, bmp.height)
        val x = (bmp.width - side) / 2; val y = (bmp.height - side) / 2
        val crop = Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, x, y, side, side), 224, 224, true)
        val px = IntArray(224 * 224)
        crop.getPixels(px, 0, 224, 0, 0, 224, 224)
        crop.recycle()
        val out = FloatArray(3 * 224 * 224)
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF; val g = (px[i] shr 8) and 0xFF; val b = px[i] and 0xFF
            out[i] = (r / 255f - 0.48145466f) / 0.26862954f
            out[224 * 224 + i] = (g / 255f - 0.4578275f) / 0.26130258f
            out[2 * 224 * 224 + i] = (b / 255f - 0.40821073f) / 0.27577711f
        }
        return out
    }

    /** Mean over real tokens (masked) → [768]. */
    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden[0].size
        val out = FloatArray(dim)
        var n = 0f
        for (i in hidden.indices) {
            if (i >= mask.size || mask[i] == 0L) continue
            n += 1f
            for (d in 0 until dim) out[d] += hidden[i][d]
        }
        if (n > 0) for (d in 0 until dim) out[d] /= n
        return out
    }

    private fun matVec(m: FloatArray, v: FloatArray): FloatArray {
        val cols = v.size
        val rows = m.size / cols
        val out = FloatArray(rows)
        for (r in 0 until rows) {
            var s = 0f; val base = r * cols
            for (c in 0 until cols) s += m[base + c] * v[c]
            out[r] = s
        }
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f; for (x in v) n += x * x
        n = sqrt(n)
        if (n > 0) for (i in v.indices) v[i] /= n
        return v
    }

    /** safetensors: 8-byte LE header len + JSON {"linear.weight":{shape,offsets}} + f32 data. */
    private fun loadDense(f: File): FloatArray {
        val bytes = f.readBytes()
        val hlen = java.nio.ByteBuffer.wrap(bytes, 0, 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).long.toInt()
        val header = String(bytes, 8, hlen)
        // single tensor "linear.weight" [512,768] F32 — pull its data offsets
        val m = Regex("\"data_offsets\"\\s*:\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]").find(header)
            ?: error("bad safetensors header")
        val start = 8 + hlen + m.groupValues[1].toInt()
        val end = 8 + hlen + m.groupValues[2].toInt()
        val n = (end - start) / 4
        val buf = java.nio.ByteBuffer.wrap(bytes, start, n * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(n); buf.get(out)
        return out
    }

    fun close() {
        runCatching { vision?.close() }; runCatching { text?.close() }
        vision = null; text = null
    }

    companion object {
        private const val HF = "https://huggingface.co"
        /** ~201 MB total — shown in the download UI. */
        const val TOTAL_BYTES = 63_642_858L + 135_336_307L + 995_526L + 1_572_984L
    }
}
