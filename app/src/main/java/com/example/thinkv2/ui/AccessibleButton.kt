package com.example.thinkv2.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString

/** Keep label, role, keyboard focus and action on one platform node. */
internal fun Modifier.accessibleButton(label: String,enabled: Boolean=true,action: ()->Unit)=composed {
    var inputFocused by remember { mutableStateOf(false) }
    val requester=remember { FocusRequester() }
    focusProperties { canFocus=enabled }.onFocusChanged { inputFocused=it.isFocused }.focusRequester(requester).clearAndSetSemantics {
        text=AnnotatedString(label);role=Role.Button;focused=inputFocused
        if(!enabled) disabled() else {
            onClick(label) { action();true }
            requestFocus { requester.requestFocus() }
        }
    }
}
