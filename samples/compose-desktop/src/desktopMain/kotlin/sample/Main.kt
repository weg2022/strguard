package sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

const val API_ENDPOINT = "https://api.example.com/v2/rakuten-ops"

@Composable
fun App() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("StrGuard Compose Desktop Sample")
            Text("API: $API_ENDPOINT")
            Text("Token: ${ApiClient().fetchToken("sample-user")}")
        }
    }
}

class ApiClient {
    fun fetchToken(user: String): String = "sensitive-token-for-$user-4f8a2c1e"
}

/**
 * 帧合并覆盖:分支汇合两个 Compose 库类型(接口 PopupPositionProvider 与类
 * Alignment.Horizontal),COMPUTE_FRAMES 求公共父类时必须能从依赖 jar 解析
 * 这些类型 —— 正是真实 Compose Desktop 项目曾触发的 NoClassDefFoundError 场景。
 */
fun mergeComposeTypes(flag: Boolean, a: PopupPositionProvider?, b: Alignment.Horizontal?): Any? = if (flag) a else b

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "StrGuard Compose Desktop",
        state = rememberWindowState(width = 480.dp, height = 320.dp),
    ) {
        App()
    }
}
