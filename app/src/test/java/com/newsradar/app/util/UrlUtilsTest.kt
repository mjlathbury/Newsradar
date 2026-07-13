package com.newsradar.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun normaliseLink_stripsTrackingAndFragment() {
        assertEquals(
            "https://metro.co.uk/x",
            UrlUtils.normaliseLink("https://metro.co.uk/x?utm_source=foo&gclid=1#frag")
        )
    }

    @Test
    fun normaliseLink_preservesRefOnLinks() {
        assertEquals(
            "https://dailymail.com/a?ref=bar",
            UrlUtils.normaliseLink("https://dailymail.co.uk/a?ref=bar")
        )
    }

    @Test
    fun cleanImageUrl_keepsGuardianSignatureIntact() {
        val inUrl = "https://i.guim.co.uk/img/abc?w=140&quality=85&auto=format&fit=max&s=SIG&utm_source=x"
        assertEquals(
            "https://i.guim.co.uk/img/abc?w=140&quality=85&auto=format&fit=max&s=SIG",
            UrlUtils.cleanImageUrl(inUrl)
        )
    }

    @Test
    fun cleanImageUrl_keepsOgImageSignature() {
        // The og:image Guardian serves is itself signed — must NOT be reduced to just ?s=
        val inUrl = "https://i.guim.co.uk/img/hero?width=2000&s=SIG2&auto=webp"
        assertEquals(
            "https://i.guim.co.uk/img/hero?width=2000&s=SIG2&auto=webp",
            UrlUtils.cleanImageUrl(inUrl)
        )
    }

    @Test
    fun cleanImageUrl_stripsResizeForNonSignedHosts() {
        assertEquals(
            "https://metro.co.uk/wp.jpg",
            UrlUtils.cleanImageUrl("https://metro.co.uk/wp.jpg?w=300&utm_source=x")
        )
    }

    @Test
    fun cleanImageUrl_keepsDailyMailTokenAndFullSignedQuery() {
        // Signed hosts keep the FULL original query (signature integrity); only
        // universal tracking is stripped. So resize params survive for DM.
        assertEquals(
            "https://i.dailymail.co.uk/x?token=Z&w=900",
            UrlUtils.cleanImageUrl("https://i.dailymail.co.uk/x?token=Z&w=900&utm_source=x")
        )
    }

    @Test
    fun cleanImageUrl_stripsRefOnImages() {
        assertEquals(
            "https://metro.co.uk/wp.jpg",
            UrlUtils.cleanImageUrl("https://metro.co.uk/wp.jpg?ref=abc&ref_src=xyz")
        )
    }

    @Test
    fun isSignedImageUrl_detectsGuardianAndGetty() {
        assertEquals(true, UrlUtils.isSignedImageUrl("https://i.guim.co.uk/x?s=1"))
        assertEquals(true, UrlUtils.isSignedImageUrl("https://media.gettyimages.com/x"))
        assertEquals(false, UrlUtils.isSignedImageUrl("https://metro.co.uk/x"))
    }

    @Test
    fun cleanImageUrl_nullSafe() {
        assertEquals(null, UrlUtils.cleanImageUrl(null))
        assertEquals(null, UrlUtils.cleanImageUrl(""))
        assertEquals(null, UrlUtils.cleanImageUrl("   "))
    }
}
