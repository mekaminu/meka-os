package os.meka.core.domain

/*
 * The Vault, slice 2b (2026-10-18): **the document itself.** A photo or PDF of a Vault document (a passport's photo
 * page, an insurance certificate) is encrypted on the phone or Mac with the household's Vault key (slice 2a) before it
 * leaves; MEKA's server keeps only the sealed bytes (S3, ADR-004), and each phone or Mac keeps its own sealed copy so
 * the document opens offline. Pure, non-AI rules; the crypto (AES-256-GCM) is each platform's ([os.meka.core.facade]
 * VaultCrypto).
 *
 * - The sealed box holds a short header naming the copy's id ([VaultFileRules.frame]) before the file's bytes, so a box
 *   moved to another copy's id by a server is refused when opened.
 * - What the household syncs about a copy is one field on its `vault_doc`, [VaultDocFields.FILE] ([VaultFileRef]
 *   packed into one text, so two devices adding a copy at once can't mix halves): its id, type, size, the Vault key's
 *   id it was sealed with, when and on which device. Never a name of the file, never a number from it.
 * - At most [VaultFileRules.MAX_FILE_BYTES] (a photo of a page, re-encoded on the device, fits easily).
 */

/** One stored copy of a document: its random [id] (the server's key), [type] ([VaultFileRules.JPEG]…), [bytes] (plain size). */
data class VaultFileRef(
    val id: String,
    val type: String,
    val bytes: Long,
    val keyId: String,
    val atMs: Long,
    /** "Fold", "Mac". */
    val by: String,
) {
    val isPdf: Boolean get() = type == VaultFileRules.PDF
}

/** What adding, opening or removing a copy said: the line under the row, and whether it failed ([refused]). */
data class VaultFileSaid(val line: String, val refused: Boolean)

/**
 * An opened copy for the screen: [type] (JPEG, PNG or PDF), the file's bytes as base64 ([base64]: only Strings cross
 * to the Mac), and the document's [title]; or, when it couldn't be opened, [problem] and no bytes. The bytes are never
 * stored outside the app's memory.
 */
data class VaultFileOpened(val title: String, val type: String, val base64: String?, val problem: String?) {
    val isPdf: Boolean get() = type == VaultFileRules.PDF

    companion object {
        fun problem(title: String, line: String) = VaultFileOpened(title, "", null, line)
    }
}

/** What [VaultFileRules.open] made of a downloaded or kept box. */
sealed interface VaultFileOpen {
    data class Opened(val bytes: ByteArray) : VaultFileOpen
    data class Refused(val line: String) : VaultFileOpen
}

object VaultFileRules {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val PDF = "application/pdf"
    val TYPES = setOf(JPEG, PNG, PDF)

    /** The largest file kept: a page's photo is a few hundred KB; a scanned PDF of a few pages fits. */
    const val MAX_FILE_BYTES = 1_400_000L
    /** A sealed box: the header ([frame]), the file, a 12-byte nonce and a 16-byte tag; base64 of it stays under 2 MB. */
    const val MAX_SEALED_BYTES = 1_450_000
    /** A photo is re-encoded on the device to at most this long side before it's sealed. */
    const val PHOTO_LONG_SIDE = 2_000

    const val DOMAIN = "MEKA-VAULT-FILE-1"
    /** Device-local values: the sealed copy kept for offline (`vault:file:<id>`), and the ids still to upload. */
    const val LOCAL_PREFIX = "vault:file:"
    const val LOCAL_PENDING = "vault:files-pending"
    /** The ids this device keeps a sealed copy of (so copies no document points at any more are let go). */
    const val LOCAL_KEPT = "vault:files-kept"
    /** Ids removed or replaced here whose server copy is still to delete. */
    const val LOCAL_GONE = "vault:files-gone"
    /** Copies fetched ahead for offline per sync round, so a round stays quick. */
    const val PREFETCH_PER_ROUND = 3

    const val ADD = "Add a copy"
    const val REPLACE = "Replace copy"
    const val OPEN = "Open copy"
    const val REMOVE = "Remove copy"
    const val CLOSE = "Close"
    const val NO_KEY =
        "Copies are locked with your Vault key, and this device doesn't hold it yet: see VAULT KEY below."
    const val TOO_BIG = "That file is too big for the Vault (1.4 MB at most). A photo of the page fits."
    const val NOT_TYPE = "The Vault keeps photos (JPEG or PNG) and PDFs."
    const val COULDNT_READ = "Navilife couldn't read that file."
    const val COULDNT_SEAL = "Navilife couldn't lock that file with your Vault key. Nothing was kept."
    const val GONE = "That document isn't in the Vault any more."
    const val NOT_UPLOADED = "This copy isn't on Navilife's server yet: it goes up when %s is next online."
    const val OFFLINE = "Couldn't fetch the copy. Try again with signal."
    const val OTHER_KEY = "This copy was locked with a different Vault key, so it can't be opened here."
    const val CHANGED = "This copy didn't open: it was changed or damaged. Add it again from the original."
    const val REMOVED = "Copy removed from every device and Navilife's server."
    const val NOTE =
        "Copies are encrypted on this device with your Vault key before they leave it; Navilife's server keeps only the locked bytes, and each of your devices keeps its own for offline."

