package com.example.thinkv2.voice

data class VoiceAnchor(val editorToken: Long,val noteId: String,val revision: Long,val cursor: Int)
data class Correction(val from: String,val to: String)

/** Text-only, explicit suggestions. No hotword bias and no automatic substitution. */
object VoiceText {
    const val MAX_MAPPINGS=200
    fun validate(rows: List<Correction>) {
        require(rows.size<=MAX_MAPPINGS)
        require(rows.distinct().size==rows.size)
        rows.forEach { require(it.from.isNotBlank() && it.to.isNotBlank() && it.from!=it.to)
            require(it.from.length<=80 && it.to.length<=80)
            require(it.from.none { c -> c.isISOControl() } && it.to.none { c -> c.isISOControl() }) }
    }
    fun suggestions(text: String,rows: List<Correction>)=rows.filter { text.contains(it.from) }
    fun cursor(text: String,position: Int): Int {
        val p=position.coerceIn(0,text.length)
        return if(p>0 && p<text.length && text[p].isLowSurrogate() && text[p-1].isHighSurrogate()) p-1 else p
    }
    fun insert(body: String,position: Int,text: String): String {
        val at=cursor(body,position);return body.substring(0,at)+text+body.substring(at)
    }
}

/** Sample-count cap is independent of wall-clock/UI timers. 16 kHz mono signed PCM16. */
class VoiceBudget {
    companion object { const val RATE=16000;const val SECONDS=90;const val LIMIT=RATE*SECONDS;const val CHUNK=1600 }
    var samples=0
        private set
    fun take(requested: Int): Int { require(requested>=0);val count=minOf(requested,LIMIT-samples);samples+=count;return count }
    val full get()=samples==LIMIT
}
