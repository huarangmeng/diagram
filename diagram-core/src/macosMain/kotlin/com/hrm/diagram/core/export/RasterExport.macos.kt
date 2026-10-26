@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hrm.diagram.core.export

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.draw.ArrowHead
import com.hrm.diagram.core.draw.Cap
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.Join
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.ImageIO.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** 使用 CoreGraphics 绘制共享指令，再由 ImageIO 编码；不依赖 Compose 或窗口。 */
@DiagramApi
actual suspend fun RenderedDiagram.exportPng(options: RasterExportOptions): ExportArtifact<ByteArray> =
    exportRaster(planRasterExport(options.scale, options.background, opaqueRequired = false), "public.png", null)

/** JPEG 背景与品质由共享导出计划及 ImageIO 压缩参数共同保证。 */
@DiagramApi
actual suspend fun RenderedDiagram.exportJpeg(options: JpegExportOptions): ExportArtifact<ByteArray> =
    exportRaster(planRasterExport(options.scale, options.background, opaqueRequired = true), "public.jpeg", options.quality)

private fun RenderedDiagram.exportRaster(plan: RasterExportPlan, type: String, quality: Int?): ExportArtifact<ByteArray> {
    val diagnostics = ArrayList<Diagnostic>()
    val bytes = CGColorSpaceCreateWithName(kCGColorSpaceSRGB).withOwnedCF { space ->
        CGBitmapContextCreate(
            null, plan.widthPx.toULong(), plan.heightPx.toULong(), 8u, 0u, space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        ).withOwnedCF { context ->
            CGContextSetShouldAntialias(context, true)
            CGContextSetShouldSmoothFonts(context, false)
            // CGBitmapContext 默认左下原点；只翻转一次，后续命令保持左上原点。
            CGContextTranslateCTM(context, 0.0, plan.heightPx.toDouble())
            CGContextScaleCTM(context, 1.0, -1.0)
            plan.background?.let {
                setFill(context, it)
                CGContextFillRect(context, CGRectMake(0.0, 0.0, plan.widthPx.toDouble(), plan.heightPx.toDouble()))
            }
            CGContextScaleCTM(context, plan.scale.toDouble(), plan.scale.toDouble())
            CGContextTranslateCTM(context, -bounds.left.toDouble(), -bounds.top.toDouble())
            renderCommands(context, drawCommands, diagnostics)
            CGBitmapContextCreateImage(context).withOwnedCF { image ->
                CFDataCreateMutable(null, 0).withOwnedCF { data ->
                    CFStringCreateWithCString(null, type, kCFStringEncodingUTF8).withOwnedCF { imageType ->
                        CGImageDestinationCreateWithData(data, imageType, 1u, null).withOwnedCF { destination ->
                            if (quality == null) {
                                CGImageDestinationAddImage(destination, image, null)
                            } else {
                                CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr).withOwnedCF { properties ->
                                    memScoped {
                                        val value = alloc<DoubleVar> { this.value = quality.coerceIn(1, 100) / 100.0 }
                                        CFNumberCreate(null, kCFNumberDoubleType, value.ptr).withOwnedCF { number ->
                                            CFDictionarySetValue(properties, kCGImageDestinationLossyCompressionQuality, number)
                                            CGImageDestinationAddImage(destination, image, properties)
                                        }
                                    }
                                }
                            }
                            check(CGImageDestinationFinalize(destination)) { "ImageIO failed to encode $type" }
                        }
                    }
                    val length = CFDataGetLength(data)
                    check(length in 1..Int.MAX_VALUE.toLong()) { "Invalid ImageIO output length: $length" }
                    checkNotNull(CFDataGetBytePtr(data)).readBytes(length.toInt())
                }
            }
        }
    }
    return ExportArtifact(bytes, if (quality == null) "image/png" else "image/jpeg", plan.widthPx, plan.heightPx, plan.diagnostics + diagnostics)
}

/** 对称释放 CF Create/Copy 引用；借用指针字典的值必须活在此作用域内。 */
private inline fun <T : CPointed, R> CPointer<T>?.withOwnedCF(block: (CPointer<T>) -> R): R {
    val value = checkNotNull(this) { "Unable to create macOS raster resource" }
    try {
        return block(value)
    } finally {
        CFRelease(value)
    }
}

