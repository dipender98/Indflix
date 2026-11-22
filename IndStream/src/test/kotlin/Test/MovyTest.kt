package Test

import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovyTest {

    @Test
    fun movy_decrypt_pinnedVector() {
        // Captured live from the upstream API (2026); multi-quality movie sources.
        val seed = "59844493.b9mTUnMgSYVBwNFuYi9e_X"
        val cipher = "RMiP6gpv9YAZr2X7FBWLD37vRV6PAZXvNEvq9R1ME4ZPoVGUx6HppJik9hLRwkbyGSlo39t36zFp-8IgRulQq-_GbBF0ke2mn7pu9ocaNigK0smrhduSPzPwNAexHyvXt_3wwXpteask9fmrRf3tc-nPsA4DlUVkXcjLGnuoGZ5G9l3XWgvH_kplDDD6e2pGN99zS9B4Al_1DwGk3mIHs-VONO3l8oIb4GDVHgm4iKuhv5qeKS0kUoBCNIs66FWd6yLwf-Am5qEZMEXzeEq2n-SegpwoUlfyVa3EWCmW0GRLfi6HxurF0M9zAtz1p21EqECVRFvelMp-kxTJElNz6GqpqNSJ0pJ4dgnAPBE3fU7Qdo288-JoWadcCNcOU-Ne2WzhpOA5w0gBzAynBuHWCAD-pohSR2KheTVx7kO7CxcIe34KuP5gJ4NtGVSVYss8e6qg8iCHj5BXVJYSNPFgWRgooxuAnYw41tJZIG6c_AEc8xqiX5o6GZZ-MA4K2usBgXe1GB72CIZw1pzTd2jrOfvN6TUT9d46yIThfg7pNr42hweCtyDHWShgAal3Q6IAXdBkHd4-HVKv2TqZXo07ZvXT9rpKKB7_cD4LgKaRjXJud0vV6HVzsqvh0AVHUBd2Jwz6V5Frg_HvFEsuWlTvsK5relp_pw6-rYlO-j2tVTnbXF8D6VEtFL4fKGsEgEEDl5FmPM1h8w5w_evKROp8gXMoZW5pspO0-xiy2b-PFy8estJ0yoFDf64e8Welgp8moyAHdQYBYMfOLPS0Km-jQMD5H6HLaCBKQ2izTmGoi8u9r9mCNbL9rzPNxt4EtLE3_v-fghqyXlDp48uSU56jwtp3A8eJB8Aa3UtSdJOBj_DQplW0oBFUWuIOfssHiOZV0wGAUKfHxAYGTUEk6-lEp1oKsXIaRw2OOr_TfrXeoIZuoMCW9rpEhBZ05P7yhBGTJJruZRUAtASpLWhyKFDonrve9URes18QW9G-F76HAxumgm5MfaTu1UbCgRVY3bzvPlxsKj7v_e5hBhCz1PX38Yksgoz_FD27VCl3jwtWpgGAR7cjPiBaaGcUi3vHdxdFomF2MU4GsMxV9i2FAyPDV0jhDdENUwsUmZIg1LoW8hanesW5OaxCJLFUgz9lCA15keoDOU8aUl0KyxR8lW-6kLekXFp0lqomhc6acuNFmKVCsoe5ShWKusW-k1RE7oY5zqEI_dy8Wy25F4K0PGt7pILovM2LMQ31mrGnHayrjFJHDsEvKUcuvalhebhZQI5kQ0eI_qhuti66k552XHj404xKn7DJthGg73qAYIGSsf_dYs5GnyivxjTUdX-3662Q8SW7M7LsKr5ZM487Flvp9dP94z8wyc5hOIgcIa0OX-4AldLYKCIxbPLtM_rbtLWIcz6v0wlwPs_re1v-uh-X5f6ydUkCphcun3p_q9as_XBgAEYA1mfcWsGQ39FUELcK354KaOyRfmP3XE9a28pN7EXydC58CoFSX6kQDMpbpBIgSuChIWC5ZpOh6qGZaBbpy-fTUSZN-VDD33Ld5f4v9sdPC1Zx6-smG2SYjNAFLC0fOWWoJ7QQxWFVNEclByRrrdmTeBQaTG4HyhPa1nkeBP24"
        val expected = """{"sources":[{"quality":"1080p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s1080p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"},{"quality":"720p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s720p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"},{"quality":"480p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s480p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"},{"quality":"2160p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s2160p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"}],"subtitles":[],"thumbnail":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/thumbs/thumbs.vtt","playlist":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/master.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"}"""
        assertEquals(expected, StreamEngine.movyDecrypt(cipher, seed, 872906))
        assertNull(StreamEngine.movyDecrypt(cipher, "wrong-seed", 872906))
    }

    @Test
    fun movy_parseSources() {
        val out = StreamEngine.movySources("""{"sources":[{"quality":"Hindi","url":"https://x.example/h.m3u8"}],"subtitles":[]}""")
        assertEquals(1, out.size)
        assertEquals("Hindi", out[0].quality)
        assertTrue(StreamEngine.movySources("nope").isEmpty())
    }
}
