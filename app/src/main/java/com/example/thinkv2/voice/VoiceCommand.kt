package com.example.thinkv2.voice

/** Exact local allowlist. Vosk word-boundary whitespace is ignored; no fuzzy matching or embedded phrases. */
enum class VoiceCommand {
    NEW, SAVE, CANCEL;
    val phrase: String get()=when(this) { NEW -> "新建笔记";SAVE -> "保存当前草稿";CANCEL -> "取消本次输入" }
    companion object {
        fun parse(text: String): VoiceCommand? {
            if(text.length>128) return null
            val words=text.filterNot { it.isWhitespace() }
            return entries.firstOrNull { it.phrase==words }
        }
    }
}
data class VoiceCommandPreview(val intent: VoiceCommand,val recognized: String,val anchor: VoiceAnchor,val session: Long)
