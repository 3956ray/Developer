package com.example.thinkv2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on the fresh synthetic emulator specified in the verification report. */
@RunWith(AndroidJUnit4::class)
class NotesUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun textDraftSearchAndRecreation() {
        ui.waitUntil(10000) { ui.onAllNodesWithText("还没有笔记，写下第一个想法吧。").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("打开或新建笔记后可使用离线语音输入。").assertExists()
        ui.onNodeWithText("新增文字").performClick()
        ui.onNodeWithText("正文").performTextInput("合成验收记录。测试搜索与恢复。")
        createCategory("未分类","合成分类")
        ui.waitUntil(10000) { ui.onAllNodesWithText("草稿已保存在本机，尚未正式保存").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 1 条").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("搜索标题或正文").performTextInput("恢复")
        ui.waitUntil(10000) { ui.onAllNodesWithText("合成验收记录").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("合成验收记录").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("正在查看已正式保存的版本").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 1 条").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("清空").performClick()
        ui.onNodeWithText("合成验收记录").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("正文").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("正文").performTextReplacement("更新后的合成文字。")
        createCategory("合成分类","已纠正分类")
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("更新后的合成文字").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("搜索标题或正文").performTextInput("恢复")
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 0 条").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("清空").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 1 条").fetchSemanticsNodes().isNotEmpty() }

        ui.onNodeWithText("管理分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("改名分类已纠正分类").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("改名分类已纠正分类").performClick()
        ui.onNodeWithText("分类名称").performTextReplacement("改名后的分类")
        ui.onNodeWithText("保存分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("删除分类改名后的分类").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("返回笔记").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("更新后的合成文字").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("更新后的合成文字").performClick()
        ui.onNodeWithText("分类：改名后的分类").performScrollTo().assertExists()
        ui.onNodeWithText("移入回收站").performScrollTo().performClick()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("移入回收站").performClick()
        ui.onNodeWithText("确认").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 0 条").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("管理分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("删除分类改名后的分类").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("删除分类改名后的分类").performClick()
        ui.onNodeWithText("确认删除分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("分类已删除，相关笔记和草稿已移到未分类；回收站内容仍保留。").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("返回笔记").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("回收站（1）").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("回收站（1）").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("原分类已不存在，恢复时将移至未分类。").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("恢复笔记").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("笔记已恢复；原分类已不存在，相关内容已移至未分类。").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("返回笔记").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 1 条").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("更新后的合成文字").performClick()
        ui.onNodeWithText("分类：未分类").performScrollTo().assertExists()
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 1 条").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun createCategory(oldName: String,name: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithText("分类：$oldName").performScrollTo()
        ui.waitForIdle()
        ui.onNodeWithText("分类：$oldName").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("新建分类").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("新建分类").performClick()
        ui.onNodeWithText("分类名称").performTextInput(name)
        ui.onNodeWithText("保存分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("分类：$name").fetchSemanticsNodes().isNotEmpty() }
    }
}
