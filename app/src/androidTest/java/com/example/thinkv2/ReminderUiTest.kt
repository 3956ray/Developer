package com.example.thinkv2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun shown(text: String) { ui.waitUntil(15000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun weeklyConfigurationValidationBlockedStatusAndDisable() {
        shown("还没有笔记，写下第一个想法吧。")
        ui.onNodeWithText("新增文字").performClick()
        ui.onNodeWithText("正文").performTextInput("提醒界面合成笔记。保留正文。")
        ui.onNodeWithText("保存").performClick();shown("提醒界面合成笔记")
        ui.onNodeWithText("提醒界面合成笔记").performClick();shown("正文")
        ui.onNodeWithText("设置笔记提醒").performScrollTo().performClick();shown("每周（可多选）")
        ui.onNodeWithText("每周（可多选）").performScrollTo().performClick()
        ui.onNodeWithText("保存提醒规则").performScrollTo().performClick()
        shown("请填写有效日期 YYYY-MM-DD、时间 HH:mm；每周至少选择一天。")
        ui.onNodeWithText("每周一").performScrollTo().performClick()
        ui.onNodeWithText("每周五").performScrollTo().performClick()
        ui.onNodeWithText("保存提醒规则").performScrollTo().performClick()
        shown("规则已保存。提醒未启用：通知权限或频道关闭")
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("修改或重新设置"))
        ui.onNodeWithText("修改或重新设置").performClick();shown("每周一")
        ui.onNodeWithText("每周一").performScrollTo().assertIsSelected()
        ui.onNodeWithText("每周五").performScrollTo().assertIsSelected()
        ui.onNode(isToggleable()).performScrollTo().performClick()
        ui.onNodeWithText("保存提醒规则").performScrollTo().performClick();shown("规则已保存。已关闭")
        ui.onNodeWithText("返回笔记").performClick();shown("正文")
        ui.onNodeWithText("正文").assertTextContains("提醒界面合成笔记。保留正文。")
    }
}
