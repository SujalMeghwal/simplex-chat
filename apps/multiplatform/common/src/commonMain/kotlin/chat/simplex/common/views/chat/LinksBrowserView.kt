package chat.simplex.common.views.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.base64ToBitmap
import chat.simplex.common.platform.chatModel
import chat.simplex.common.views.helpers.ModalManager
import chat.simplex.common.views.newchat.planAndConnect
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.datetime.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Links browser — an improved "filter by link" for a chat.
//
// Privacy contract (this is a privacy tool, so it is enforced, not optional):
//   • WEB links (http/https) are NEVER contacted by this view. We only dedup and
//     drop malformed ones locally. A web server learns nothing until YOU tap Open.
//   • SimpleX links are classified via apiConnectPlan — SimpleX's own connection
//     planner, which runs over your configured SimpleX transport (SOCKS/Tor when
//     enabled). It is plan-only (never auto-connects) and tells us, without extra
//     exposure beyond a normal connect attempt, whether a link is:
//       working / already-joined / your own / connecting / dead.
//   • Nothing here is written to disk. Classification results live in memory only.
// ─────────────────────────────────────────────────────────────────────────────

enum class LinkKind { SimpleX, Web }

enum class LinkStatus {
  Joinable,     // simplex link that still works and you're not in it
  Joined,       // you're already a member / already connected
  Own,          // your own link
  Connecting,   // connection in progress
  Unavailable,  // no relays / update required
  Dead,         // malformed / expired / server rejected
  Checking,     // classification not finished yet
  Web           // web link — not checked by design
}

// One deduplicated link ready to render.
class BrowserLink(
  val displayUrl: String,
  val dedupKey: String,
  val kind: LinkKind,
  val simplexType: SimplexLinkType?,
  val host: String,
  val title: String?,
  val imageBase64: String?,
  val lastSender: String,
  val lastTs: Instant,
  val count: Int,
) {
  var status by mutableStateOf(if (kind == LinkKind.Web) LinkStatus.Web else LinkStatus.Checking)
  // Resolved SimpleX entity ("g:<id>" / "c:<id>") so multiple links to the same
  // group/contact can be merged after classification.
  var entityKey by mutableStateOf<String?>(null)
}

// Result of classifying one SimpleX link.
private class SxResult(val status: LinkStatus, val entityKey: String?)

// In-memory cache so re-opening the browser (or scrolling) never re-probes a link.
// Cleared when the app process dies — never persisted.
private val sxCache = HashMap<String, SxResult>()

