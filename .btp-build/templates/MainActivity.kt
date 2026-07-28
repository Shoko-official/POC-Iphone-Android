package fr.controlebtp.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var bridge: BtpJavascriptBridge
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingPdfName: String = "rapport-controle-btp.pdf"
    private var pendingPdfBytes: ByteArray? = null

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingWebPermission
        pendingWebPermission = null
        if (granted) {
            request?.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            request?.deny()
            bridge.notifyError("Autorisez la caméra pour scanner les Cartes BTP.")
        }
    }

    private val chooseFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        if (result.resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val intent = result.data
        val values = when {
            intent?.clipData != null -> Array(intent.clipData!!.itemCount) { index -> intent.clipData!!.getItemAt(index).uri }
            intent?.data != null -> arrayOf(intent.data!!)
            else -> null
        }
        callback.onReceiveValue(values)
    }

    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = pendingPdfBytes
        pendingPdfBytes = null
        if (uri == null || bytes == null) {
            bridge.notifyPdfSaved(false)
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: error("Impossible d’ouvrir le fichier de destination.")
        }.onSuccess {
            bridge.notifyPdfSaved(true)
        }.onFailure {
            bridge.notifyError(it.message ?: "Impossible d’enregistrer le PDF.")
            bridge.notifyPdfSaved(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersiveMode()

        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.loadsImagesAutomatically = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.userAgentString = "${settings.userAgentString} ControleBTP/1.0"
        }
        setContentView(webView)

        bridge = BtpJavascriptBridge(
            activity = this,
            webView = webView,
            onSavePdf = ::requestPdfSave,
        )
        webView.addJavascriptInterface(bridge, "AndroidBtp")
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                request?.url?.let(assetLoader::shouldInterceptRequest)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "file" || url.scheme == "blob" || url.scheme == "data") return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                }.getOrDefault(false)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!wantsCamera) {
                        request.deny()
                        return@runOnUiThread
                    }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingWebPermission?.deny()
                        pendingWebPermission = request
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?: arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv",
                        "text/plain",
                    )
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (acceptTypes.size == 1) acceptTypes.first() else "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                chooseFile.launch(intent)
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.d(
                    "ControleBTP-Web",
                    "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                )
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.__androidBack ? window.__androidBack() : false") { handled ->
                    if (handled != "true") finish()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://appassets.androidplatform.net/assets/btp/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        configureImmersiveMode()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pendingWebPermission?.deny()
        pendingWebPermission = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        bridge.close()
        webView.removeJavascriptInterface("AndroidBtp")
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
        )
    }

    private fun requestPdfSave(fileName: String, bytes: ByteArray) {
        runOnUiThread {
            pendingPdfName = fileName.ifBlank { "rapport-controle-btp.pdf" }
            pendingPdfBytes = bytes
            createPdf.launch(pendingPdfName)
        }
    }

    private fun configureImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
