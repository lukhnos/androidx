/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.material.textfield

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.preferredSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.TextFieldScroller
import androidx.compose.material.TextFieldScrollerPosition
import androidx.compose.material.iconPadding
import androidx.compose.runtime.remember
import androidx.compose.runtime.savedinstancestate.rememberSavedInstanceState
import androidx.compose.testutils.assertPixels
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.ValueElement
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performGesture
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalFoundationApi::class)
class TextFieldImplTest {

    private val TextfieldTag = "textField"

    private val LONG_TEXT = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do " +
        "eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam," +
        " quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. " +
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu " +
        "fugiat nulla pariatur."

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun before() {
        isDebugInspectorInfoEnabled = true
    }

    @After
    fun after() {
        isDebugInspectorInfoEnabled = false
    }

    @Test
    fun testTextField_scrollable_withLongInput() {
        val scrollerPosition = TextFieldScrollerPosition()
        rule.setContent {
            Box {
                TextFieldScroller(
                    remember { scrollerPosition },
                    Modifier.preferredSize(width = 300.dp, height = 50.dp)
                ) {
                    BasicTextField(
                        value = TextFieldValue(LONG_TEXT),
                        onValueChange = {}
                    )
                }
            }
        }

        rule.runOnIdle {
            assertThat(scrollerPosition.maximum).isLessThan(Float.POSITIVE_INFINITY)
            assertThat(scrollerPosition.maximum).isGreaterThan(0f)
        }
    }

    @Test
    fun testTextField_notScrollable_withShortInput() {
        val text = "text"
        val scrollerPosition = TextFieldScrollerPosition()
        rule.setContent {
            Box {
                TextFieldScroller(
                    remember { scrollerPosition },
                    Modifier.preferredSize(width = 300.dp, height = 50.dp)
                ) {
                    BasicTextField(
                        value = TextFieldValue(text),
                        onValueChange = {}
                    )
                }
            }
        }

        rule.runOnIdle {
            assertThat(scrollerPosition.maximum).isEqualTo(0f)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    fun testTextField_scrolledAndClipped() {
        val scrollerPosition = TextFieldScrollerPosition()

        val parentSize = 200
        val textFieldSize = 50

        with(rule.density) {
            rule.setContent {
                Box(
                    Modifier
                        .preferredSize(parentSize.toDp())
                        .background(color = Color.White)
                        .testTag(TextfieldTag)
                ) {
                    TextFieldScroller(
                        remember { scrollerPosition },
                        Modifier.preferredSize(textFieldSize.toDp())
                    ) {
                        BasicTextField(
                            value = TextFieldValue(LONG_TEXT),
                            onValueChange = {}
                        )
                    }
                }
            }
        }

        rule.runOnIdle {}

        rule.onNodeWithTag(TextfieldTag)
            .captureToImage()
            .assertPixels(expectedSize = IntSize(parentSize, parentSize)) { position ->
                if (position.x > textFieldSize && position.y > textFieldSize) Color.White else null
            }
    }

    @Test
    fun testTextField_swipe_whenLongInput() {
        val scrollerPosition = TextFieldScrollerPosition()

        rule.setContent {
            Box {
                TextFieldScroller(
                    remember { scrollerPosition },
                    Modifier.preferredSize(width = 300.dp, height = 50.dp).testTag(TextfieldTag)
                ) {
                    BasicTextField(
                        value = TextFieldValue(LONG_TEXT),
                        onValueChange = {}
                    )
                }
            }
        }

        rule.runOnIdle {
            assertThat(scrollerPosition.current).isEqualTo(0f)
        }

        rule.onNodeWithTag(TextfieldTag)
            .performGesture { swipeDown() }

        val firstSwipePosition = rule.runOnIdle {
            scrollerPosition.current
        }
        assertThat(firstSwipePosition).isGreaterThan(0f)

        rule.onNodeWithTag(TextfieldTag)
            .performGesture { swipeUp() }
        rule.runOnIdle {
            assertThat(scrollerPosition.current).isLessThan(firstSwipePosition)
        }
    }

    @Test
    fun textFieldScroller_restoresScrollerPosition() {
        val restorationTester = StateRestorationTester(rule)
        var scrollerPosition = TextFieldScrollerPosition()

        restorationTester.setContent {
            scrollerPosition = rememberSavedInstanceState(
                saver = TextFieldScrollerPosition.Saver
            ) {
                TextFieldScrollerPosition()
            }
            TextFieldScroller(
                scrollerPosition,
                Modifier.preferredSize(width = 300.dp, height = 50.dp).testTag(TextfieldTag)
            ) {
                BasicTextField(
                    value = TextFieldValue(LONG_TEXT),
                    onValueChange = {}
                )
            }
        }

        rule.onNodeWithTag(TextfieldTag)
            .performGesture { swipeDown() }

        val swipePosition = rule.runOnIdle {
            scrollerPosition.current
        }
        assertThat(swipePosition).isGreaterThan(0f)

        rule.runOnIdle {
            scrollerPosition = TextFieldScrollerPosition()
            assertThat(scrollerPosition.current).isEqualTo(0f)
        }

        restorationTester.emulateSavedInstanceStateRestore()

        rule.runOnIdle {
            assertThat(scrollerPosition.current).isEqualTo(swipePosition)
        }
    }

    @Test
    fun testInspectorValue() {
        val modifier = Modifier.iconPadding(10.0.dp, 200.0.dp) as InspectableValue
        assertThat(modifier.nameFallback).isEqualTo("iconPadding")
        assertThat(modifier.valueOverride).isNull()
        assertThat(modifier.inspectableElements.asIterable()).containsExactly(
            ValueElement("start", 10.0.dp),
            ValueElement("end", 200.0.dp)
        )
    }
}