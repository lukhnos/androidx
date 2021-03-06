/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.InternalTextApi
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextDelegate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.input.EditOperation
import androidx.compose.ui.text.input.EditProcessor
import androidx.compose.ui.text.input.FinishComposingTextEditOp
import androidx.compose.ui.text.input.INVALID_SESSION
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.InputSessionToken
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.OffsetMap
import androidx.compose.ui.text.input.SetSelectionEditOp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TextInputService
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import kotlin.jvm.JvmStatic
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// visible for testing
internal const val DefaultWidthCharCount = 10 // min width for TextField is 10 chars long
private val EmptyTextReplacement = "H".repeat(DefaultWidthCharCount) // just a reference character.

/**
 * Computed the default width and height for TextField.
 *
 * The bounding box or x-advance of the empty text is empty, i.e. 0x0 box or 0px advance. However
 * this is not useful for TextField since text field want to reserve some amount of height for
 * accepting touch for starting text input. In Android, uses FontMetrics of the first font in the
 * fallback chain to compute this height, this is because custom font may have different
 * ascender/descender from the default font in Android.
 *
 * Until we have font metrics APIs, use the height of reference text as a workaround.
 */
private fun computeSizeForEmptyText(
    style: TextStyle,
    density: Density,
    resourceLoader: Font.ResourceLoader
): IntSize {
    val paragraph = Paragraph(
        text = EmptyTextReplacement,
        style = style,
        spanStyles = listOf(),
        maxLines = 1,
        ellipsis = false,
        density = density,
        resourceLoader = resourceLoader,
        width = Float.POSITIVE_INFINITY
    )
    return IntSize(paragraph.minIntrinsicWidth.toIntPx(), paragraph.height.toIntPx())
}

private fun Float.toIntPx(): Int = ceil(this).roundToInt()