@Composable
fun LinksBrowserView(rhId: Long?, chatInfo: ChatInfo, close: () -> Unit) {
  val loading = remember { mutableStateOf(true) }
  val allLinks = remember { mutableStateOf<List<BrowserLink>>(emptyList()) }
  val search = remember { mutableStateOf("") }
  val showHidden = remember { mutableStateOf(false) }
  val sort = remember { mutableStateOf(LinkSort.Newest) }
  val clipboard = LocalClipboardManager.current
  val uriHandler = LocalUriHandler.current

  LaunchedEffect(Unit) {
    val items = loadAllLinkItems(rhId, chatInfo)
    val links = extractAndDedup(items, chatInfo)
    allLinks.value = links
    loading.value = false
    // Classify SimpleX links one at a time (bounded, cache-first) so the UI fills
    // in progressively and we never fan out a burst of network probes.
    val simplex = links.filter { it.kind == LinkKind.SimpleX }
    for (link in simplex) {
      val cached = sxCache[link.dedupKey]
      val res = cached ?: classifySimplex(rhId, link.displayUrl).also { sxCache[link.dedupKey] = it }
      link.status = res.status
      link.entityKey = res.entityKey
    }
  }

  // After classification, merge SimpleX links that resolve to the same group/contact.
  val merged by remember {
    derivedStateOf { mergeByEntity(allLinks.value) }
  }
  val visible by remember {
    derivedStateOf {
      val q = search.value.trim().lowercase()
      merged
        .filter { link ->
          val hiddenStatus = link.status == LinkStatus.Joined || link.status == LinkStatus.Own ||
            link.status == LinkStatus.Dead || link.status == LinkStatus.Unavailable
          if (hiddenStatus && !showHidden.value) return@filter false
          if (q.isEmpty()) true
          else link.host.lowercase().contains(q) ||
            (link.title?.lowercase()?.contains(q) == true) ||
            link.displayUrl.lowercase().contains(q)
        }
        .let { list ->
          when (sort.value) {
            LinkSort.Newest -> list.sortedByDescending { it.lastTs }
            LinkSort.Oldest -> list.sortedBy { it.lastTs }
            LinkSort.MostShared -> list.sortedWith(compareByDescending<BrowserLink> { it.count }.thenByDescending { it.lastTs })
          }
        }
    }
  }
  val simplexLinks = visible.filter { it.kind == LinkKind.SimpleX }
  val webLinks = visible.filter { it.kind == LinkKind.Web }
  val hiddenCount = merged.count {
    it.status == LinkStatus.Joined || it.status == LinkStatus.Own ||
      it.status == LinkStatus.Dead || it.status == LinkStatus.Unavailable
  }

  Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = close) {
        Icon(painterResource(MR.images.ic_arrow_back_ios_new), contentDescription = "Back", tint = MaterialTheme.colors.primary)
      }
      Text("Links", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
      if (!loading.value) {
        Text("  ${merged.size}", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
      }
      Spacer(Modifier.weight(1f))
      SortMenu(sort)
    }
    // Search field
    OutlinedTextField(
      value = search.value,
      onValueChange = { search.value = it },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
      placeholder = { Text("Search host, title or URL") },
      leadingIcon = { Icon(painterResource(MR.images.ic_search), contentDescription = null) },
      singleLine = true,
      keyboardOptions = KeyboardOptions.Default
    )
    // Privacy caption + hidden toggle
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        "Web links are never contacted. SimpleX links checked via your network settings.",
        fontSize = 11.sp,
        color = MaterialTheme.colors.secondary,
        modifier = Modifier.weight(1f)
      )
    }
    if (hiddenCount > 0) {
      Row(
        Modifier.fillMaxWidth().clickable { showHidden.value = !showHidden.value }
          .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Checkbox(checked = showHidden.value, onCheckedChange = { showHidden.value = it })
        Text("Show joined / own / dead ($hiddenCount)", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
      }
    }
    Divider()
    when {
      loading.value -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colors.primary)
      }
      merged.isEmpty() -> EmptyState("No links in this chat.")
      visible.isEmpty() -> EmptyState("No links match.")
      else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
        if (simplexLinks.isNotEmpty()) {
          item(key = "h-sx") { SectionHeaderRow("SimpleX links", simplexLinks.size) }
          items(simplexLinks, key = { "sx-${it.dedupKey}" }) { link ->
            SimplexLinkRow(link, rhId, close, onCopy = { clipboard.setText(AnnotatedString(link.displayUrl)) })
          }
        }
        if (webLinks.isNotEmpty()) {
          item(key = "h-web") { SectionHeaderRow("Web links", webLinks.size) }
          items(webLinks, key = { "web-${it.dedupKey}" }) { link ->
            WebLinkRow(
              link,
              onOpen = { runCatching { uriHandler.openUri(link.displayUrl) } },
              onCopy = { clipboard.setText(AnnotatedString(link.displayUrl)) }
            )
          }
        }
      }
    }
  }
}

private enum class LinkSort(val label: String) { Newest("Newest"), Oldest("Oldest"), MostShared("Most shared") }

@Composable
private fun SortMenu(sort: MutableState<LinkSort>) {
  val expanded = remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { expanded.value = true }) { Text(sort.value.label, fontSize = 13.sp) }
    DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
      LinkSort.values().forEach { s ->
        DropdownMenuItem(onClick = { sort.value = s; expanded.value = false }) { Text(s.label) }
      }
    }
  }
}

@Composable
private fun EmptyState(text: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colors.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
  }
}

@Composable
private fun SectionHeaderRow(title: String, count: Int) {
  Text(
    "$title ($count)",
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    color = MaterialTheme.colors.secondary,
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 14.dp)
  )
}

