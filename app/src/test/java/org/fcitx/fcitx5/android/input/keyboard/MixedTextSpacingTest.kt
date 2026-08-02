package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedTextSpacingTest {
    @Test fun insertsAtCjkLatinBoundaries() {
        assertTrue(shouldInsertMixedTextSpace('中', "abc"))
        assertTrue(shouldInsertMixedTextSpace('9', "文"))
    }
    @Test fun ignoresSameClassAndPunctuation() {
        assertFalse(shouldInsertMixedTextSpace('中', "文"))
        assertFalse(shouldInsertMixedTextSpace('a', "9"))
        assertFalse(shouldInsertMixedTextSpace('中', ","))
    }
}