/** @suppress **/
@OptIn(
    InternalTextApi::class,
    ExperimentalTextApi::class
)
@InternalTextApi
class TextFieldDelegate {
    companion object {
        /**
         * Process text layout with given constraint.
         *
         * @param textDelegate The text painter
         * @param constraints The layout constraints
         * @return the bounding box size(width and height) of the layout result
         */
        @JvmStatic
        internal fun layout(
            textDelegate: TextDelegate,
            constraints: Constraints,
            layoutDirection: LayoutDirection,
            maxLines: Int = Int.MAX_VALUE,
            prevResultText: TextLayoutResult? = null
        ): Triple<Int, Int, TextLayoutResult> {
            val layoutResult = textDelegate.layout(constraints, layoutDirection, prevResultText)

            var height = layoutResult.size.height
            var width = layoutResult.size.width

            val constrainedWithDefaultSize = constrainWithDefaultSize(
                textDelegate,
                layoutResult,
                constraints,
                layoutDirection,
                width,
                height
            )

            height = constrainedWithDefaultSize.height
            width = constrainedWithDefaultSize.width

            height = constrainWithMaxLines(maxLines, height, layoutResult)

            return Triple(width, height, layoutResult)
        }

        private fun constrainWithMaxLines(
            maxLines: Int,
            height: Int,
            layoutResult: TextLayoutResult
        ): Int {
            return if (maxLines == Int.MAX_VALUE || layoutResult.lineCount <= maxLines) {
                height
            } else {
                ceil(layoutResult.getLineBottom(maxLines - 1)).toInt()
            }
        }

        private fun constrainWithDefaultSize(
            textDelegate: TextDelegate,
            layoutResult: TextLayoutResult,
            constraints: Constraints,
            layoutDirection: LayoutDirection,
            width: Int,
            height: Int
        ): IntSize {
            val isEmptyText = textDelegate.text.text.isEmpty()
            val needDefaultWidth = layoutResult.size.width < constraints.maxWidth

            val defaultSize = if (isEmptyText || needDefaultWidth) {
                computeSizeForEmptyText(
                    style = resolveDefaults(textDelegate.style, layoutDirection),
                    density = textDelegate.density,
                    resourceLoader = textDelegate.resourceLoader
                )
            } else {
                IntSize.Zero
            }

            val newHeight = if (isEmptyText) {
                constraints.constrainHeight(defaultSize.height)
            } else {
                height
            }

            val newWidth = if (needDefaultWidth) {
                constraints.constrainWidth(max(defaultSize.width, layoutResult.size.width))
            } else {
                width
            }

            return IntSize(newWidth, newHeight)
        }

        /**
         * Draw the text content to the canvas
         *
         * @param canvas The target canvas.
         * @param value The editor state
         * @param offsetMap The offset map
         * @param selectionColor The selection color
         */
        @JvmStatic
        internal fun draw(
            canvas: Canvas,
            value: TextFieldValue,
            offsetMap: OffsetMap,
            textLayoutResult: TextLayoutResult,
            selectionColor: Color
        ) {
            if (!value.selection.collapsed) {
                val start = offsetMap.originalToTransformed(value.selection.min)
                val end = offsetMap.originalToTransformed(value.selection.max)
                if (start != end) {
                    val selectionPath = textLayoutResult.getPathForRange(start, end)
                    canvas.drawPath(selectionPath, Paint().apply { this.color = selectionColor })
                }
            }
            TextPainter.paint(canvas, textLayoutResult)
        }

        /**
         * Notify system that focused input area.
         *
         * System is typically scrolled up not to be covered by keyboard.
         *
         * @param value The editor model
         * @param textDelegate The text delegate
         * @param layoutCoordinates The layout coordinates
         * @param textInputService The text input service
         * @param token The current input session token.
         * @param hasFocus True if focus is gained.
         * @param offsetMap The mapper from/to editing buffer to/from visible text.
         */
        @JvmStatic
        internal fun notifyFocusedRect(
            value: TextFieldValue,
            textDelegate: TextDelegate,
            textLayoutResult: TextLayoutResult,
            layoutCoordinates: LayoutCoordinates,
            textInputService: TextInputService,
            token: InputSessionToken,
            hasFocus: Boolean,
            offsetMap: OffsetMap
        ) {
            if (!hasFocus) {
                return
            }

            val bbox = if (value.selection.max < value.text.length) {
                textLayoutResult.getBoundingBox(
                    offsetMap.originalToTransformed(value.selection.max)
                )
            } else if (value.selection.max != 0) {
                textLayoutResult.getBoundingBox(
                    offsetMap.originalToTransformed(value.selection.max) - 1
                )
            } else {
                val defaultSize = computeSizeForEmptyText(
                    textDelegate.style,
                    textDelegate.density,
                    textDelegate.resourceLoader
                )
                Rect(0f, 0f, 1.0f, defaultSize.height.toFloat())
            }
            val globalLT = layoutCoordinates.localToRoot(Offset(bbox.left, bbox.top))

            textInputService.notifyFocusedRect(
                token,
                Rect(Offset(globalLT.x, globalLT.y), Size(bbox.width, bbox.height))
            )
        }

        /**
         * Called when edit operations are passed from TextInputService
         *
         * @param ops A list of edit operations.
         * @param editProcessor The edit processor
         * @param onValueChange The callback called when the new editor state arrives.
         */
        @JvmStatic
        internal fun onEditCommand(
            ops: List<EditOperation>,
            editProcessor: EditProcessor,
            onValueChange: (TextFieldValue) -> Unit
        ) {
            onValueChange(editProcessor.onEditCommands(ops))
        }

        /**
         * Sets the cursor position. Should be called when TextField has focus.
         *
         * @param position The event position in composable coordinate.
         * @param textLayoutResult The text layout result
         * @param editProcessor The edit processor
         * @param offsetMap The offset map
         * @param onValueChange The callback called when the new editor state arrives.
         */
        @JvmStatic
        internal fun setCursorOffset(
            position: Offset,
            textLayoutResult: TextLayoutResult,
            editProcessor: EditProcessor,
            offsetMap: OffsetMap,
            onValueChange: (TextFieldValue) -> Unit
        ) {
            val offset = offsetMap.transformedToOriginal(
                textLayoutResult.getOffsetForPosition(position)
            )
            onEditCommand(
                listOf(SetSelectionEditOp(offset, offset)),
                editProcessor,
                onValueChange
            )
        }

        /**
         * Called when the composable gained input focus
         *
         * @param textInputService The text input service
         * @param value The editor state
         * @param editProcessor The edit processor
         * @param onValueChange The callback called when the new editor state arrives.
         * @param onImeActionPerformed The callback called when the editor action arrives.
         * @param imeOptions Keyboard configuration such as single line, auto correct etc.
         */
        @JvmStatic
        internal fun onFocus(
            textInputService: TextInputService?,
            value: TextFieldValue,
            editProcessor: EditProcessor,
            imeOptions: ImeOptions,
            onValueChange: (TextFieldValue) -> Unit,
            onImeActionPerformed: (ImeAction) -> Unit
        ): InputSessionToken {
            val inputSessionToken = textInputService?.startInput(
                value = TextFieldValue(value.text, value.selection, value.composition),
                imeOptions = imeOptions,
                onEditCommand = { onEditCommand(it, editProcessor, onValueChange) },
                onImeActionPerformed = onImeActionPerformed
            ) ?: INVALID_SESSION

            textInputService?.showSoftwareKeyboard(inputSessionToken)

            return inputSessionToken
        }

        /**
         * Called when the composable loses input focus
         *
         * @param textInputService The text input service
         * @param token The current input session token.
         * @param editProcessor The edit processor
         * @param onValueChange The callback called when the new editor state arrives.
         */
        @JvmStatic
        internal fun onBlur(
            textInputService: TextInputService?,
            token: InputSessionToken,
            editProcessor: EditProcessor,
            hasNextClient: Boolean,
            onValueChange: (TextFieldValue) -> Unit
        ) {
            onEditCommand(listOf(FinishComposingTextEditOp()), editProcessor, onValueChange)
            textInputService?.stopInput(token)
            if (!hasNextClient) {
                textInputService?.hideSoftwareKeyboard(token)
            }
        }

        /**
         *  Apply the composition text decoration (undeline) to the transformed text.
         *
         *  @param compositionRange An input state
         *  @param transformed A transformed text
         *  @return The transformed text with composition decoration.
         *
         *  @suppress
         */
        @InternalTextApi
        fun applyCompositionDecoration(
            compositionRange: TextRange,
            transformed: TransformedText
        ): TransformedText =
            TransformedText(
                AnnotatedString.Builder(transformed.transformedText).apply {
                    addStyle(
                        SpanStyle(textDecoration = TextDecoration.Underline),
                        transformed.offsetMap.originalToTransformed(compositionRange.start),
                        transformed.offsetMap.originalToTransformed(compositionRange.end)
                    )
                }.toAnnotatedString(),
                transformed.offsetMap
            )
    }
}