    /** A random id the server accepts as a key: 16–64 letters, digits, `-` or `_`. */
    fun validId(id: String): Boolean = id.length in 16..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun localKey(id: String): String = LOCAL_PREFIX + id

    /** The type the Vault keeps for a file the system names ([mime]) or, failing that, its file name's ending. */
    fun typeOf(mime: String?, name: String? = null): String? {
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        when (m) {
            JPEG, "image/jpg", "image/pjpeg" -> return JPEG
            PNG -> return PNG
            PDF -> return PDF
        }
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> JPEG
            "png" -> PNG
            "pdf" -> PDF
            else -> null
        }
    }

    /** Why a file can't be added, or null when it can. */
    fun refusal(type: String?, bytes: Long): String? = when {
        type == null || type !in TYPES -> NOT_TYPE
        bytes <= 0 -> COULDNT_READ
        bytes > MAX_FILE_BYTES -> TOO_BIG
        else -> null
    }

    private fun header(id: String): ByteArray = "$DOMAIN\n$id\n".encodeToByteArray()

    /** What is sealed: the header naming the copy's id, then the file. */
    fun frame(id: String, file: ByteArray): ByteArray = header(id) + file

    /** The file inside an opened box, or null when the box names a different copy (moved by someone) or none. */
    fun unframe(id: String, plain: ByteArray): ByteArray? {
        val h = header(id)
        if (plain.size < h.size) return null
        for (i in h.indices) if (plain[i] != h[i]) return null
        return plain.copyOfRange(h.size, plain.size)
    }

    // ---- The synced field ----

    /** [VaultFileRef] as one text: `1|id|type|bytes|keyId|atMs|by`. */
    fun encode(r: VaultFileRef): String = listOf("1", r.id, r.type, r.bytes, r.keyId, r.atMs, r.by.replace("|", " ")).joinToString("|")

    /** Null for no copy, a removed one or anything unreadable. */
    fun decode(text: String?): VaultFileRef? {
        val p = text?.split('|') ?: return null
        if (p.size != 7 || p[0] != "1") return null
        val id = p[1].takeIf(::validId) ?: return null
        val type = p[2].takeIf { it in TYPES } ?: return null
        val bytes = p[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val at = p[5].toLongOrNull() ?: return null
        return VaultFileRef(id, type, bytes, p[4], at, p[6])
    }

    // ---- Words ----

    /** "412 KB", "1.2 MB". */
    fun sizeWords(bytes: Long): String = when {
        bytes < 1_000 -> "1 KB"
        bytes < 1_000_000 -> "${(bytes + 500) / 1_000} KB"
        else -> {
            val tenths = (bytes + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} MB"
        }
    }

    /** "Photo", "PDF". */
    fun kindWord(type: String): String = if (type == PDF) "PDF" else "Photo"

    /** Under a row with a copy: "Photo · 412 KB · encrypted · added on the Fold, Sun 18 Oct". */
    fun copyLine(r: VaultFileRef, cal: LocalCalendar, thisDevice: String?): String {
        val where = if (r.by == thisDevice) "added on this ${r.by}" else "added on the ${r.by}"
        return "${kindWord(r.type)} · ${sizeWords(r.bytes)} · encrypted · $where, ${CivilDate.shortLabel(cal.epochDayOf(r.atMs))}"
    }

    /** After adding: "Copy added · locked with your Vault key on this Fold" (+ "· it goes up when you're online"). */
    fun addedLine(r: VaultFileRef, uploaded: Boolean, replaced: Boolean): String {
        val verb = if (replaced) "Copy replaced" else "Copy added"
        val tail = if (uploaded) "only your devices can open it" else "it goes up when this ${r.by} is next online"
        return "$verb · ${kindWord(r.type).lowercase()}, ${sizeWords(r.bytes)}, locked with your Vault key · $tail"
    }

    fun notUploaded(by: String): String = NOT_UPLOADED.replace("%s", "the $by")

    /**
     * Opens a box with [open] (the platform's AES-GCM under the Vault key) after checking which key sealed it: the
     * file's bytes, or the line to show.
     */
    fun open(ref: VaultFileRef, heldKeyId: String?, open: () -> ByteArray?): VaultFileOpen {
        if (heldKeyId == null) return VaultFileOpen.Refused(NO_KEY)
        if (ref.keyId != heldKeyId) return VaultFileOpen.Refused(OTHER_KEY)
        val plain = open() ?: return VaultFileOpen.Refused(CHANGED)
        val file = unframe(ref.id, plain) ?: return VaultFileOpen.Refused(CHANGED)
        return VaultFileOpen.Opened(file)
    }

    /** A list of ids kept on the device ([LOCAL_PENDING], [LOCAL_KEPT], [LOCAL_GONE]): one per line. */
    fun decodeIds(text: String?): List<String> = text?.lines()?.map { it.trim() }?.filter(::validId)?.distinct().orEmpty()

    fun encodeIds(ids: List<String>): String? = ids.filter(::validId).distinct().joinToString("\n").ifEmpty { null }
}
