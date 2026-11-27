package Test

import com.indstream.LinkNaming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamingSpecTest {

    @Test
    fun bitrate_parsesAndCanonicalizes() {
        assertEquals("9~Mbps", LinkNaming.bitrateFromText("Film.2024.1080p...9000 Kbps"))
        assertEquals("6.5~Mbps", LinkNaming.bitrateFromText("Film.2024.1080p...6.5 Mbps"))
        assertEquals("8~Mbps", LinkNaming.bitrateFromText("8 Mbps"))
        assertEquals("1.2~Mbps", LinkNaming.bitrateFromText("1200 Kb/s"))
        assertEquals("0.5~Mbps", LinkNaming.bitrateFromText("512 Kbps"))
        assertEquals("0.5~Mbps", LinkNaming.bitrateFromText("500 kbps"))
        assertEquals("0.8~Mbps", LinkNaming.bitrateFromText("800 Kbps"))
        assertEquals("", LinkNaming.bitrateFromText("Film 2024 1080p HEVC"))
        assertEquals("", LinkNaming.bitrateFromText(null))
    }

    @Test
    fun hostLabel_shortensKnownHosts() {
        assertEquals("pixeldrain", LinkNaming.hostLabel("https://pixeldrain.dev/api/file/AbC123"))
        assertEquals("hubcloud", LinkNaming.hostLabel("https://hubcloud.mov/drive/rglhteeruzfzz33"))
        assertEquals("hubcloud", LinkNaming.hostLabel("https://gpdl.hubcloud.mov/?id=abc"))
        assertEquals("gdflix", LinkNaming.hostLabel("https://new10.gdflix.io/file/kudSMDBVKMZZuUC"))
        assertEquals("gdrive", LinkNaming.hostLabel("https://video-downloads.googleusercontent.com/ADGPM2kyzCFLtzlqkT"))
        assertEquals("megaup", LinkNaming.hostLabel("https://megaup.net/a9e1822404c6ceec62c143c19e75c391/file.mkv"))
        assertEquals("cdn", LinkNaming.hostLabel("https://cdn.example/files/direct.mkv"))
        assertEquals("", LinkNaming.hostLabel(null))
        assertEquals("", LinkNaming.hostLabel(""))
    }

    @Test
    fun displayName_hostAfterProviderBeforeIcons() {
        val name = LinkNaming.displayName(
            serverName = "FSL",
            audioLabel = "Hindi",
            sizeLabel = "2.3GB",
            mediaLabel = "Film.2024.1080p.BluRay.HEVC.Hindi.5.1.mkv",
            hostLabel = "pixeldrain",
        )
        assertEquals("FSL · pixeldrain · 💿BluRay · 🎞️HEVC · 🔊5.1 · 💾2.3GB · (🇮🇳 Hindi)", name)
    }

    @Test
    fun displayName_leavesResolutionToThePlayer() {
        val name = LinkNaming.displayName(
            serverName = "FSL 1080p",
            audioLabel = "Hindi",
            mediaLabel = "Film.2024.1080p.BluRay.HEVC.mkv",
            hostLabel = "pixeldrain",
        )
        assertTrue("1080p" !in name, "the player appends the quality itself")
    }

    @Test
    fun renditionTokens_addsStandaloneChannels() {
        val tokens = LinkNaming.renditionTokens("Film 2024 1080p BluRay HEVC Hindi 5.1")
        assertTrue("HEVC" in tokens)
        assertTrue("BluRay" in tokens)
        assertTrue("5.1" in tokens)
    }

    @Test
    fun renditionTokens_skipsChannelCarriedByAudioProfile() {
        val tokens = LinkNaming.renditionTokens("Film 2024 1080p WEB-DL DDP5.1")
        assertTrue("Dolby 5.1" in tokens)
        assertTrue("5.1" !in tokens, "5.1 is already inside the Dolby 5.1 profile token")
    }

    @Test
    fun displayName_providerFirstThenIconBlockLanguage() {
        val name = LinkNaming.displayName(
            serverName = "FSL",
            audioLabel = "Hindi",
            sizeLabel = "2.3GB",
            mediaLabel = "Film.2024.1080p.BluRay.HEVC.Hindi.5.1.9000 Kbps.mkv",
        )
        assertEquals(
            "FSL · 💿BluRay · 🎞️HEVC · 🔊5.1 · ⚡9~Mbps · 💾2.3GB · (🇮🇳 Hindi)",
            name,
        )
    }

    @Test
    fun displayName_bitrateIconBetweenSpecsAndLanguage() {
        val name = LinkNaming.displayName(
            serverName = "FSL",
            audioLabel = "Hindi",
            mediaLabel = "Film.2024.1080p.WEB-DL.HEVC.9000 Kbps.mkv",
        )
        assertEquals("FSL · ☁️WEB-DL · 🎞️HEVC · ⚡9~Mbps · (🇮🇳 Hindi)", name)
    }
}
