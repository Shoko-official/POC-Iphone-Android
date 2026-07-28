package fr.controlebtp.app

import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.controlebtp.app.core.BtpCardParser
import fr.controlebtp.app.core.QrHashExtractor
import fr.controlebtp.app.data.CibtpValidationClient
import org.json.JSONObject
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BtpJavascriptBridge(
    activity: MainActivity,
    webView: WebView,
    private val onSavePdf: (String, ByteArray) -> Unit,
) : Closeable {
    private val activityRef = WeakReference(activity)
    private val webViewRef = WeakReference(webView)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private val validationClient = CibtpValidationClient()

    @JavascriptInterface
    fun analyzeFrame(dataUrl: String) {
        if (!processing.compareAndSet(false, true)) return
        worker.execute {
            try {
                val payload = dataUrl.substringAfter(',', dataUrl)
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Image caméra illisible.")
                val image = InputImage.fromBitmap(bitmap, 0)
                val textTask = textRecognizer.process(image)
                val barcodeTask = barcodeScanner.process(image)
                Tasks.await(Tasks.whenAllComplete(textTask, barcodeTask))

                val rawText = if (textTask.isSuccessful) textTask.result?.text.orEmpty() else ""
                val barcodes = if (barcodeTask.isSuccessful) barcodeTask.result.orEmpty() else emptyList()
                val rawQr = barcodes.mapNotNull(Barcode::getRawValue)
                    .firstOrNull { QrHashExtractor.extract(it).isNotBlank() }
                    .orEmpty()
                val qrHash = QrHashExtractor.extract(rawQr)
                val parsed = BtpCardParser.parse(rawText)
                val validation = if (qrHash.isNotBlank()) validationClient.validate(qrHash) else null

                val json = JSONObject().apply {
                    put("name", parsed.lastName)
                    put("firstName", parsed.firstName)
                    put("company", parsed.company)
                    put("cardNo", parsed.cardNumber)
                    put("qrHash", qrHash)
                    put("cardIdentifier", validation?.cardIdentifier.orEmpty())
                    put("validity", validation?.validity?.name ?: "UNKNOWN")
                    put("confidence", parsed.confidence.toDouble())
                }
                if (parsed.hasIdentity) {
                    evaluate("window.__onNativeBtpRecognition(${JSONObject.quote(json.toString())})")
                } else {
                    evaluate("window.__onNativeBtpError(${JSONObject.quote("Maintenez la carte immobile et rapprochez-la du cadre.")})")
                }
            } catch (error: Throwable) {
                notifyError(error.message ?: "Lecture OCR momentanément indisponible.")
            } finally {
                processing.set(false)
            }
        }
    }

    @JavascriptInterface
    fun savePdf(fileName: String, base64Payload: String) {
        worker.execute {
            runCatching {
                val bytes = Base64.decode(base64Payload, Base64.DEFAULT)
                require(bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") {
                    "Le rapport généré n’est pas un PDF valide."
                }
                onSavePdf(fileName, bytes)
            }.onFailure { notifyError(it.message ?: "Impossible de préparer le PDF.") }
        }
    }

    @JavascriptInterface
    fun openSettings() {
        activityRef.get()?.runOnUiThread { activityRef.get()?.openAppSettings() }
    }

    fun notifyError(message: String) {
        evaluate("window.__onNativeBtpError(${JSONObject.quote(message)})")
    }

    fun notifyPdfSaved(success: Boolean) {
        evaluate("window.__onNativePdfSaved(${if (success) "true" else "false"})")
    }

    private fun evaluate(script: String) {
        val webView = webViewRef.get() ?: return
        webView.post { webView.evaluateJavascript(script, null) }
    }

    override fun close() {
        worker.shutdownNow()
        textRecognizer.close()
        barcodeScanner.close()
    }
}
