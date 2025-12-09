package com.example.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class YoloManager(private val context: Context) {

    private val inputSize = 640  // kích thước model yêu cầu
    private lateinit var interpreter: Interpreter
    private var isLoaded = false

    // Chú ý: chỉnh labels tương ứng dataset của bạn
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

    // ------------------------------
    // Load TFLite Model
    // ------------------------------
    private fun loadModel() {
        try {
            val afd = context.assets.openFd("best_2_float32.tflite")
            val inputStream = FileInputStream(afd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = afd.startOffset
            val declaredLength = afd.declaredLength

            val modelBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                startOffset,
                declaredLength
            )

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }

            interpreter = Interpreter(modelBuffer, options)
            isLoaded = true

        } catch (e: Exception) {
            e.printStackTrace()
            isLoaded = false
        }
    }

    // ------------------------------
    // Public API: Predict
    // ------------------------------
    fun predict(bitmap: Bitmap): List<Detection> {
        if (!isLoaded) return emptyList()

        // Resize input to model size
        val bmp = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Prepare input as 4D float array [1][H][W][3]
        val inputTensor = Array(1) {
            Array(inputSize) {
                Array(inputSize) {
                    FloatArray(3)
                }
            }
        }

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bmp.getPixel(x, y)
                inputTensor[0][y][x][0] = Color.red(pixel) / 255f
                inputTensor[0][y][x][1] = Color.green(pixel) / 255f
                inputTensor[0][y][x][2] = Color.blue(pixel) / 255f
            }
        }

        // Model trả về shape [1, 20, 8400] theo log của bạn
        val channels = 20
        val numBoxes = 8400

        val output = Array(1) { Array(channels) { FloatArray(numBoxes) } }

        // Chạy inference
        try {
            interpreter.run(inputTensor, output)
            val outputTensorShape = interpreter.getOutputTensor(0).shape()
            Log.d("YOLO", "Output shape = ${outputTensorShape.contentToString()}")
            val metadata = interpreter.getOutputTensor(0).shape()
            Log.d("YOLO", "Meta shape = ${metadata.contentToString()}")
            val out = outputTensorShape[1]   // 20
            val numClasses = out - 5
            Log.d("YOLO", "Num classes detected = $numClasses")
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        // Post-process: parse output -> detections
        return postProcessFromChannels(output, inputSize)
    }

    /**
     * Parse output of shape [1, channels, numBoxes]
     * Assumed channel order:
     * 0: cx (normalized 0..1)
     * 1: cy
     * 2: w
     * 3: h
     * 4: objectness
     * 5..(channels-1): class scores (per-class)
     */
    private fun postProcessFromChannels(output: Array<Array<FloatArray>>, modelInputSize: Int): List<Detection> {
        val results = mutableListOf<Detection>()

        val channels = output[0].size
        val numBoxes = output[0][0].size

        // For each box index
        for (i in 0 until numBoxes) {
            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]
            val obj = output[0][4][i]

            // Find best class
            var bestClass = -1
            var bestClassScore = 0f
            for (c in 5 until channels) {
                val score = output[0][c][i]
                if (score > bestClassScore) {
                    bestClassScore = score
                    bestClass = c - 5
                }
            }

            val finalScore = obj * bestClassScore

            if (finalScore > scoreThreshold && bestClass >= 0) {
                // Convert normalized cx,cy,w,h -> absolute pixel coordinates on modelInputSize
                val boxCx = cx * modelInputSize
                val boxCy = cy * modelInputSize
                val boxW = w * modelInputSize
                val boxH = h * modelInputSize

                val x1 = (boxCx - boxW / 2f).coerceAtLeast(0f)
                val y1 = (boxCy - boxH / 2f).coerceAtLeast(0f)
                val x2 = (boxCx + boxW / 2f).coerceAtMost(modelInputSize.toFloat())
                val y2 = (boxCy + boxH / 2f).coerceAtMost(modelInputSize.toFloat())

                val clsIdx = bestClass.coerceIn(0, labels.size - 1)
                results.add(Detection(x1, y1, x2, y2, finalScore, clsIdx))
            }
        }

        // Apply NMS on results (coordinates currently in model input pixels)
        val final = nonMaxSuppression(results, iouThreshold)

        // Optionally: if you want coordinates relative to original image,
        // caller can map by scaling factor = originalSize / inputSize.
        return final
    }

    // ------------------------------
    // Non-Max Suppression (NMS)
    // ------------------------------
    private fun nonMaxSuppression(dets: List<Detection>, iouThresh: Float = 0.45f): List<Detection> {
        val finalDetections = mutableListOf<Detection>()
        val sorted = dets.sortedByDescending { it.score }.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            finalDetections.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (iou(best, other) > iouThresh) {
                    iterator.remove()
                }
            }
        }
        return finalDetections
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)

        val interArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)

        return if (areaA + areaB - interArea <= 0f) 0f else interArea / (areaA + areaB - interArea)
    }
}

// ------------------------------
// Detection Data Class
// ------------------------------
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val cls: Int
)
