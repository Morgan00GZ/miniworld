package com.miniworld

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import com.miniworld.apps.AppCatalog
import com.miniworld.data.SceneStore
import com.miniworld.ui.WorkbenchRoot

/**
 * 唯一的界面：一屏放下「当前挂机应用 / 启动 / 关闭 / 更换」和三行权限状态。
 *
 * 真正的逻辑都在 system/、window/、service/ 里，这里只负责把状态摆出来。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = SceneStore(this)
        val catalog = AppCatalog(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WorkbenchRoot(activity = this, store = store, catalog = catalog)
                }
            }
        }
    }
}
