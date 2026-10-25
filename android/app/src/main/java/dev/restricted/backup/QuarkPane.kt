package dev.restricted.backup

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled") // Required by the official SPA; no native JS bridge is installed.
@Composable
fun QuarkPane(
    initialUrl: String, binding: String?, ownerMode: Boolean, ownerAuthorized: () -> Boolean,
    files: List<Uri>, onHandoff: () -> Unit, onFolderSelected: (String) -> Unit, onMessage: (String) -> Unit, modifier: Modifier = Modifier
) {
    if (runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() == null) {
        Text("系统 WebView 不可用，请由所有者在设备上启用或更新系统 WebView 后重试。")
        return
    }
    val currentFiles by rememberUpdatedState(files)
    val authorized by rememberUpdatedState(ownerAuthorized)
    val handoff by rememberUpdatedState(onHandoff)
    val report by rememberUpdatedState(onMessage)
    var loading by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    fun allowed(url: String) = QuarkPolicy.allows(url, binding, ownerMode && authorized()) &&
        (!ownerMode || authorized())
    LaunchedEffect(ownerMode) {
        if (ownerMode) while (true) {
            delay(1000)
            if (!authorized()) {
                web?.stopLoading(); web?.loadUrl("about:blank")
                report("所有者认证已过期，请回到首页重新认证。")
                break
            }
        }
    }
    Column(modifier) {
        TextButton(onClick = {
            if (allowed(initialUrl)) web?.loadUrl(initialUrl)
            else report("当前地址未获授权，请由所有者检查绑定或重新认证。")
        }) { Text("重新打开当前页面") }
        if (ownerMode) TextButton(onClick = {
            val url = web?.url.orEmpty()
            if (authorized() && QuarkPolicy.binding(url)) onFolderSelected(url)
            else report("请先在官方网页进入具体文件夹；根目录或失效认证不能用于绑定。")
        }) { Text("使用当前文件夹 · 返回设置确认") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                web = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true // SAF content URIs are delivered only by the chooser callback.
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.safeBrowsingEnabled = true
                setDownloadListener { _, _, _, _, _ -> report("此客户端未开放网页下载或外部跳转。") }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val permit = request.isForMainFrame && allowed(request.url.toString())
                        if (!permit) report("已阻止超出当前页面范围的导航。登录失效时请联系所有者。")
                        return !permit
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // Network requests by the official SPA remain its responsibility; this is not an ACL.
                        if (request.isForMainFrame && (!QuarkPolicy.allowsDocument(
                                request.url.toString(), binding, ownerMode && authorized()) || (ownerMode && !authorized()))) {
                            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(),
                                ByteArrayInputStream("Navigation blocked".toByteArray()))
                        }
                        return null
                    }
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        loading = url != "about:blank"
                        if (url != "about:blank" && !allowed(url)) {
                            view.stopLoading(); view.loadUrl("about:blank")
                            report("页面已离开绑定范围，已关闭显示。")
                        }
                    }
                    override fun onPageFinished(view: WebView, url: String) { loading = false }
                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        // Includes SPA fragment/history changes, which do not trigger URL loading callbacks.
                        if (url != "about:blank" && !allowed(url)) {
                            view.stopLoading(); view.loadUrl("about:blank")
                            report("已拦截网页内部的范围外导航。请重新打开绑定页面。")
                        }
                        view.clearHistory()
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        loading = false
                        report("网页证书验证失败，连接已取消。")
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) { loading = false; report("网页加载失败，请检查网络后重试。") }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) report("官方网页返回错误 ${errorResponse.statusCode}，尚未完成操作。")
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        report("网页进程已停止，请返回首页后重新打开。")
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.destroy(); web = null
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    private var consumed = false
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                        resultMsg: android.os.Message): Boolean {
                        report("已阻止新窗口，请在当前官方页面完成操作。")
                        return false
                    }
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                        params: FileChooserParams): Boolean {
                        val pending = currentFiles
                        val valid = !consumed && !ownerMode && allowed(view.url.orEmpty()) && pending.isNotEmpty() &&
                            !params.isCaptureEnabled && params.mode in listOf(FileChooserParams.MODE_OPEN, FileChooserParams.MODE_OPEN_MULTIPLE) &&
                            (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE || pending.size == 1)
                        val readable = try {
                            valid && pending.all { uri ->
                                uri.scheme == "content" && QuarkPolicy.accepts(
                                    context.contentResolver.getType(uri) ?: "application/octet-stream", params.acceptTypes.toList()) &&
                                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
                            }
                        } catch (_: Exception) { false }
                        if (readable) {
                            consumed = true
                            handoff()
                            callback.onReceiveValue(pending.toTypedArray())
                            report("文件已交给官方网页；上传结果未知，请在网页中确认。")
                        } else {
                            callback.onReceiveValue(null)
                            report("未交接文件：请重新选择并确认可读文件，且符合网页要求的数量和类型。")
                        }
                        return true
                    }
                }
                if (allowed(initialUrl)) loadUrl(initialUrl)
                else report("未绑定有效文件夹，或所有者认证已失效。")
            }
        })
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.apply {
                stopLoading()
                webChromeClient = null
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            web = null
        }
    }
}
