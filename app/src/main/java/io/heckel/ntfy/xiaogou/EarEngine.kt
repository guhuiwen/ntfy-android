package io.heckel.ntfy.xiaogou

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 唤醒词引擎（sherpa-onnx 的关键词识别，中文小模型，全在手机上跑，不联网）。
 *
 * 模型是公开的，打包时由 GitHub Actions 下载进 assets/kws/；唤醒词本身不在这个仓库里——
 * 它从服务器的配置里来（一行一个，拼音的声母 + 韵母，@ 后面是原词），到了手机上写成一个私有文件给引擎读。
 */
class EarEngine private constructor(private val spotter: KeywordSpotter, private var stream: OnlineStream) {

    /** 喂一小段声音（16kHz、-1~1 的小数）。听到唤醒词就返回那个词，没听到返回 null */
    fun accept(samples: FloatArray): String? {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        var hit: String? = null
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotEmpty()) {
                hit = keyword
                spotter.reset(stream)
            }
        }
        return hit
    }

    /** 换一条新的流：停了一阵再接着听的时候用，免得把之前的半截声音接上 */
    fun newStream() {
        try {
            stream.release()
        } catch (_: Exception) {
        }
        stream = spotter.createStream()
    }

    fun release() {
        try {
            stream.release()
        } catch (_: Exception) {
        }
        try {
            spotter.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "XiaogouQuick"
        const val SAMPLE_RATE = 16000
        private val MODEL_FILES = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

        /** 包里带没带模型（没带 = 这个包不是 GitHub Actions 打的） */
        fun hasModel(context: Context): Boolean {
            return try {
                val names = context.assets.list("kws")?.toSet() ?: emptySet()
                MODEL_FILES.all { it in names }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 准备好引擎。keywordLines = 服务器给的唤醒词（每行形如「x iǎo ài t óng x ué @小爱同学」，可带 :加分 #门槛）。
         * 行里有模型不认识的拼音就丢掉那一行（引擎遇到不认识的会直接让 App 崩掉，所以先筛一遍）。
         * 一行能用的都没有、或者模型不在，返回 null。要在后台线程里调。
         */
        fun create(context: Context, keywordLines: List<String>, threshold: Float): EarEngine? {
            if (!hasModel(context)) {
                Log.w(TAG, "ear: no model in this build")
                return null
            }
            val dir = File(context.filesDir, "kws")
            if (!dir.exists() && !dir.mkdirs()) return null
            val stamp = File(dir, "build.txt")
            val fresh = stamp.exists() && stamp.readText() == QuickPrefs.BUILD && MODEL_FILES.all { File(dir, it).length() > 0 }
            if (!fresh) {
                for (name in MODEL_FILES) {
                    context.assets.open("kws/$name").use { input ->
                        File(dir, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                stamp.writeText(QuickPrefs.BUILD)
            }
            val known = HashSet<String>()
            File(dir, "tokens.txt").forEachLine { line ->
                val token = line.trim().substringBefore(' ')
                if (token.isNotEmpty()) known.add(token)
            }
            val usable = keywordLines.map { it.trim() }.filter { line ->
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val tokens = parts.filter { !it.startsWith("@") && !it.startsWith(":") && !it.startsWith("#") }
                val ok = tokens.isNotEmpty() && tokens.all { it in known } && parts.any { it.startsWith("@") }
                if (!ok) Log.w(TAG, "ear: dropped a keyword line the model cannot spell (${tokens.size} tokens)")
                ok
            }
            if (usable.isEmpty()) {
                Log.w(TAG, "ear: no usable keyword")
                return null
            }
            val keywordsFile = File(dir, "keywords.txt")
            keywordsFile.writeText(usable.joinToString("\n") + "\n")
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.onnx").absolutePath,
                        decoder = File(dir, "decoder.onnx").absolutePath,
                        joiner = File(dir, "joiner.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    modelType = "zipformer2",
                    numThreads = 1,
                ),
                keywordsFile = keywordsFile.absolutePath,
                keywordsThreshold = threshold,
            )
            val spotter = KeywordSpotter(assetManager = null, config = config)
            Log.d(TAG, "ear: engine ready (${usable.size} keyword lines, threshold $threshold)")
            return EarEngine(spotter, spotter.createStream())
        }
    }
}