private fun renderCommands(context: CGContextRef, commands: List<DrawCommand>, diagnostics: MutableList<Diagnostic>) {
    for (command in commands.sortedBy { it.z }) {
        CGContextSaveGState(context)
        try {
            when (command) {
                is DrawCommand.FillRect -> {
                    setFill(context, command.color)
                    rectPath(command.rect, command.corner).withOwnedCF { path ->
                        CGContextAddPath(context, path)
                        CGContextFillPath(context)
                    }
                }
                is DrawCommand.StrokeRect -> {
                    configureStroke(context, command.stroke, command.color)
                    rectPath(command.rect, command.corner).withOwnedCF { path ->
                        CGContextAddPath(context, path)
                        CGContextStrokePath(context)
                    }
                }
                is DrawCommand.FillPath -> {
                    setFill(context, command.color)
                    command.path.toCGPath().withOwnedCF { path ->
                        CGContextAddPath(context, path)
                        CGContextFillPath(context)
                    }
                }
                is DrawCommand.StrokePath -> {
                    configureStroke(context, command.stroke, command.color)
                    command.path.toCGPath().withOwnedCF { path ->
                        CGContextAddPath(context, path)
                        CGContextStrokePath(context)
                    }
                }
                is DrawCommand.DrawText -> drawText(context, command)
                is DrawCommand.DrawArrow -> {
                    configureStroke(context, command.style.stroke, command.style.color)
                    CGContextBeginPath(context)
                    CGContextMoveToPoint(context, command.from.x.toDouble(), command.from.y.toDouble())
                    CGContextAddLineToPoint(context, command.to.x.toDouble(), command.to.y.toDouble())
                    CGContextStrokePath(context)
                    // 端点不能继承 shaft 的 dash，否则空心端点会被切成断线。
                    CGContextSetLineDash(context, 0.0, null, 0u)
                    drawArrowHead(context, command.to, command.from, command.style.head, command.style.color)
                    drawArrowHead(context, command.from, command.to, command.style.tail, command.style.color)
                }
                is DrawCommand.DrawIcon -> {
                    // 名称不是图像载荷；与 SVG 一样保留可见占位，不能静默丢失。
                    configureStroke(context, Stroke(1f, listOf(2f, 2f)), Color(0xff888888.toInt()))
                    CGContextStrokeRect(context, command.rect.toCGRect())
                    diagnostics += Diagnostic(severity = Severity.WARNING, code = "EXPORT-W001", message = "No raster asset for icon '${command.name}'; rendered the SVG-compatible placeholder.")
                }
                is DrawCommand.Group -> {
                    val t = command.transform
                    CGContextTranslateCTM(context, t.translate.x.toDouble(), t.translate.y.toDouble())
                    CGContextRotateCTM(context, t.rotateDeg * PI / 180.0)
                    CGContextScaleCTM(context, t.scale.toDouble(), t.scale.toDouble())
                    renderCommands(context, command.children, diagnostics)
                }
                is DrawCommand.Clip -> {
                    CGContextClipToRect(context, command.rect.toCGRect())
                    renderCommands(context, command.children, diagnostics)
                }
                // Hyperlink 只声明交互区域，不含绘制内容；位图不能承载链接元数据。
                is DrawCommand.Hyperlink -> Unit
            }
        } finally {
            CGContextRestoreGState(context)
        }
    }
}

private fun setFill(context: CGContextRef, color: Color) =
    CGContextSetRGBFillColor(context, color.r / 255.0, color.g / 255.0, color.b / 255.0, color.a / 255.0)

private fun configureStroke(context: CGContextRef, stroke: Stroke, color: Color) {
    CGContextSetRGBStrokeColor(context, color.r / 255.0, color.g / 255.0, color.b / 255.0, color.a / 255.0)
    CGContextSetLineWidth(context, stroke.width.toDouble())
    CGContextSetLineCap(context, when (stroke.cap) {
        Cap.Butt -> CGLineCap.kCGLineCapButt
        Cap.Round -> CGLineCap.kCGLineCapRound
        Cap.Square -> CGLineCap.kCGLineCapSquare
    })
    CGContextSetLineJoin(context, when (stroke.join) {
        Join.Miter -> CGLineJoin.kCGLineJoinMiter
        Join.Round -> CGLineJoin.kCGLineJoinRound
        Join.Bevel -> CGLineJoin.kCGLineJoinBevel
    })
    val dash = stroke.dash
    if (dash.isNullOrEmpty()) CGContextSetLineDash(context, 0.0, null, 0u)
    else memScoped {
        val lengths = allocArray<DoubleVar>(dash.size)
        dash.forEachIndexed { index, value -> lengths[index] = value.toDouble() }
        CGContextSetLineDash(context, 0.0, lengths, dash.size.toULong())
    }
}

private fun rectPath(rect: Rect, corner: Float): CGPathRef? =
    if (corner > 0f) CGPathCreateWithRoundedRect(rect.toCGRect(), corner.toDouble(), corner.toDouble(), null)
    else CGPathCreateWithRect(rect.toCGRect(), null)

