package io.github.lijiahua1024.dsnative

import android.app.Application
import io.github.lijiahua1024.dsnative.api.ApiOrigin
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.preloadVectorIcons
import io.github.lijiahua1024.dsnative.ui.session.prewarmMarkdownParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeepSeekApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var settings: SettingsStore
        private set
    lateinit var chatStore: ChatStore
        private set

    override fun onCreate() {
        super.onCreate()
        io.github.lijiahua1024.dsnative.data.CrashLog.install(this)
        ApiOrigin.init(applicationContext)
        settings = SettingsStore(applicationContext)
        chatStore = ChatStore(applicationContext, settings, appScope)
        appScope.launch(Dispatchers.Default) {
            prewarmMarkdownParser()
            preloadVectorIcons(
                applicationContext,
                R.drawable.ic_chevron_right,
                R.drawable.ic_data,
                R.drawable.ic_globe_lang,
                R.drawable.ic_light,
                R.drawable.ic_moon,
                R.drawable.ic_font_size,
                R.drawable.ic_wand,
                R.drawable.ic_sound,
                R.drawable.ic_sound_muted,
                R.drawable.ic_info_circle,
                R.drawable.ic_doc_edit,
                R.drawable.ic_copy,
                R.drawable.ic_thumb_up,
                R.drawable.ic_thumb_up_filled,
                R.drawable.ic_thumb_down,
                R.drawable.ic_thumb_down_filled,
                R.drawable.ic_speaker,
                R.drawable.ic_refresh,
                R.drawable.ic_share,
                R.drawable.ic_ellipsis,
                R.drawable.ic_sidebar_panel,
                R.drawable.ic_new_chat,
                R.drawable.ic_close,
                R.drawable.ic_search,
                R.drawable.ic_select_chats,
                R.drawable.ic_pin,
                R.drawable.ic_trash,
                R.drawable.ic_chat_item,
                R.drawable.ic_warning,
                R.drawable.ic_think,
                R.drawable.ic_globe,
                R.drawable.ic_add_circle,
                R.drawable.ic_voice_circle,
                R.drawable.ic_send,
                R.drawable.ic_stop,
                R.drawable.whale,
            )
        }
    }
}
