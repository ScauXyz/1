package dev.restricted.backup

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var ownerUntil by mutableLongStateOf(0L)
    private var screen by mutableStateOf("home")
    private var message by mutableStateOf("")
    private var selected by mutableStateOf<List<Uri>>(emptyList())
    private var names by mutableStateOf<List<String>>(emptyList())
    private var confirm by mutableStateOf(false)
    private val settings by lazy { getSharedPreferences("quark_owner", Context.MODE_PRIVATE) }
    private var bound by mutableStateOf<String?>(null)
    private var folderDraft by mutableStateOf("")
    private val authenticate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            ownerUntil = SystemClock.elapsedRealtime() + 5 * 60_000
            screen = "settings"
        } else message = "设备认证已取消，设置保持锁定。"
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selected = uris.filter { it.scheme == "content" }.distinct()
        names = selected.map { uri ->
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else "所选文件"
                } ?: "所选文件"
            } catch (_: Exception) { "无法读取的文件" }
        }
        confirm = selected.isNotEmpty()
    }
    private fun owner() = SystemClock.elapsedRealtime() < ownerUntil
    private fun unlock() {
        val manager = getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent = manager.createConfirmDeviceCredentialIntent("所有者设置", "请验证设备锁屏凭据")
        if (!manager.isDeviceSecure || intent == null) {
            message = "请先在 Android 设置中启用安全锁屏。"
        } else {
            try { authenticate.launch(intent) }
            catch (_: Exception) { message = "设备认证不可用，无法打开设置。" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bound = settings.getString("folder", null)?.takeIf(QuarkPolicy::binding)
        folderDraft = bound.orEmpty()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176B5B), background = Color(0xFFF6F8F7))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("文件空间", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                            TextButton(onClick = { screen = "home"; selected = emptyList(); ownerUntil = 0 }) { Text("首页") }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (screen) {
                            "home" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("选择文件，安心交接", style = MaterialTheme.typography.titleLarge)
                                Text("使用夸克官方网页保存的登录会话。请由所有者先登录并绑定目标文件夹。")
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(if (bound == null) "尚未绑定文件夹" else "已绑定所有者指定文件夹")
                                        Button(onClick = {
                                            try { picker.launch(arrayOf("*/*")) }
                                            catch (_: Exception) { message = "系统文件选择器不可用。" }
                                        }, enabled = bound != null, modifier = Modifier.fillMaxWidth()) { Text("上传文件") }
                                        OutlinedButton(onClick = { screen = "view" }, enabled = bound != null,
                                            modifier = Modifier.fillMaxWidth()) { Text("查看文件") }
                                    }
                                }
                                Text("网页限制属于软隔离，不能替代服务端权限控制。登录账号应仅包含可向此设备开放的文件。",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = ::unlock) { Text("所有者设置 · 设备认证") }
                            }
                            "settings" -> {
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("所有者设置", style = MaterialTheme.typography.titleLarge)
                                    Text("认证有效期 5 分钟。官方网页支持情况需在此设备手动验证；不会读取 Cookie 或保存密码。")
                                    Button(onClick = {
                                        if (owner()) screen = "login" else { screen = "home"; message = "认证已过期，请重新验证。" }
                                    }) { Text("打开官方网页人工登录") }
                                    OutlinedTextField(value = folderDraft, onValueChange = { folderDraft = it },
                                        label = { Text("固定文件夹完整网址") }, modifier = Modifier.fillMaxWidth())
                                    Text("接受格式：https://pan.quark.cn/list#/list/all/文件夹ID。请从官方网页确认具体文件夹，根目录不接受。")
                                    Button(onClick = {
                                        if (!owner()) { screen = "home"; message = "认证已过期。" }
                                        else if (!QuarkPolicy.binding(folderDraft.trim())) message = "网址格式不符合固定文件夹规则，未保存。"
                                        else if (settings.edit().putString("folder", folderDraft.trim()).commit()) {
                                            bound = folderDraft.trim(); ownerUntil = 0; screen = "home"; message = "已保存文件夹绑定，尚未验证网页可访问性。"
                                        } else message = "设置保存失败，请重试。"
                                    }) { Text("保存绑定并锁定") }
                                    OutlinedButton(onClick = {
                                        if (owner()) {
                                            if (settings.edit().remove("folder").commit()) {
                                                bound = null; folderDraft = ""
                                                ownerUntil = 0; screen = "home"; message = "已解除文件夹绑定。WebView 登录会话仍由系统组件管理。"
                                            } else message = "设置保存失败，请重试。"
                                        } else { screen = "home"; message = "认证已过期。" }
                                    }) { Text("解除绑定") }
                                }
                            }
                            else -> {
                                val login = screen == "login"
                                val upload = screen == "upload"
                                Text(if (login) "官方网页登录 · 仅所有者" else if (upload) "等待官方网页请求文件" else "已绑定文件夹",
                                    style = MaterialTheme.typography.titleMedium)
                                Text(if (upload) "请在官方网页点击上传。文件交接后，请在网页核实最终结果。" else "仅允许当前模式的官方页面导航。", style = MaterialTheme.typography.bodySmall)
                                QuarkPane(
                                    initialUrl = if (login) QuarkPolicy.LOGIN else bound.orEmpty(),
                                    binding = bound, ownerMode = login, ownerAuthorized = ::owner,
                                    files = if (upload) selected else emptyList(),
                                    onHandoff = { selected = emptyList() },
                                    onFolderSelected = { url ->
                                        if (owner()) { folderDraft = url; screen = "settings" }
                                        else { screen = "home"; message = "认证已过期。" }
                                    },
                                    onMessage = { message = it }, modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (message.isNotBlank()) {
                            Text(message, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { message = "" }) { Text("关闭提示") }
                        }
                    }
                    if (confirm) AlertDialog(onDismissRequest = { confirm = false; selected = emptyList() },
                        title = { Text("确认交接 ${selected.size} 个文件") },
                        text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            names.forEach { Text(it) }
                            Text("仅在官方网页请求文件时交接。此操作不表示上传成功；原始文件不会删除。")
                        } },
                        confirmButton = { TextButton(onClick = {
                            confirm = false
                            if (bound != null) screen = "upload" else { selected = emptyList(); message = "请先绑定文件夹。" }
                        }) { Text("确认并打开网页") } },
                        dismissButton = { TextButton(onClick = { confirm = false; selected = emptyList() }) { Text("取消") } })
                }
            }
        }
    }
    override fun onStop() {
        super.onStop()
        ownerUntil = 0
        if (screen == "login" || screen == "settings") screen = "home"
    }
}
