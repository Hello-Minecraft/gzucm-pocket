package com.gzucm.pocket

import android.app.Application
import android.content.Context
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.SessionStore

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** 极简依赖容器：一个会话存储 + 一个网络栈，够用了。 */
object Graph {

    lateinit var store: SessionStore
        private set

    fun init(context: Context) {
        store = SessionStore(context.applicationContext)
        Net.init(context.applicationContext)
    }
}
