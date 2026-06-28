package chat.simplex.common.views.chatlist

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.chatModel
import chat.simplex.common.views.helpers.ChatInfoImage
import chat.simplex.common.views.helpers.withBGApi

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QuickSwitcher(onDismiss: () -> Unit) {
  var query by remember { mutableStateOf("") }
  var selectedIndex by remember { mutableStateOf(0) }
  val focusRequester = remember { FocusRequester() }

  val filteredChats by remember {
    derivedStateOf {
      val q = query.trim()
      chatModel.chats.value
        .filter { chat: Chat ->
          chat.chatInfo !is ChatInfo.ContactRequest &&
          (q.isEmpty() || chat.chatInfo.chatViewName.contains(q, ignoreCase = true))
        }
        .take(9)
    }
  }

  LaunchedEffect(filteredChats) {
    selectedIndex = 0
  }

  fun openSelected() {
    val chat = filteredChats.getOrNull(selectedIndex) ?: return
    withBGApi { openChat(null, chat.remoteHostId, chat.chatInfo) }
    onDismiss()
  }

  Box(
    Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.45f))
      .clickable(onClick = onDismiss),
    contentAlignment = Alignment.TopCenter
  ) {
    Surface(
      modifier = Modifier
        .padding(top = 80.dp)
        .widthIn(min = 360.dp, max = 520.dp)
        .fillMaxWidth(0.55f)
        .shadow(24.dp, RoundedCornerShape(14.dp))
        .clickable(onClick = {}),
      shape = RoundedCornerShape(14.dp),
      elevation = 0.dp,
      color = MaterialTheme.colors.surface
    ) {
      Column {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it; selectedIndex = 0 },
          placeholder = { Text("Jump to chat…", color = MaterialTheme.colors.secondary) },
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { e ->
              if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
              when (e.key) {
                Key.DirectionDown -> { selectedIndex = (selectedIndex + 1).coerceAtMost(filteredChats.size - 1); true }
                Key.DirectionUp   -> { selectedIndex = (selectedIndex - 1).coerceAtLeast(0); true }
                Key.Enter         -> { openSelected(); true }
                Key.Escape        -> { onDismiss(); true }
                else -> false
              }
            },
          singleLine = true,
          colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent
          )
        )

        if (filteredChats.isNotEmpty()) {
          Divider()
          filteredChats.forEachIndexed { i, chat ->
            val isSelected = i == selectedIndex
            Row(
              Modifier
                .fillMaxWidth()
                .background(if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                .clickable { selectedIndex = i; openSelected() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              ChatInfoImage(chat.chatInfo, size = 34.dp)
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text(
                  chat.chatInfo.chatViewName,
                  fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                  fontSize = 14.sp,
                  maxLines = 1
                )
                if (chat.chatInfo is ChatInfo.Group) {
                  Text(
                    "Group",
                    color = MaterialTheme.colors.secondary,
                    fontSize = 11.sp,
                    maxLines = 1
                  )
                }
              }
              if (chat.chatStats.unreadCount > 0) {
                Text(
                  unreadCountStr(chat.chatStats.unreadCount),
                  color = if (isSelected) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Medium
                )
              }
            }
          }
          Spacer(Modifier.height(4.dp))
        } else if (query.isNotBlank()) {
          Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("No chats found", color = MaterialTheme.colors.secondary, fontSize = 13.sp)
          }
        }
      }
    }

    LaunchedEffect(Unit) {
      focusRequester.requestFocus()
    }
  }
}
