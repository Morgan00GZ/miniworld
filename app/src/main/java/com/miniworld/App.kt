package com.miniworld

import android.app.Application
import com.miniworld.shizuku.AppHolder
import com.miniworld.shizuku.ShizukuGateway
import com.miniworld.system.Diag

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppHolder.application = this
        // 落盘日志要最早初始化：这台机器 logcat 读不到，diag.log 是唯一的现场来源。
        Diag.init(this)
        ShizukuGateway.init(this)
    }
}