private fun Rect.toCGRect() = CGRectMake(left.toDouble(), top.toDouble(), size.width.toDouble(), size.height.toDouble())

private fun PathCmd.toCGPath(): CGMutablePathRef {
    val path = checkNotNull(CGPathCreateMutable())
    for (op in ops) {
        when (op) {
            is PathOp.MoveTo -> CGPathMoveToPoint(path, null, op.p.x.toDouble(), op.p.y.toDouble())
            is PathOp.LineTo -> CGPathAddLineToPoint(path, null, op.p.x.toDouble(), op.p.y.toDouble())
            is PathOp.QuadTo -> CGPathAddQuadCurveToPoint(path, null, op.ctrl.x.toDouble(), op.ctrl.y.toDouble(), op.end.x.toDouble(), op.end.y.toDouble())
            is PathOp.CubicTo -> CGPathAddCurveToPoint(path, null, op.c1.x.toDouble(), op.c1.y.toDouble(), op.c2.x.toDouble(), op.c2.y.toDouble(), op.end.x.toDouble(), op.end.y.toDouble())
            PathOp.Close -> CGPathCloseSubpath(path)
        }
    }
    return path
}

private fun drawText(context: CGContextRef, command: DrawCommand.DrawText) {
    if (command.text.isEmpty()) return
    val family = when (command.font.family.lowercase()) {
        "sans-serif", "sansserif", "dialog" -> "Helvetica"
        "serif" -> "Times"
        "monospace", "monospaced", "dialoginput" -> "Menlo"
        else -> command.font.family
    }
    CFStringCreateWithCString(null, family, kCFStringEncodingUTF8).withOwnedCF { name ->
        CTFontCreateWithName(name, command.font.sizeSp.toDouble(), null).withOwnedCF { baseFont ->
            val traits = (if (command.font.weight >= 600) kCTFontBoldTrait else 0u) or (if (command.font.italic) kCTFontItalicTrait else 0u)
            // CoreText 合法返回 null：此字体不存在所请求的 bold/italic face，仍用基础字体绘制。
            // https://developer.apple.com/documentation/coretext/ctfontcreatecopywithsymbolictraits
            val styledFont = CTFontCreateCopyWithSymbolicTraits(baseFont, 0.0, null, traits, kCTFontBoldTrait or kCTFontItalicTrait)
            val font = styledFont ?: baseFont
            try {
                CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr).withOwnedCF { attributes ->
                    CFDictionarySetValue(attributes, kCTFontAttributeName, font)
                    CFDictionarySetValue(attributes, kCTForegroundColorFromContextAttributeName, kCFBooleanTrue)
                    memScoped {
                        val alignment = alloc<UByteVar> {
                            value = when (command.anchorX) {
                                TextAnchorX.Start -> kCTTextAlignmentLeft
                                TextAnchorX.Center -> kCTTextAlignmentCenter
                                TextAnchorX.End -> kCTTextAlignmentRight
                            }
                        }
                        val setting = alloc<CTParagraphStyleSetting> {
                            spec = kCTParagraphStyleSpecifierAlignment
                            valueSize = sizeOf<UByteVar>().toULong()
                            value = alignment.ptr
                        }
                        CTParagraphStyleCreate(setting.ptr, 1u).withOwnedCF { paragraph ->
                            CFDictionarySetValue(attributes, kCTParagraphStyleAttributeName, paragraph)
                        }
                    }
                    command.text.toCFString().withOwnedCF { text ->
                        CFAttributedStringCreate(null, text, attributes).withOwnedCF { attributed ->
                            setFill(context, command.color)
                            CGContextSetTextMatrix(context, CGAffineTransformMakeScale(1.0, -1.0))
                            CTFramesetterCreateWithAttributedString(attributed).withOwnedCF { setter ->
                                // measuredBounds 可能来自 HeuristicTextMeasurer：14px 四汉字仅约 32.5px 宽，
                                // 而 CoreText 需要约 56px。它是布局/culling 几何，不是截字或换行边界。
                                // 原生排字仅遵循 maxWidth 和 origin/anchor，不回写布局或调用共享 TextMeasurer。
                                val wrapWidth = command.maxWidth?.takeIf { it > 0f }?.toDouble()
                                val nativeSize = CTFramesetterSuggestFrameSizeWithConstraints(
                                    setter, CFRangeMake(0, 0), null,
                                    CGSizeMake(wrapWidth ?: Double.MAX_VALUE, Double.MAX_VALUE), null,
                                ).useContents { width to height }
                                val width = wrapWidth ?: ceil(nativeSize.first)
                                // 留出末行的字体下降区，避免浮点取整让 CTFrame 丢掉最后一行。
                                // 额外空间只在文字下方；锚点仍基于原生完整段落高度，不包含此空间。
                                val height = ceil(nativeSize.second) + command.font.sizeSp
                                val left = when (command.anchorX) {
                                    TextAnchorX.Start -> command.origin.x.toDouble()
                                    TextAnchorX.Center -> command.origin.x - width / 2
                                    TextAnchorX.End -> command.origin.x - width
                                }
                                val top = when (command.anchorY) {
                                    TextAnchorY.Top -> command.origin.y.toDouble()
                                    TextAnchorY.Middle -> command.origin.y - nativeSize.second / 2
                                    TextAnchorY.Baseline -> command.origin.y - CTFontGetAscent(font)
                                    TextAnchorY.Bottom -> command.origin.y - nativeSize.second
                                }
                                if (width > 0 && height > 0) {
                                    CGContextTranslateCTM(context, left, top + height)
                                    CGContextScaleCTM(context, 1.0, -1.0)
                                    CGContextSetTextMatrix(context, CGAffineTransformMakeScale(1.0, 1.0))
                                    CGPathCreateWithRect(CGRectMake(0.0, 0.0, width, height), null).withOwnedCF { path ->
                                        CTFramesetterCreateFrame(setter, CFRangeMake(0, 0), path, null).withOwnedCF { frame ->
                                            if (command.anchorY == TextAnchorY.Baseline) memScoped {
                                                val first = alloc<CGPoint>()
                                                if (CFArrayGetCount(CTFrameGetLines(frame)) > 0) {
                                                    CTFrameGetLineOrigins(frame, CFRangeMake(0, 1), first.ptr)
                                                    CGContextTranslateCTM(context, 0.0, top + height - command.origin.y - first.y)
                                                }
                                            }
                                            CTFrameDraw(frame, context)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                styledFont?.let { CFRelease(it) }
            }
        }
    }
}

private fun drawArrowHead(context: CGContextRef, tip: Point, tail: Point, head: ArrowHead, color: Color) {
    if (head == ArrowHead.None) return
    val angle = atan2((tip.y - tail.y).toDouble(), (tip.x - tail.x).toDouble())
    fun point(distance: Double, offset: Double): Point = Point(
        (tip.x - distance * cos(angle) - offset * sin(angle)).toFloat(),
        (tip.y - distance * sin(angle) + offset * cos(angle)).toFloat(),
    )
    setFill(context, color)
    CGContextBeginPath(context)
    if (head == ArrowHead.Circle || head == ArrowHead.OpenCircle) {
        CGContextAddEllipseInRect(context, CGRectMake(tip.x - 4.0, tip.y - 4.0, 8.0, 8.0))
    } else {
        val points = when (head) {
            ArrowHead.Triangle -> listOf(tip, point(8.0, -4.0), point(8.0, 4.0), tip)
            ArrowHead.Diamond -> listOf(tip, point(8.0, -4.0), point(12.8, 0.0), point(8.0, 4.0), tip)
            ArrowHead.OpenTriangle -> listOf(point(8.0, -4.0), tip, point(8.0, 4.0))
            ArrowHead.OpenDiamond -> listOf(tip, point(4.0, -8.0 / 3.0), point(8.0, 0.0), point(4.0, 8.0 / 3.0), tip)
            ArrowHead.Bar -> listOf(point(0.0, -4.0), point(0.0, 4.0))
            ArrowHead.Cross -> listOf(point(4.0, -4.0), point(-4.0, 4.0), point(4.0, 4.0), point(-4.0, -4.0))
            ArrowHead.None, ArrowHead.Circle, ArrowHead.OpenCircle -> error("Non-polygon arrow head")
        }
        points.forEachIndexed { index, p ->
            if (index == 0 || (head == ArrowHead.Cross && index == 2)) CGContextMoveToPoint(context, p.x.toDouble(), p.y.toDouble())
            else CGContextAddLineToPoint(context, p.x.toDouble(), p.y.toDouble())
        }
        if (head == ArrowHead.Triangle || head == ArrowHead.Diamond || head == ArrowHead.OpenDiamond) CGContextClosePath(context)
    }
    if (head == ArrowHead.Triangle || head == ArrowHead.Diamond || head == ArrowHead.Circle) CGContextFillPath(context)
    else CGContextStrokePath(context)
}

/** 使用 UTF-16 长度保留非 BMP 字符与内嵌 NUL，不能把正文当 C 字符串。 */
private fun String.toCFString(): CFStringRef? = memScoped {
    val chars = allocArray<UShortVar>(length)
    forEachIndexed { index, char -> chars[index] = char.code.toUShort() }
    CFStringCreateWithCharacters(null, chars, length.toLong())
}
