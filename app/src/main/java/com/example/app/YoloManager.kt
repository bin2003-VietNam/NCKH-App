package com.example.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloManager(private val context: Context) {

    private val inputSize = 640 // model input size
    private lateinit var interpreter: Interpreter
    private var isLoaded = false

    // labels (16 classes)
    private val labels = listOf(
        "ben_xe_buyt",
        "cam_di_nguoc_chieu",
        "cam_do_xe",
        "cam_dung_cam_do_xe",
        "cam_queo_phai",
        "cam_queo_trai",
        "cam_xe_container",
        "cam_xe_o_to",
        "cam_xe_tai",
        "di_cham",
        "duong_nguoi_di_bo_cat_ngang",
        "giao_nhau_voi_duong_khong_uu_tien",
        "huong_phai_di_vong_chuong_ngai_vat",
        "toc_do_toi_da_cho_phep_50km",
        "toc_do_toi_da_cho_phep_60km",
        "tre_em"
    )

    private val scoreThreshold = 0.40f
    private val iouThreshold = 0.45f

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val afd = context.assets.openFd("best_2_float32.tflite")
            val fis = FileInputStream(afd.fileDescriptor)
            val fc: FileChannel = fis.channel
            val startOffset = afd.startOffset
            val declaredLength = afd.declaredLength
            val modelBuffer = fc.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(modelBuffer, options)
            isLoaded = true
            Log.d("YOLO", "Model loaded")
        } catch (e: Exception) {
            e.printStackTrace()
            isLoaded = false
            Log.e("YOLO", "Failed to load model: ${e.message}")
        }
    }

    /**
     * Predict -> returns list of Detection with coordinates in original bitmap pixels
     */
    fun predict(bitmap: Bitmap): List<Detection> {
        if (!isLoaded) return emptyList()

        // Resize to model input
        val inputBmp = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Prepare input ByteBuffer (NHWC, float32)
        val bytePerChannel = 4
        val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * bytePerChannel)
            .order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val pixels = IntArray(inputSize * inputSize)
        inputBmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (i in pixels.indices) {
            val px = pixels[i]
            // Normalize to [0,1]
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((px shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((px and 0xFF) / 255.0f)         // B
        }
        inputBuffer.rewind()

        // Read output tensor shape dynamically
        val outShape = interpreter.getOutputTensor(0).shape() // e.g. [1,20,8400]
        Log.d("YOLO", "Interpreter output shape: ${outShape.contentToString()}")

        // Expect shape [1, channels, numBoxes]
        if (outShape.size != 3) {
            Log.e("YOLO", "Unexpected output rank: ${outShape.size}")
            return emptyList()
        }

        val channels = outShape[1]   // e.g. 20
        val numBoxes = outShape[2]   // e.g. 8400

        // allocate output structure matching interpreter's expectation
        // Java structure: Array(1) { Array(channels) { FloatArray(numBoxes) } }
        val output = Array(1) { Array(channels) { FloatArray(numBoxes) } }

        // Run inference
        try {
            interpreter.run(inputBuffer, output)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("YOLO", "Inference failed: ${e.message}")
            return emptyList()
        }

        // Postprocess raw output and map boxes back to original bitmap size
        return postProcessRaw(output, bitmap.width, bitmap.height)
    }

    /**
     * Post-process raw output of shape [1, channels, numBoxes]
     * For YOLO11 raw head we assume:
     *   channels = 4 (cx,cy,w,h) + nc (classes)
     * class scores start at index 4
     */
    private fun postProcessRaw(output: Array<Array<FloatArray>>, origWidth: Int, origHeight: Int): List<Detection> {
        val results = mutableListOf<Detection>()
        val channels = output[0].size  // 20
        val numBoxes = output[0][0].size  // 8400

        Log.d("YOLO", "Post-process: channels=$channels, boxes=$numBoxes")

        if (channels < 4 + labels.size) {
            Log.e("YOLO", "Invalid channels: $channels (need >= ${4 + labels.size})")
            return emptyList()
        }

        val numClasses = labels.size  // 16

        for (i in 0 until numBoxes) {
            // Box: center_x, center_y, width, height (normalized 0-1)
            val cx = output[0][0][i].coerceIn(0f, 1f)  // Clamp để tránh NaN
            val cy = output[0][1][i].coerceIn(0f, 1f)
            val w = output[0][2][i].coerceIn(0f, 1f)
            val h = output[0][3][i].coerceIn(0f, 1f)

            // Tìm max class score (YOLOv11: direct class scores, no objectness)
            var maxScore = 0f
            var maxCls = -1
            for (c in 0 until numClasses) {
                val score = output[0][4 + c][i].coerceAtLeast(0f)  // Clamp >=0
                if (score > maxScore) {
                    maxScore = score
                    maxCls = c
                }
            }

            if (maxScore < scoreThreshold) continue

            // Decode boxes (center to corners, scale to original)
            val scaleX = origWidth.toFloat() / inputSize.toFloat()
            val scaleY = origHeight.toFloat() / inputSize.toFloat()

            val x1 = ((cx - w / 2f) * inputSize * scaleX).coerceIn(0f, origWidth.toFloat())
            val y1 = ((cy - h / 2f) * inputSize * scaleY).coerceIn(0f, origHeight.toFloat())
            val x2 = ((cx + w / 2f) * inputSize * scaleX).coerceIn(0f, origWidth.toFloat())
            val y2 = ((cy + h / 2f) * inputSize * scaleY).coerceIn(0f, origHeight.toFloat())

            if (x2 <= x1 || y2 <= y1) continue  // Invalid box

            results.add(Detection(x1, y1, x2, y2, maxScore, maxCls.coerceIn(0, numClasses - 1)))
        }

        val filtered = nonMaxSuppression(results, iouThreshold)
        Log.d("YOLO", "Detections after NMS: ${filtered.size}")
        return filtered
    }
    // Standard greedy NMS
    private fun nonMaxSuppression(dets: List<Detection>, iouThresh: Float = 0.45f): List<Detection> {
        val out = mutableListOf<Detection>()
        val sorted = dets.sortedByDescending { it.score }.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            out.add(best)

            val it = sorted.iterator()
            while (it.hasNext()) {
                val other = it.next()
                if (iou(best, other) > iouThresh) {
                    it.remove()
                }
            }
        }
        return out
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)

        val interW = max(0f, x2 - x1)
        val interH = max(0f, y2 - y1)
        val interArea = interW * interH
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val denom = areaA + areaB - interArea
        return if (denom <= 0f) 0f else interArea / denom
    }

    /**
     * Convenience: return top-1 label or empty string
     */
    fun predictLabel(bitmap: Bitmap): String {
        val dets = predict(bitmap)
        if (dets.isEmpty()) return ""
        val top = dets.maxByOrNull { it.score } ?: return ""
        return labels.getOrNull(top.cls) ?: ""
    }
}

// Data class
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val cls: Int
)
