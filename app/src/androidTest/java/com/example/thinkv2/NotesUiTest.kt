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
        ui.onNodeWithText("语音输入暂不可用").assertIsNotEnabled()
        ui.onNodeWithText("新增文字").performClick()
        ui.onNodeWithText("正文").performTextInput("合成验收记录。测试搜索与恢复。")
        ui.onNodeWithText("分类（可修改，留空为未分类）").performScrollTo().performTextInput("合成分类")
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
        ui.onNodeWithText("分类（可修改，留空为未分类）").performScrollTo().performTextReplacement("已纠正分类")
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("更新后的合成文字").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("搜索标题或正文").performTextInput("恢复")
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 0 条").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("清空").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("已保存 · 1 条").fetchSemanticsNodes().isNotEmpty() }
    }
}