@Composable
private fun SimplexLinkRow(link: BrowserLink, rhId: Long?, close: () -> Unit, onCopy: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      painterResource(if (link.simplexType == SimplexLinkType.group || link.simplexType == SimplexLinkType.channel) MR.images.ic_group else MR.images.ic_person),
      contentDescription = null,
      tint = MaterialTheme.colors.primary,
      modifier = Modifier.size(28.dp)
    )
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        link.title ?: (link.simplexType?.description ?: "SimpleX link"),
        fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(link.status)
        Text(
          metaLine(link),
          fontSize = 12.sp, color = MaterialTheme.colors.secondary,
          maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp)
        )
      }
    }
    if (link.status == LinkStatus.Joinable || link.status == LinkStatus.Joined || link.status == LinkStatus.Connecting) {
      TextButton(onClick = {
        // launch the SimpleX-native connect/open flow (opens the chat if already known)
        withScope {
          planAndConnect(
            rhId, link.displayUrl, close = close,
            filterKnownContact = { c -> chat.simplex.common.views.newchat.openKnownContact(chatModel, rhId, close, c) },
            filterKnownGroup = { g -> chat.simplex.common.views.newchat.openKnownGroup(chatModel, rhId, close, g) }
          )
        }
      }) { Text(if (link.status == LinkStatus.Joined) "Open" else "Connect") }
    }
    IconButton(onClick = onCopy) {
      Icon(painterResource(MR.images.ic_content_copy), contentDescription = "Copy", tint = MaterialTheme.colors.secondary)
    }
  }
  Divider(Modifier.padding(start = 52.dp))
}

