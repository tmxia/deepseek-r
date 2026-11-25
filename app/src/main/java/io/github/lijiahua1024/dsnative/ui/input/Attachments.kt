package io.github.lijiahua1024.dsnative.ui.input

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.DraftAttachment
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.theme.softShadow
import io.github.lijiahua1024.dsnative.ui.excludeFromDrawerGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.hypot

/** 一张照片（MediaStore 最近照片行） */
data class RecentPhoto(val id: String, val uri: Uri, val name: String, val mimeType: String)

/** 查询最近照片（MediaStore，按添加时间倒序）。 */
suspend fun queryRecentPhotos(context: Context, limit: Int = 40): List<RecentPhoto> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )
        // ref RecentPhotos：同秒入序按 _id 倒序
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val out = mutableListOf<RecentPhoto>()
        try {
            resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idCol)
                    out += RecentPhoto(
                        id = id.toString(),
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameCol) ?: "photo-$id",
                        mimeType = cursor.getString(mimeCol) ?: "image/*",
                    )
                }
            }
        } catch (_: Exception) {
        }
        out
    }

/** 返回持久化附件目录（filesDir/attaches）。 */
fun attachDir(context: Context): File = File(context.filesDir, "attaches").apply { mkdirs() }

/** 复制 content:// 源到应用私有附件目录，返回 DraftAttachment。 */
suspend fun importToDraft(context: Context, uri: Uri, photoId: String? = null): DraftAttachment? =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var name = "file-${UUID.randomUUID()}"
            var mime = resolver.getType(uri) ?: ""
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                }
            }
            if (mime.isEmpty()) mime = guessMime(name)
            val dir = attachDir(context)
            val file = File(dir, "${UUID.randomUUID()}-$name")
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            DraftAttachment(
                id = UUID.randomUUID().toString(),
                name = name,
                mimeType = mime,
                isImage = mime.startsWith("image/"),
                localPath = file.absolutePath,
                recentPhotoId = photoId,
            )
        } catch (_: Exception) {
            null
        }
    }

private fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "pdf" -> "application/pdf"
        else -> ""
    }
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberThumb(producer: suspend () -> Bitmap?): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, producer) {
        value = withContext(Dispatchers.IO) {
            try {
                producer()?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

private fun decodeScaled(path: String, reqSize: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    if (opts.outWidth <= 0) return null
    var sample = 1
    while (opts.outWidth / (sample * 2) >= reqSize) sample *= 2
    return BitmapFactory.decodeFile(
        path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
internal fun thumbFor(path: String, sizePx: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                decodeScaled(path, sizePx)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

/** 缩略图按 EXIF 方向校正后解码，避免竖拍照片在气泡/草稿位横置。 */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
internal fun orientedThumbFor(path: String, sizePx: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                decodeOrientedBitmap(path, sizePx)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

private sealed interface PreviewDecode {
    data object Loading : PreviewDecode
    data class Ready(val bitmap: ImageBitmap) : PreviewDecode
    data object Error : PreviewDecode
}

/**
 * 全屏预览解码：按 EXIF 方向校正后再按长边缩放（ref Web `<img>` 加载完成前显示
 * loading 占位，onError 才进入失败态）。三态分离避免旧实现「解码中先闪错误图标、
 * 再异步出图」造成的跳闪。
 */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberPreviewDecode(path: String, maxEdge: Int): PreviewDecode {
    val state by produceState<PreviewDecode>(PreviewDecode.Loading, path) {
        value = withContext(Dispatchers.IO) {
            try {
                decodeOrientedBitmap(path, maxEdge)?.asImageBitmap()
                    ?.let { PreviewDecode.Ready(it) } ?: PreviewDecode.Error
            } catch (_: Exception) {
                PreviewDecode.Error
            }
        }
    }
    return state
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun photoThumb(resolver: ContentResolver, uri: Uri, sizePx: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val b = if (Build.VERSION.SDK_INT >= 29) {
                    resolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        resolver, ContentUris.parseId(uri),
                        MediaStore.Images.Thumbnails.MINI_KIND, null)
                }
                b?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

/**
 * ref AndroidAttachments panel：+ 按钮展开，位于输入卡片下方、占满屏宽
 *（ref margin-inline: -12px 相对卡片内边距）。
 */
@Composable
fun AttachmentsPanel(
    open: Boolean,
    photos: List<RecentPhoto>,
    selectedPhotoIds: Set<String>,
    onPhoto: (RecentPhoto) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onWeChatFiles: () -> Unit = onFiles,
) {
    // ref AndroidAttachments.module.css: grid-template-rows 260ms cubic-bezier(0.2,0.8,0.2,1)
    // + opacity 160ms ease-out — expand/collapse the panel instead of instant mount
    val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    var showFileSources by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open) showFileSources = false
    }
    io.github.lijiahua1024.dsnative.ui.DrawerGestureBlocker(open && showFileSources)
    val c = DsTheme.colors
    androidx.compose.animation.AnimatedVisibility(visible = open,
        enter = androidx.compose.animation.expandVertically(
            androidx.compose.animation.core.tween(260, easing = ease))
            + androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(160)),
        exit = androidx.compose.animation.shrinkVertically(
            androidx.compose.animation.core.tween(260, easing = ease))
            + androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(160))) {
    val resolver = LocalContext.current.contentResolver
    Column(Modifier.fillMaxWidth()) {
        if (photos.isNotEmpty()) {
            // .recent: gap 8, padding 24 20 0；80x80 r16 border-l2 skeleton
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 12.dp).excludeFromDrawerGesture(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(photos, key = { _, p -> p.id }) { i, photo ->
                    val selected = photo.id in selectedPhotoIds
                    // CSS padding 不裁剪可视区：首/末项自带 20dp 外边距，行满宽显示
                    Box(
                        Modifier.padding(
                                start = if (i == 0) 20.dp else 0.dp,
                                end = if (i == photos.lastIndex) 20.dp else 0.dp)
                            .size(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.bgSkeleton)
                            .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                            .clickable { onPhoto(photo) },
                    ) {
                        photoThumb(resolver, photo.uri, 320)?.let {
                            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (selected) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                        }
                        // 选中徽标：17px 圆，白边，未选黑底白边 / 选中白底黑勾
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp)
                                .size(17.dp)
                                .shadow(2.dp, CircleShape, clip = false)
                                .clip(CircleShape)
                                .background(if (selected) Color.White else Color(0x52000000))
                                .border(1.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(painterResource(R.drawable.ic_check_badge), null,
                                    tint = Color.Unspecified, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        // .actions: 3 列 grid，gap 8，padding 20 20 33；按钮 h84 r18 currentColor 5%
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 50.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachmentAction(Modifier.weight(1f), R.drawable.ic_camera,
                Strings.get("androidCamera"), onCamera)
            AttachmentAction(Modifier.weight(1f), R.drawable.ic_image_outline,
                Strings.get("androidGallery"), onGallery)
            AttachmentAction(Modifier.weight(1f), R.drawable.ic_files_clip,
                Strings.get("androidFiles")) { showFileSources = true }
        }
    }
    }
    if (open && showFileSources) {
        val atWindowOrigin = remember {
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    popupContentSize: androidx.compose.ui.unit.IntSize,
                ) = androidx.compose.ui.unit.IntOffset(-anchorBounds.left, -anchorBounds.top)
            }
        }
        androidx.compose.ui.window.Popup(
            popupPositionProvider = atWindowOrigin,
            onDismissRequest = { showFileSources = false },
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showFileSources = false }
            ) {
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 32.dp, bottom = 215.dp)
                        .width(208.dp)
                        .softShadow(16.dp, 14.dp, 4.dp, color = Color.Black.copy(alpha = 0.12f))
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.menu)
                        .clickable(enabled = false) {},
                ) {
                    Column {
                        FileSourceRow(
                            icon = R.drawable.ic_folder,
                            label = Strings.get("androidLocalFiles"),
                        ) { showFileSources = false; onFiles() }
                        Box(Modifier.fillMaxWidth().height(0.7.dp).background(c.borderL1))
                        FileSourceRow(
                            icon = R.drawable.ic_wechat,
                            label = Strings.get("androidWeChatFiles"),
                        ) { showFileSources = false; onWeChatFiles() }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileSourceRow(icon: Int, label: String, onClick: () -> Unit) {
    val c = DsTheme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(src, null, onClick = onClick)
            .background(DsTheme.pressTint(pressed, c.interactiveBgHover))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(icon), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 16.sp, lineHeight = 22.sp, color = c.labelPrimary)
    }
}

@Composable
private fun AttachmentAction(modifier: Modifier, icon: Int, label: String, onClick: () -> Unit) {
    val c = DsTheme.colors
    // ref AndroidAttachments .action:active -> color-mix(currentColor 10%)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val tileBg = if (c.dark) {
        if (pressed) Color(0xFF4A4A4C) else Color(0xFF3C3C3D)
    } else {
        c.labelPrimary.copy(alpha = DsTheme.pressAlpha(pressed, 0.10f, 0.05f))
    }
    Column(
        modifier.height(84.dp).clip(RoundedCornerShape(18.dp))
            .background(tileBg)
            .clickable(src, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = c.labelPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 15.sp, color = c.labelPrimary)
    }
}

/**
 * ref AndroidDraftAttachments + DraftAttachments：卡片内 textarea 上方的
 * 80x80 缩略图行 + 图片附件专属快捷建议 chips。
 */
@Composable
fun DraftAttachmentsRow(
    items: List<DraftAttachment>,
    showSuggestions: Boolean,
    canSend: Boolean,
    onRemove: (DraftAttachment) -> Unit,
    onPreview: (DraftAttachment) -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val c = DsTheme.colors
    // ref .androidPreview/.quickSuggestions: grid-template-rows 0fr->1fr
    // 300ms cubic-bezier(0.2,0,0,1)，内容 opacity 240ms + transform 300ms
    androidx.compose.animation.AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = androidx.compose.animation.expandVertically(
            androidx.compose.animation.core.tween(300,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            + androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(240)),
        exit = androidx.compose.animation.shrinkVertically(
            androidx.compose.animation.core.tween(300,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            + androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(240)),
    ) {
    Column {
        // [data-android] .attachments：padding 9 9 0，gap 10，item 80x80 r16
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 9.dp, end = 9.dp, top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEach { item ->
                // ref .attachment: android-attachment-enter 210ms linear 90ms
                // (opacity 0->1, translateY(12px)->0, scale .98->1)
                val chipEnter = remember(item.id) { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(item.id) {
                    chipEnter.animateTo(1f, androidx.compose.animation.core.tween(210, delayMillis = 90))
                }
                Box(
                    Modifier.size(80.dp)
                        .graphicsLayer {
                            alpha = chipEnter.value
                            translationY = 12.dp.toPx() * (1f - chipEnter.value)
                            val s = 0.98f + 0.02f * chipEnter.value
                            scaleX = s; scaleY = s
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.bgSkeleton)
                        .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                        .clickable { onPreview(item) },
                ) {
                    if (item.isImage) {
                        orientedThumbFor(item.localPath, 320)?.let {
                            Image(it, item.name, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop)
                        }
                    } else {
                        // 非图片：FileCard 简化（图标 + 文件名）
                        Column(
                            Modifier.align(Alignment.Center).padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(painterResource(R.drawable.ic_files_clip), item.name,
                                tint = c.labelSecondary, modifier = Modifier.size(20.dp))
                            Text(
                                item.name.substringAfterLast('/'),
                                fontSize = 9.sp, color = c.labelSecondary, maxLines = 2,
                            )
                        }
                    }
                    // [data-android] .remove：20x20 常显 #606164 圆底
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp)
                            .size(20.dp).clip(CircleShape).background(Color(0xFF606164))
                            .clickable { onRemove(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_close), Strings.get("remove") ,
                            tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        if (showSuggestions) {
            val keys = listOf(
                "androidPhotoWhat", "androidPhotoAnalyze",
                "androidPhotoAdvice", "androidPhotoExplain",
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 9.dp, end = 9.dp, top = 14.dp, bottom = 3.6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                keys.forEach { key ->
                    Row(
                        Modifier.height(32.dp)
                            .border(1.dp, c.borderL2, RoundedCornerShape(10.dp))
                            .clickable(enabled = canSend) { onSuggestion(Strings.get(key)) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Strings.get(key), fontSize = 14.sp, color = c.labelSecondary)
                        Spacer(Modifier.width(6.dp))
                        Icon(painterResource(R.drawable.ic_arrow_right_16), null,
                            tint = c.labelSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
    }
}

/**
 * ref AndroidImageViewer 预览态：全屏 dialog（#111317），图片 contain 居中，
 * 点按关闭；右下 action 行（裁剪、可下载时保存）。
 */

/** ref saveAndroidImage: copia il file locale in MediaStore.Images (Pictures/).
 *  API<=28 richiede WRITE_EXTERNAL_STORAGE a runtime; se manca ritorna false. */
internal fun saveImageToGallery(context: Context, localPath: String?, name: String, mime: String): Boolean {
    if (localPath.isNullOrEmpty()) return false
    if (Build.VERSION.SDK_INT <= 28 &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) return false
    return try {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime.ifEmpty { "image/*" })
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        val ok = resolver.openOutputStream(uri)?.use { out ->
            java.io.File(localPath).inputStream().use { it.copyTo(out) }
            true
        } ?: false
        if (!ok) { resolver.delete(uri, null, null); return false }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal data class NormalizedCrop(
    val turns: Int = 0,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    fun isFull(): Boolean =
        turns % 4 == 0 && left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    fun rotated90Ccw(): NormalizedCrop = NormalizedCrop(
        turns = (turns + 1) % 4,
        left = top.coerceIn(0f, 1f),
        top = (1f - right).coerceIn(0f, 1f),
        right = bottom.coerceIn(0f, 1f),
        bottom = (1f - left).coerceIn(0f, 1f),
    )

    fun resizeOrMove(
        handleX: Int,
        handleY: Int,
        dxFrac: Float,
        dyFrac: Float,
        minSize: Float = 0.1f,
    ): NormalizedCrop {
        if (handleX == 0 && handleY == 0) {
            val w = right - left
            val h = bottom - top
            val nl = (left + dxFrac).coerceIn(0f, (1f - w).coerceAtLeast(0f))
            val nt = (top + dyFrac).coerceIn(0f, (1f - h).coerceAtLeast(0f))
            return copy(left = nl, top = nt, right = nl + w, bottom = nt + h)
        }
        val minW = minOf(minSize, right - left)
        val minH = minOf(minSize, bottom - top)
        val nl = if (handleX < 0) (left + dxFrac).coerceIn(0f, right - minW) else left
        val nr = if (handleX > 0) (right + dxFrac).coerceIn(left + minW, 1f) else right
        val nt = if (handleY < 0) (top + dyFrac).coerceIn(0f, bottom - minH) else top
        val nb = if (handleY > 0) (bottom + dyFrac).coerceIn(top + minH, 1f) else bottom
        return copy(left = nl, top = nt, right = nr, bottom = nb)
    }
}

private fun decodeOrientedBitmap(path: String, maxEdge: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    var sample = 1
    while (opts.outWidth / sample > maxEdge * 2 || opts.outHeight / sample > maxEdge * 2) sample *= 2
    val raw = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    val matrix = Matrix()
    try {
        when (ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
    } catch (_: Exception) {
    }
    val oriented = if (!matrix.isIdentity) {
        val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (r != raw) raw.recycle()
        r
    } else raw
    val maxDim = maxOf(oriented.width, oriented.height)
    if (maxDim > maxEdge) {
        val scale = maxEdge.toFloat() / maxDim
        val scaled = Bitmap.createScaledBitmap(
            oriented,
            maxOf(1, (oriented.width * scale).toInt()),
            maxOf(1, (oriented.height * scale).toInt()),
            true,
        )
        if (scaled != oriented) oriented.recycle()
        return scaled
    }
    return oriented
}

private fun rotateBitmapCcw(src: Bitmap, turns: Int): Bitmap {
    val normTurns = ((turns % 4) + 4) % 4
    if (normTurns == 0) return src
    val matrix = Matrix().apply { postRotate(-normTurns * 90f) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private suspend fun exportCroppedAttachment(
    context: Context,
    item: DraftAttachment,
    crop: NormalizedCrop,
): DraftAttachment? = withContext(Dispatchers.IO) {
    var base: Bitmap? = null
    var rotated: Bitmap? = null
    var cropped: Bitmap? = null
    try {
        base = decodeOrientedBitmap(item.localPath, 4096) ?: return@withContext null
        rotated = rotateBitmapCcw(base, crop.turns)
        val cx = (crop.left * rotated.width).toInt().coerceIn(0, rotated.width - 1)
        val cy = (crop.top * rotated.height).toInt().coerceIn(0, rotated.height - 1)
        val cw = ((crop.right - crop.left) * rotated.width).toInt().coerceIn(1, rotated.width - cx)
        val ch = ((crop.bottom - crop.top) * rotated.height).toInt().coerceIn(1, rotated.height - cy)
        cropped = Bitmap.createBitmap(rotated, cx, cy, cw, ch)
        val dir = attachDir(context)
        val stem = item.name.substringBeforeLast('.').ifEmpty { "image" }
        val outFile = File(dir, "${UUID.randomUUID()}-$stem.webp")
        val format = if (Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        FileOutputStream(outFile).use { out ->
            if (!cropped.compress(format, 85, out)) return@withContext null
        }
        DraftAttachment(
            id = UUID.randomUUID().toString(),
            name = "$stem.webp",
            mimeType = "image/webp",
            isImage = true,
            localPath = outFile.absolutePath,
        )
    } catch (_: Exception) {
        null
    } finally {
        if (cropped != null && cropped !== rotated && cropped !== base) cropped.recycle()
        if (rotated != null && rotated !== base) rotated.recycle()
        base?.recycle()
    }
}

@Composable
fun ImagePreviewDialog(
    item: DraftAttachment,
    onClose: () -> Unit,
    onCrop: () -> Unit = {},
    onConfirmCrop: ((DraftAttachment) -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    val dlgCtx = LocalContext.current
    val dlgScope = rememberCoroutineScope()
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var cropState by remember(item.id, item.localPath) { mutableStateOf<NormalizedCrop?>(null) }
    var cropError by remember { mutableStateOf<String?>(null) }
    var cropSaving by remember { mutableStateOf(false) }
    LaunchedEffect(downloadStatus) {
        if (downloadStatus != null) {
            kotlinx.coroutines.delay(2500)
            downloadStatus = null
        }
    }
    LaunchedEffect(cropError) {
        if (cropError != null) {
            kotlinx.coroutines.delay(2500)
            cropError = null
        }
    }
    Dialog(
        onDismissRequest = { if (cropState != null) cropState = null else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false),
    ) {
        // ref AndroidImageViewer.module.css: left/top/width/height 180ms ease-out —
        // 缩略图位到全屏位的位移放大，近似为整体 fade+scale 入场
        val viewerEnter = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            viewerEnter.animateTo(1f,
                androidx.compose.animation.core.tween(180,
                    easing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)))
        }
        // ref preview is fully immersive: the WebView overlay hides both system
        // bars and sits under a black status scrim
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            val w = dialogWindow ?: return@DisposableEffect onDispose {}
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            w.attributes = w.attributes.apply { dimAmount = 1f }
            WindowInsetsControllerCompat(w, w.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            // DialogWrapper re-applies wrap_content on every layout pass;
            // re-assert MATCH_PARENT each time so the window keeps filling
            // the parent frame (y128..2340, same as the ref overlay)
            val fill = {
                val lp = w.attributes
                if (lp.width != WindowManager.LayoutParams.MATCH_PARENT ||
                    lp.height != WindowManager.LayoutParams.MATCH_PARENT) {
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT)
                }
            }
            val fillRunnable = Runnable { fill() }
            val layoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fill() }
            w.decorView.post(fillRunnable)
            w.decorView.addOnLayoutChangeListener(layoutListener)
            onDispose {
                w.decorView.removeCallbacks(fillRunnable)
                w.decorView.removeOnLayoutChangeListener(layoutListener)
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF111317))
            .graphicsLayer {
                alpha = viewerEnter.value
                val s = 0.96f + 0.04f * viewerEnter.value
                scaleX = s; scaleY = s
            }) {
            val activeCrop = cropState
            if (activeCrop != null) {
                CropEditorOverlay(
                    item = item,
                    crop = activeCrop,
                    saving = cropSaving,
                    errorKey = cropError,
                    onCropChange = { cropState = it; cropError = null },
                    onCancel = { cropState = null; cropError = null },
                    onConfirm = {
                        if (cropSaving) return@CropEditorOverlay
                        cropSaving = true
                        cropError = null
                        dlgScope.launch {
                            val result = exportCroppedAttachment(dlgCtx, item, activeCrop)
                            cropSaving = false
                            if (result != null) {
                                onConfirmCrop?.invoke(result)
                                cropState = null
                                onClose()
                            } else {
                                cropError = "androidCropFailed"
                            }
                        }
                    },
                )
            } else {
            val previewDecode = rememberPreviewDecode(item.localPath, 2048)
            when (previewDecode) {
                PreviewDecode.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            Modifier.size(28.dp),
                            color = Color(0xFFF7F9FC),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                is PreviewDecode.Ready -> {
                    // the content rect extends under the nav bar, so the centered
                    // image sits ~34px above the ref's viewport center
                    Image(
                        previewDecode.bitmap, item.name,
                        Modifier.fillMaxSize().padding(top = 25.dp)
                            .clickable(onClick = onClose),
                        contentScale = ContentScale.Fit,
                    )
                }
                PreviewDecode.Error -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(painterResource(R.drawable.ic_photo_error), null,
                            tint = Color(0xFFF7F9FC), modifier = Modifier.size(20.dp))
                        Text(Strings.get("androidDraftImageLoadFailed"),
                            color = Color(0xFFF7F9FC), fontSize = 14.sp)
                    }
                }
            }
            // .previewControls：右下 right/bottom 16px fixed（ref 用 button-nav
            // inset=0 的手势导航），gap 8。dialog 内容仍带着隐式 nav inset，
            // 用 offset 把按钮推回屏幕底边
            Column(
                Modifier.align(Alignment.BottomEnd)
                    .offset(y = 25.dp)
                    .padding(end = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.End,
            ) {
                downloadStatus?.let { key ->
                    // ref .downloadStatus: pill sopra i bottoni, pad 6/12 r8 bg image-toolbar
                    Box(
                        Modifier.padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF20242B))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(Strings.get(key), color = Color(0xFFF7F9FC), fontSize = 14.sp)
                    }
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (previewDecode is PreviewDecode.Ready && onConfirmCrop != null) {
                    Row(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF20242B))
                            .clickable {
                                if (onConfirmCrop != null) {
                                    cropState = NormalizedCrop()
                                } else {
                                    onCrop()
                                }
                            }
                            .padding(start = 12.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_crop), null,
                            tint = Color(0xFFF7F9FC), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(Strings.get("androidCropImage"),
                            color = Color(0xFFF7F9FC), fontSize = 15.sp)
                    }
                }
                if (onDownload != null) {
                    Row(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF20242B))
                            .clickable(enabled = !downloading) {
                                downloading = true
                                downloadStatus = null
                                dlgScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val ok = saveImageToGallery(
                                        dlgCtx, item.localPath, item.name, item.mimeType)
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        downloadStatus = if (ok) "androidImageSaved"
                                            else "androidImageSaveFailed"
                                        downloading = false
                                    }
                                }
                                onDownload()
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_download), null,
                            tint = Color(0xFFF7F9FC), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(Strings.get("androidSaveImage"),
                            color = Color(0xFFF7F9FC), fontSize = 15.sp)
                    }
                }
            }
            }
            }
        }
    }
}

@Composable
private fun CropEditorOverlay(
    item: DraftAttachment,
    crop: NormalizedCrop,
    saving: Boolean,
    errorKey: String?,
    onCropChange: (NormalizedCrop) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = DsTheme.colors
    var previewBmp by remember(item.localPath, crop.turns) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.localPath, crop.turns) {
        previewBmp = withContext(Dispatchers.IO) {
            val base = decodeOrientedBitmap(item.localPath, 1600) ?: return@withContext null
            val rotated = rotateBitmapCcw(base, crop.turns)
            if (rotated !== base) base.recycle()
            rotated.asImageBitmap()
        }
    }
    Column(
        Modifier.fillMaxSize()
            .navigationBarsPadding()
            .padding(top = 36.dp, bottom = 16.dp),
    ) {
        // 顶部操作栏：还原 + 旋转
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (!crop.isFull()) {
                Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF20242B))
                        .clickable(enabled = !saving) { onCropChange(NormalizedCrop()) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Strings.get("androidCropReset"), color = Color(0xFFF7F9FC), fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
            }
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF20242B))
                    .clickable(enabled = !saving) { onCropChange(crop.rotated90Ccw()) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_refresh), null,
                    tint = Color(0xFFF7F9FC), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(Strings.get("androidCropRotate"), color = Color(0xFFF7F9FC), fontSize = 14.sp)
            }
        }
        // 中央裁剪画布
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = previewBmp
            if (bmp != null) {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / bmp.width.toFloat(), boxH / bmp.height.toFloat())
                val imgW = bmp.width * scale
                val imgH = bmp.height * scale
                val imgLeft = (boxW - imgW) / 2f
                val imgTop = (boxH - imgH) / 2f
                val latestCrop by rememberUpdatedState(crop)
                var dragTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }

                Box(Modifier.fillMaxSize()) {
                    Image(
                        bmp, item.name,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(imgW, imgH, crop.turns) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val r = latestCrop
                                        val cl = imgLeft + r.left * imgW
                                        val ct = imgTop + r.top * imgH
                                        val cr = imgLeft + r.right * imgW
                                        val cb = imgTop + r.bottom * imgH
                                        val cx = (cl + cr) / 2f
                                        val cy = (ct + cb) / 2f
                                        val hitR = 28.dp.toPx()
                                        val handles = listOf(
                                            Triple(-1, -1, Offset(cl, ct)),
                                            Triple(0, -1, Offset(cx, ct)),
                                            Triple(1, -1, Offset(cr, ct)),
                                            Triple(-1, 0, Offset(cl, cy)),
                                            Triple(1, 0, Offset(cr, cy)),
                                            Triple(-1, 1, Offset(cl, cb)),
                                            Triple(0, 1, Offset(cx, cb)),
                                            Triple(1, 1, Offset(cr, cb)),
                                        )
                                        var best: Pair<Int, Int>? = null
                                        var bestDist = hitR
                                        for ((hx, hy, pt) in handles) {
                                            val d = hypot(offset.x - pt.x, offset.y - pt.y)
                                            if (d <= bestDist) {
                                                bestDist = d
                                                best = hx to hy
                                            }
                                        }
                                        if (best == null && offset.x in cl..cr && offset.y in ct..cb) {
                                            best = 0 to 0
                                        }
                                        dragTarget = best
                                    },
                                    onDragEnd = { dragTarget = null },
                                    onDragCancel = { dragTarget = null },
                                    onDrag = { change, dragAmount ->
                                        val target = dragTarget ?: return@detectDragGestures
                                        change.consume()
                                        if (imgW > 1f && imgH > 1f) {
                                            onCropChange(
                                                latestCrop.resizeOrMove(
                                                    target.first, target.second,
                                                    dragAmount.x / imgW, dragAmount.y / imgH,
                                                ),
                                            )
                                        }
                                    },
                                )
                            },
                    ) {
                        val cl = imgLeft + crop.left * imgW
                        val ct = imgTop + crop.top * imgH
                        val cr = imgLeft + crop.right * imgW
                        val cb = imgTop + crop.bottom * imgH
                        val cw = (cr - cl).coerceAtLeast(1f)
                        val ch = (cb - ct).coerceAtLeast(1f)
                        val scrim = Color(0x99000000)
                        // 上、下、左、右半透明遮罩
                        if (ct > imgTop) drawRect(scrim, Offset(imgLeft, imgTop), androidx.compose.ui.geometry.Size(imgW, ct - imgTop))
                        if (cb < imgTop + imgH) drawRect(scrim, Offset(imgLeft, cb), androidx.compose.ui.geometry.Size(imgW, imgTop + imgH - cb))
                        if (cl > imgLeft) drawRect(scrim, Offset(imgLeft, ct), androidx.compose.ui.geometry.Size(cl - imgLeft, ch))
                        if (cr < imgLeft + imgW) drawRect(scrim, Offset(cr, ct), androidx.compose.ui.geometry.Size(imgLeft + imgW - cr, ch))
                        // 三分线与外框
                        val thirdW = cw / 3f
                        val thirdH = ch / 3f
                        val gridColor = Color.White.copy(alpha = 0.42f)
                        val gridStroke = 0.75.dp.toPx()
                        drawLine(gridColor, Offset(cl + thirdW, ct), Offset(cl + thirdW, cb), gridStroke)
                        drawLine(gridColor, Offset(cl + 2 * thirdW, ct), Offset(cl + 2 * thirdW, cb), gridStroke)
                        drawLine(gridColor, Offset(cl, ct + thirdH), Offset(cr, ct + thirdH), gridStroke)
                        drawLine(gridColor, Offset(cl, ct + 2 * thirdH), Offset(cr, ct + 2 * thirdH), gridStroke)
                        drawRect(Color.White, Offset(cl, ct), androidx.compose.ui.geometry.Size(cw, ch), style = Stroke(1.5.dp.toPx()))
                        // 四角白色加粗柄
                        val arm = minOf(18.dp.toPx(), cw / 3f, ch / 3f)
                        val handleW = 3.dp.toPx()
                        drawLine(Color.White, Offset(cl, ct), Offset(cl + arm, ct), handleW)
                        drawLine(Color.White, Offset(cl, ct), Offset(cl, ct + arm), handleW)
                        drawLine(Color.White, Offset(cr, ct), Offset(cr - arm, ct), handleW)
                        drawLine(Color.White, Offset(cr, ct), Offset(cr, ct + arm), handleW)
                        drawLine(Color.White, Offset(cl, cb), Offset(cl + arm, cb), handleW)
                        drawLine(Color.White, Offset(cl, cb), Offset(cl, cb - arm), handleW)
                        drawLine(Color.White, Offset(cr, cb), Offset(cr - arm, cb), handleW)
                        drawLine(Color.White, Offset(cr, cb), Offset(cr, cb - arm), handleW)
                    }
                }
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        // 底部错误提示 + 取消 / 去提问按钮
        if (errorKey != null) {
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF20242B))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(Strings.get(errorKey), color = Color(0xFFFF7A7A), fontSize = 13.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(Color(0xFF20242B))
                    .clickable(enabled = !saving, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_close), Strings.get("cancel"),
                    tint = Color(0xFFF7F9FC), modifier = Modifier.size(18.dp))
            }
            Button(
                onClick = onConfirm,
                enabled = !saving && previewBmp != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.brandPrimary,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(100.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Text(Strings.get("androidCropAsk"), fontSize = 15.sp)
                }
            }
        }
    }
}
