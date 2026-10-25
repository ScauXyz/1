package dev.restricted.backup

import org.junit.Assert.*
import org.junit.Test

class QuarkPolicyTest {
    private val bound = "https://pan.quark.cn/list#/list/all/abc_123"
    @Test fun bindsOnlyConcreteOfficialFolder() {
        assertTrue(QuarkPolicy.binding(bound))
        assertTrue(QuarkPolicy.binding("https://pan.quark.cn/#/list/all/folder"))
        for (url in listOf(QuarkPolicy.LOGIN, "https://pan.quark.cn/list#/list/all/",
            "https://pan.quark.cn/list#/list/all/../private", "$bound/child", "$bound?other=1",
            "https://pan.quark.cn/list?redirect=evil#/list/all/abc", "https://pan.quark.cn/list#/list/all/0", "not a URL")) {
            assertFalse(url, QuarkPolicy.binding(url))
        }
    }
    @Test fun blocksOriginSpoofingAndUnsafeSchemes() {
        for (url in listOf("http://pan.quark.cn/", "https://pan.quark.cn.evil.test/",
            "https://pan.quark.cn@evil.test/", "https://evil@pan.quark.cn/", "https://pan.quark.cn:443/",
            "file:///private", "content://secret", "javascript:alert(1)", "intent://pan.quark.cn/")) {
            assertFalse(url, QuarkPolicy.official(url))
            assertFalse(QuarkPolicy.allows(url, bound, false))
        }
    }
    @Test fun lockedModeRejectsOtherFoldersRootAndUnknownBinding() {
        assertTrue(QuarkPolicy.allows(bound, bound, false))
        for (url in listOf(QuarkPolicy.LOGIN, bound.replace("abc_123", "private"), "$bound/child", "$bound%2fprivate"))
            assertFalse(QuarkPolicy.allows(url, bound, false))
        assertFalse(QuarkPolicy.allows(bound, null, false))
        assertFalse(QuarkPolicy.allows(QuarkPolicy.LOGIN, QuarkPolicy.LOGIN, false))
    }
    @Test fun ownerModeIsExplicitAndStillOfficialOnly() {
        assertTrue(QuarkPolicy.allows(QuarkPolicy.LOGIN, null, true))
        assertFalse(QuarkPolicy.allows(QuarkPolicy.LOGIN, null, false))
        assertFalse(QuarkPolicy.allows("https://example.com", bound, true))
    }
    @Test fun networkDocumentMayOmitFragmentButCannotChangeOriginOrPath() {
        assertTrue(QuarkPolicy.allowsDocument(bound.substringBefore('#'), bound, false))
        assertTrue(QuarkPolicy.allowsDocument(bound, bound, false))
        for (url in listOf("https://pan.quark.cn/", "https://pan.quark.cn/list?redirect=other",
            bound.replace("abc_123", "private"), "https://evil.test/list"))
            assertFalse(url, QuarkPolicy.allowsDocument(url, bound, false))
        assertFalse(QuarkPolicy.allowsDocument("https://pan.quark.cn/list", null, false))
        assertFalse(QuarkPolicy.allows(bound.substringBefore('#'), bound, false))
    }
    @Test fun chooserHonorsWebMimeTypes() {
        assertTrue(QuarkPolicy.accepts("image/jpeg", listOf("image/*")))
        assertTrue(QuarkPolicy.accepts("text/plain", listOf("image/*,text/plain")))
        assertTrue(QuarkPolicy.accepts("application/zip", emptyList()))
        assertFalse(QuarkPolicy.accepts("video/mp4", listOf("image/*")))
        assertFalse(QuarkPolicy.accepts("application/pdf", listOf(".pdf")))
    }
}