@Composable
private fun WebLinkRow(link: BrowserLink, onOpen: () -> Unit, onCopy: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    val bmp = remember(link.dedupKey) {
      link.imageBase64?.takeIf { it.isNotEmpty() }?.let { runCatching { base64ToBitmap(it) }.getOrNull() }
    }
    Box(
      Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.15f)),
      contentAlignment = Alignment.Center
    ) {
      if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      else Icon(painterResource(MR.images.ic_link), contentDescription = null, tint = MaterialTheme.colors.secondary)
    }
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      if (link.title != null) {
        Text(link.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Text(link.host, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(metaLine(link), fontSize = 12.sp, color = MaterialTheme.colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = onCopy) {
      Icon(painterResource(MR.images.ic_content_copy), contentDescription = "Copy", tint = MaterialTheme.colors.secondary)
    }
    IconButton(onClick = onOpen) {
      Icon(painterResource(MR.images.ic_open_in_new), contentDescription = "Open", tint = MaterialTheme.colors.primary)
    }
  }
  Divider(Modifier.padding(start = 64.dp))
}

@Composable
private fun StatusBadge(status: LinkStatus) {
  val (label, color) = when (status) {
    LinkStatus.Joinable -> "Joinable" to MaterialTheme.colors.primary
    LinkStatus.Joined -> "Joined" to MaterialTheme.colors.secondary
    LinkStatus.Own -> "Yours" to MaterialTheme.colors.secondary
    LinkStatus.Connecting -> "Connecting" to MaterialTheme.colors.primary
    LinkStatus.Unavailable -> "Unavailable" to MaterialTheme.colors.error
    LinkStatus.Dead -> "Dead" to MaterialTheme.colors.error
    LinkStatus.Checking -> "Checking…" to MaterialTheme.colors.secondary
    LinkStatus.Web -> "" to MaterialTheme.colors.secondary
  }
  if (label.isEmpty()) return
  Text(
    label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold,
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(color.copy(alpha = 0.12f))
      .padding(horizontal = 5.dp, vertical = 1.dp)
  )
}

private fun metaLine(link: BrowserLink): String {
  val who = link.lastSender
  val shared = if (link.count > 1) " · shared ${link.count}×" else ""
  return "$who$shared"
}

// Kick off a background coroutine on the app scope (planAndConnect is suspend).
private fun withScope(block: suspend () -> Unit) {
  chat.simplex.common.views.helpers.withBGApi { block() }
}

// ── data loading / extraction ────────────────────────────────────────────────

// Page the whole chat history for link-tagged items (read-only, doesn't disturb the open chat).
private suspend fun loadAllLinkItems(rhId: Long?, chatInfo: ChatInfo): List<ChatItem> {
  val all = ArrayList<ChatItem>()
  val pageSize = 200
  var pagination: ChatPagination = ChatPagination.Last(pageSize)
  var guard = 0
  while (guard++ < 200) {
    val res = chatModel.controller.apiGetChat(rhId, chatInfo.chatType, chatInfo.apiId, null, MsgContentTag.Link, pagination, "") ?: break
    val items = res.first.chatItems
    if (items.isEmpty()) break
    all.addAll(items)
    if (items.size < pageSize) break
    pagination = ChatPagination.Before(items.first().id, pageSize)
  }
  return all.distinctBy { it.id }
}

private class RawLink(
  val url: String, val kind: LinkKind, val simplexType: SimplexLinkType?,
  val host: String, val title: String?, val image: String?
)

// Pull every link out of a message: the MCLink preview plus any inline links in the formatted text.
private fun rawLinksOf(ci: ChatItem): List<RawLink> {
  val out = ArrayList<RawLink>()
  when (val mc = ci.content.msgContent) {
    is MsgContent.MCLink -> {
      val h = hostOf(mc.preview.uri)
      if (h != null) out.add(RawLink(mc.preview.uri, LinkKind.Web, null, h, mc.preview.title.ifBlank { null }, mc.preview.image.ifBlank { null }))
    }
    else -> {}
  }
  ci.formattedText?.forEach { ft ->
    when (val f = ft.format) {
      is Format.SimplexLink ->
        out.add(RawLink(f.simplexUri, LinkKind.SimpleX, f.linkType, f.smpHosts.firstOrNull() ?: "SimpleX", f.showText?.ifBlank { null }, null))
      is Format.Uri -> {
        val h = hostOf(ft.text)
        if (h != null) out.add(RawLink(ft.text, LinkKind.Web, null, h, null, null))
      }
      is Format.HyperLink -> {
        val h = hostOf(f.linkUri)
        if (h != null) out.add(RawLink(f.linkUri, LinkKind.Web, null, h, f.showText?.ifBlank { null }, null))
      }
      else -> {}
    }
  }
  return out
}

private fun extractAndDedup(items: List<ChatItem>, chatInfo: ChatInfo): List<BrowserLink> {
  // key -> aggregation
  class Agg(
    var displayUrl: String, val kind: LinkKind, val simplexType: SimplexLinkType?, val host: String,
    var title: String?, var image: String?, var lastSender: String, var lastTs: Instant, var count: Int
  )
  val map = LinkedHashMap<String, Agg>()
  // newest first from loader; walk oldest->newest so lastTs/sender end up newest
  for (ci in items.sortedBy { it.meta.itemTs }) {
    val sender = if (ci.chatDir.sent) "You" else (ci.memberDisplayName ?: chatInfo.displayName)
    for (raw in rawLinksOf(ci)) {
      val key = if (raw.kind == LinkKind.Web) (normalizeWeb(raw.url) ?: continue) else "sx:${raw.url.trim()}"
      val agg = map[key]
      if (agg == null) {
        map[key] = Agg(raw.url, raw.kind, raw.simplexType, raw.host, raw.title, raw.image, sender, ci.meta.itemTs, 1)
      } else {
        agg.count++
        agg.lastSender = sender
        agg.lastTs = ci.meta.itemTs
        if (agg.title == null && raw.title != null) agg.title = raw.title
        if (agg.image == null && raw.image != null) agg.image = raw.image
      }
    }
  }
  return map.entries.map { (k, a) ->
    BrowserLink(a.displayUrl, k, a.kind, a.simplexType, a.host, a.title, a.image, a.lastSender, a.lastTs, a.count)
  }
}

// After classification, collapse SimpleX links that point at the same known group/contact.
private fun mergeByEntity(links: List<BrowserLink>): List<BrowserLink> {
  val result = ArrayList<BrowserLink>()
  val byEntity = HashMap<String, BrowserLink>()
  for (l in links) {
    val ek = l.entityKey
    if (l.kind == LinkKind.SimpleX && ek != null) {
      val existing = byEntity[ek]
      if (existing == null) {
        byEntity[ek] = l
        result.add(l)
      } else {
        // fold this one into the kept entry
        val folded = BrowserLink(
          existing.displayUrl, existing.dedupKey, existing.kind, existing.simplexType, existing.host,
          existing.title ?: l.title, existing.imageBase64, existing.lastSender,
          if (l.lastTs > existing.lastTs) l.lastTs else existing.lastTs,
          existing.count + l.count
        )
        folded.status = existing.status
        folded.entityKey = ek
        val idx = result.indexOf(existing)
        if (idx >= 0) result[idx] = folded
        byEntity[ek] = folded
      }
    } else {
      result.add(l)
    }
  }
  return result
}

// ── SimpleX classification (safe, native, plan-only) ─────────────────────────

private suspend fun classifySimplex(rhId: Long?, uri: String): SxResult {
  // Raw command instead of controller.apiConnectPlan on purpose: apiConnectPlan pops a
  // user-facing alert on error responses, which would spam alerts during bulk background
  // classification. log = false so link URIs are never written to the debug log. This runs
  // over the app's normal SimpleX transport (SOCKS/Tor when enabled) — no extra exposure.
  val userId = chatModel.currentUser.value?.userId ?: return SxResult(LinkStatus.Dead, null)
  val r = runCatching { chatModel.controller.sendCmd(rhId, CC.APIConnectPlan(userId, uri, null), log = false) }.getOrNull()
  val plan = if (r is API.Result && r.res is CR.CRConnectionPlan) (r.res as CR.CRConnectionPlan).connectionPlan
    else return SxResult(LinkStatus.Dead, null)
  return when (plan) {
    is ConnectionPlan.InvitationLink -> when (val p = plan.invitationLinkPlan) {
      is InvitationLinkPlan.Ok -> SxResult(LinkStatus.Joinable, null)
      InvitationLinkPlan.OwnLink -> SxResult(LinkStatus.Own, null)
      is InvitationLinkPlan.Connecting -> SxResult(LinkStatus.Connecting, p.contact_?.let { "c:${it.contactId}" })
      is InvitationLinkPlan.Known -> SxResult(LinkStatus.Joined, "c:${p.contact.contactId}")
    }
    is ConnectionPlan.ContactAddress -> when (val p = plan.contactAddressPlan) {
      is ContactAddressPlan.Ok -> SxResult(LinkStatus.Joinable, null)
      ContactAddressPlan.OwnLink -> SxResult(LinkStatus.Own, null)
      ContactAddressPlan.ConnectingConfirmReconnect -> SxResult(LinkStatus.Connecting, null)
      is ContactAddressPlan.ConnectingProhibit -> SxResult(LinkStatus.Connecting, "c:${p.contact.contactId}")
      is ContactAddressPlan.Known -> SxResult(LinkStatus.Joined, "c:${p.contact.contactId}")
      is ContactAddressPlan.ContactViaAddress -> SxResult(LinkStatus.Joinable, "c:${p.contact.contactId}")
    }
    is ConnectionPlan.GroupLink -> when (val p = plan.groupLinkPlan) {
      is GroupLinkPlan.Ok -> SxResult(LinkStatus.Joinable, null)
      is GroupLinkPlan.OwnLink -> SxResult(LinkStatus.Own, "g:${p.groupInfo.groupId}")
      GroupLinkPlan.ConnectingConfirmReconnect -> SxResult(LinkStatus.Connecting, null)
      is GroupLinkPlan.ConnectingProhibit -> SxResult(LinkStatus.Connecting, p.groupInfo_?.let { "g:${it.groupId}" })
      is GroupLinkPlan.Known -> SxResult(LinkStatus.Joined, "g:${p.groupInfo.groupId}")
      is GroupLinkPlan.NoRelays -> SxResult(LinkStatus.Unavailable, null)
      is GroupLinkPlan.UpdateRequired -> SxResult(LinkStatus.Unavailable, null)
    }
    is ConnectionPlan.Error -> SxResult(LinkStatus.Dead, null)
  }
}

// ── URL helpers (pure string, no network, no java.net) ───────────────────────

private val trackingParams = setOf(
  "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
  "fbclid", "gclid", "mc_eid", "igshid", "ref", "ref_src", "spm"
)

// Extract a display host from a URL, or null if it isn't a real web URL.
private fun hostOf(url: String): String? {
  var s = url.trim()
  if (s.isEmpty()) return null
  val scheme = s.indexOf("://")
  if (scheme >= 0) {
    val proto = s.substring(0, scheme).lowercase()
    if (proto != "http" && proto != "https") return null
    s = s.substring(scheme + 3)
  } else {
    // no scheme: only treat as web host if it looks like a domain
    if (!s.contains('.')) return null
  }
  var host = s.substringBefore('/').substringBefore('?').substringBefore('#')
  host = host.substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
  if (host.isEmpty() || !host.contains('.')) return null
  return host
}

// Canonical dedup key for a web URL: host + path (trailing slash trimmed) + cleaned, sorted query.
private fun normalizeWeb(url: String): String? {
  val host = hostOf(url) ?: return null
  var s = url.trim()
  val scheme = s.indexOf("://")
  if (scheme >= 0) s = s.substring(scheme + 3)
  val noFrag = s.substringBefore('#')
  val slash = noFrag.indexOf('/')
  val pathAndQuery = if (slash >= 0) noFrag.substring(slash) else ""
  val path = pathAndQuery.substringBefore('?').trimEnd('/')
  val query = pathAndQuery.substringAfter('?', "")
  val cleanQuery = if (query.isEmpty()) "" else query.split('&')
    .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in trackingParams }
    .sorted().joinToString("&")
  val sb = StringBuilder("web:").append(host).append(path)
  if (cleanQuery.isNotEmpty()) sb.append('?').append(cleanQuery)
  return sb.toString()
}
