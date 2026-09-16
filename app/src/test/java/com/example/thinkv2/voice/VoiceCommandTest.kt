package com.example.thinkv2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun exactWhitelistAcceptsOnlyWholePhrasesAndVoskWordBoundaries() {
        for(command in VoiceCommand.entries) {
            assertEquals(command,VoiceCommand.parse(command.phrase))
            assertEquals(command,VoiceCommand.parse(" \t"+command.phrase.toList().joinToString(" ")+"\n"))
        }
        for(text in listOf(""," ","请新建笔记","新建笔记然后保存当前草稿","不要保存当前草稿","取消","保存草稿","删除笔记","新建笔记。","x".repeat(129))) assertNull(text,VoiceCommand.parse(text))
    }
    @Test fun commandParsingNeverMutatesPlainDictationText() {
        val body="此前正文：";val text="新建笔记 保存当前草稿 取消本次输入"
        assertEquals(body+text,VoiceText.insert(body,body.length,text))
        assertEquals(VoiceCommand.SAVE,VoiceCommand.parse("保存 当前 草稿"))
        assertNull(VoiceCommand.parse("这段文字里提到了保存当前草稿"))
    }
}
