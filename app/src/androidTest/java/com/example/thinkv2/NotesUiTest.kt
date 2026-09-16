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
        awaitText("还没有笔记，写下第一个想法吧。")
        node("打开或新建笔记后可使用离线语音输入。").assertExists()
        node("新增文字").performClick()
        node("正文").performTextInput("合成验收记录。测试搜索与恢复。")
        createCategory("未分类","合成分类")
        awaitText("草稿已保存在本机，尚未正式保存")
        ui.activityRule.scenario.recreate()
        node("保存").performClick()
        awaitText("已保存 · 1 条")
        node("搜索标题或正文").performTextInput("恢复")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        awaitText("合成验收记录")
        node("合成验收记录").performClick()
        awaitText("正在查看已正式保存的版本")
        node("保存").performClick()
        awaitText("已保存 · 1 条")
        node("清空").performClick()
        node("合成验收记录").performClick()
        awaitText("正文")
        node("正文").performTextReplacement("更新后的合成文字。")
        createCategory("合成分类","已纠正分类")
        node("保存").performClick()
        awaitText("更新后的合成文字")
        node("搜索标题或正文").performTextInput("恢复")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        awaitText("已保存 · 0 条")
        node("清空").performClick()
        awaitText("已保存 · 1 条")

        node("管理分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("改名分类已纠正分类").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("改名分类已纠正分类").performClick()
        node("分类名称").performTextReplacement("改名后的分类")
        node("保存分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("删除分类改名后的分类").fetchSemanticsNodes().isNotEmpty() }
        node("返回笔记").performClick()
        awaitText("更新后的合成文字")
        node("更新后的合成文字").performClick()
        node("分类：改名后的分类").performScrollTo().assertExists()
        node("移入回收站").performScrollTo().performClick()
        node("取消").performClick()
        node("移入回收站").performClick()
        node("确认").performClick()
        awaitText("已保存 · 0 条")
        node("管理分类").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("删除分类改名后的分类").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("删除分类改名后的分类").performClick()
        node("确认删除分类").performClick()
        awaitText("分类已删除，相关笔记和草稿已移到未分类；回收站内容仍保留。")
        node("返回笔记").performClick()
        awaitText("回收站（1）")
        node("回收站（1）").performClick()
        awaitText("原分类已不存在，恢复时将移至未分类。")
        node("恢复笔记").performScrollTo().performClick()
        awaitText("笔记已恢复；原分类已不存在，相关内容已移至未分类。")
        node("返回笔记").performClick()
        awaitText("已保存 · 1 条")
        node("更新后的合成文字").performClick()
        node("分类：未分类").performScrollTo().assertExists()
        node("保存").performClick()
        awaitText("已保存 · 1 条")
    }
    private fun notes()=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as com.example.thinkv2.notes.NotesModel
    private fun node(text: String): SemanticsNodeInteraction {
        val node=ui.onNodeWithText(text)
        if(runCatching { node.assertIsDisplayed() }.isSuccess)return node
        val state=notes().state
        if(state.editor==null) ui.onNodeWithTag(if(state.page==com.example.thinkv2.notes.NotesPage.HOME) "notes-list" else "lifecycle-list").performScrollToNode(hasText(text))
        return node
    }
    private fun awaitText(text: String) {
        ui.waitUntil(10000) { !notes().state.loading && !notes().state.busy }
        node(text).assertExists()
    }
    private fun createCategory(oldName: String,name: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        node("分类：$oldName").performScrollTo()
        ui.waitForIdle()
        node("分类：$oldName").performClick()
        awaitText("新建分类")
        node("新建分类").performClick()
        node("分类名称").performTextInput(name)
        node("保存分类").performClick()
        awaitText("分类：$name")
    }
}
