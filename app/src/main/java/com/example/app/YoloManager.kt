package com.example.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloManager(private val context: Context) {

    private val inputSize = 640  // chỉnh theo model
    private lateinit var interpreter: Interpreter
    private var isLoaded = false

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
        }
    }

    // ------------------------------
    // Public API: Predict
    // ------------------------------
    fun predict(bitmap: Bitmap): List<Detection> {
        if (!isLoaded) return emptyList()

        val inputTensor = bitmapToInputTensor(bitmap)

        // Output (YOLOv11 thường 8400 predictions, mỗi prediction 6 values)
        val output = Array(1) { Array(8400) { FloatArray(6) } }

        interpreter.run(inputTensor, output)

        return postProcess(output)
    }

    // ------------------------------
    // Convert Bitmap → 1 × 640 × 640 × 3 Float32
    // ------------------------------
    private fun bitmapToInputTensor(src: Bitmap): Array<Array<Array<FloatArray>>> {
        val bmp = Bitmap.createScaledBitmap(src, inputSize, inputSize, true)

        val input = Array(1) {
            Array(inputSize) {
                Array(inputSize) {
                    FloatArray(3)
                }
            }
        }

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bmp.getPixel(x, y)
                input[0][y][x][0] = Color.red(pixel) / 255f
                input[0][y][x][1] = Color.green(pixel) / 255f
                input[0][y][x][2] = Color.blue(pixel) / 255f
            }
        }

        return input
    }

    // ------------------------------
    // Post-process YOLO output
    // ------------------------------
    private fun postProcess(output: Array<Array<FloatArray>>): List<Detection> {
        val result = mutableListOf<Detection>()

        val rows = output[0]

        for (i in rows.indices) {
            val row = rows[i]
            val score = row[4]

            if (score > 0.40f) {  // chỉnh threshold tuỳ ý
                val x1 = row[0]
                val y1 = row[1]
                val x2 = row[2]
                val y2 = row[3]
                val cls = row[5].toInt()

                result.add(
                    Detection(
                        x1, y1, x2, y2,
                        score,
                        cls
                    )
                )
            }
        }

        return nonMaxSuppression(result)
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

        return interArea / (areaA + areaB - interArea)
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
