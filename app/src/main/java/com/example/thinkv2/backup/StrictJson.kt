package com.example.thinkv2.backup

import java.io.*
import java.nio.charset.CodingErrorAction

class BackupFailure(val code: String): Exception(code)
internal fun requireBackup(ok: Boolean,code: String="invalid_format") { if(!ok) throw BackupFailure(code) }
object BackupLimits {
    const val FILE=64*1024*1024
    const val PAYLOAD=32*1024*1024
    const val RECORDS=10000
    const val RELATIONS=100000
}
internal class BoundedInput(private val source: InputStream,private val maximum: Int): InputStream() {
    private var count=0L
    override fun read(): Int { val b=source.read();if(b>=0 && ++count>maximum) throw BackupFailure("size_limit");return b }
    override fun read(b: ByteArray,off: Int,len: Int): Int {
        val n=source.read(b,off,minOf(len,(maximum-count+1).coerceAtLeast(1).toInt()))
        if(n>0) { count+=n;requireBackup(count<=maximum,"size_limit") };return n
    }
}
internal class BoundedOutput(private val target: OutputStream,private val maximum: Int): OutputStream() {
    private var count=0L
    override fun write(b: Int) { requireBackup(++count<=maximum,"size_limit");target.write(b) }
    override fun write(b: ByteArray,off: Int,len: Int) { count+=len;requireBackup(count<=maximum,"size_limit");target.write(b,off,len) }
}

/** Strict streaming JSON: duplicate keys, malformed UTF-8/surrogates, floats, trailing data and excessive depth are rejected. */
internal class StrictJson(input: InputStream,maximum: Int,private val envelope: Boolean=false) {
    private val reader=InputStreamReader(BoundedInput(input,maximum),Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    private var current=reader.read();private var nodes=0
    private fun advance() { current=reader.read() }
    private fun whitespace() { while(current==32 || current==9 || current==10 || current==13) advance() }
    private fun take(c: Char) { requireBackup(current==c.code);advance() }
    fun parse(): Any? { val v=value(0);whitespace();requireBackup(current==-1);return v }
    private fun value(depth: Int): Any? {
        requireBackup(depth<=32 && ++nodes<=1_000_000,"structure_limit");whitespace()
        return when(current) {
            '{'.code -> {
                advance();whitespace();val result=linkedMapOf<String,Any?>()
                if(current!='}'.code) while(true) {
                    whitespace();requireBackup(current=='"'.code);val key=string(128);requireBackup(!result.containsKey(key),"duplicate_key")
                    requireBackup(result.size<64,"structure_limit");whitespace();take(':');whitespace()
                    result[key]=if(envelope && depth==0 && key=="payload") binaryString() else value(depth+1)
                    whitespace();if(current!=','.code) break;advance()
                }
                take('}');result
            }
            '['.code -> {
                advance();whitespace();val result=mutableListOf<Any?>()
                if(current!=']'.code) while(true) {
                    requireBackup(result.size<BackupLimits.RELATIONS,"count_limit");result+=value(depth+1)
                    whitespace();if(current!=','.code) break;advance()
                }
                take(']');result
            }
            '"'.code -> string()
            't'.code -> { literal("true");true }
            'f'.code -> { literal("false");false }
            'n'.code -> { literal("null");null }
            '-'.code,in '0'.code..'9'.code -> number()
            else -> throw BackupFailure("invalid_format")
        }
    }
    private fun literal(text: String) { for(c in text) take(c) }
    private fun number(): Long {
        val s=StringBuilder();if(current=='-'.code) { s.append('-');advance() }
        requireBackup(current in '0'.code..'9'.code)
        if(current=='0'.code) { s.append('0');advance();requireBackup(current !in '0'.code..'9'.code) }
        else while(current in '0'.code..'9'.code) { requireBackup(s.length<21);s.append(current.toChar());advance() }
        requireBackup(current !in listOf('.'.code,'e'.code,'E'.code))
        return s.toString().toLongOrNull() ?: throw BackupFailure("invalid_number")
    }
    private fun escapedChar(): Char {
        requireBackup(current>=32 && current!='"'.code)
        if(current!='\\'.code) return current.toChar().also { advance() }
        advance();val c=current;advance()
        return when(c) {
            '"'.code -> '"';'\\'.code -> '\\';'/'.code -> '/';'b'.code -> '\b';'f'.code -> '\u000c';'n'.code -> '\n';'r'.code -> '\r';'t'.code -> '\t'
            'u'.code -> { var n=0;repeat(4) { val digit=when(current) { in 48..57 -> current-48;in 65..70 -> current-65+10;in 97..102 -> current-97+10;else -> -1 };requireBackup(digit>=0);n=n*16+digit;advance() };n.toChar() }
            else -> throw BackupFailure("invalid_escape")
        }
    }
    private fun chars(consume: (Char)->Unit) {
        take('"')
        while(current!='"'.code) {
            val c=escapedChar()
            when {
                Character.isHighSurrogate(c) -> { val low=escapedChar();requireBackup(Character.isLowSurrogate(low),"invalid_unicode");consume(c);consume(low) }
                Character.isLowSurrogate(c) -> throw BackupFailure("invalid_unicode")
                else -> consume(c)
            }
        }
        take('"')
    }
    private fun string(maximum: Int=BackupLimits.PAYLOAD): String {
        val s=StringBuilder();chars { requireBackup(s.length<maximum,"size_limit");s.append(it) };return s.toString()
    }
    private fun binaryString(): ByteArray {
        val out=ByteArrayOutputStream();val target=BoundedOutput(out,BackupLimits.PAYLOAD)
        val q=IntArray(4);var size=0;var ended=false
        chars { c ->
            requireBackup(!ended,"invalid_base64")
            val n=if(c=='=') -1 else ALPHABET.indexOf(c);requireBackup(n>=0 || c=='=',"invalid_base64")
            q[size++]=n
            if(size==4) {
                requireBackup(q[0]>=0 && q[1]>=0,"invalid_base64")
                target.write((q[0] shl 2) or (q[1] shr 4))
                if(q[2]<0) { requireBackup(q[3]<0 && q[1] and 15==0,"invalid_base64");ended=true }
                else {
                    target.write((q[1] shl 4) or (q[2] shr 2))
                    if(q[3]<0) { requireBackup(q[2] and 3==0,"invalid_base64");ended=true }
                    else target.write((q[2] shl 6) or q[3])
                }
                size=0
            }
        }
        requireBackup(size==0,"invalid_base64");return out.toByteArray()
    }
    companion object {
        private const val ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun write(value: Any?,out: Writer) {
            when(value) {
                null -> out.write("null")
                is String -> {
                    out.write('"'.code);var i=0
                    while(i<value.length) {
                        val c=value[i++]
                        when(c) {
                            '"' -> out.write("\\\"");'\\' -> out.write("\\\\")
                            else -> when {
                                c.code<32 -> out.write("\\u%04x".format(java.util.Locale.ROOT,c.code))
                                Character.isHighSurrogate(c) -> { requireBackup(i<value.length && Character.isLowSurrogate(value[i]),"invalid_unicode");out.write(c.code);out.write(value[i++].code) }
                                Character.isLowSurrogate(c) -> throw BackupFailure("invalid_unicode")
                                else -> out.write(c.code)
                            }
                        }
                    };out.write('"'.code)
                }
                is Boolean,is Long,is Int -> out.write(value.toString())
                is Map<*,*> -> { out.write('{'.code);value.entries.forEachIndexed { i,e -> if(i>0) out.write(','.code);write(e.key as String,out);out.write(':'.code);write(e.value,out) };out.write('}'.code) }
                is List<*> -> { out.write('['.code);value.forEachIndexed { i,v -> if(i>0) out.write(','.code);write(v,out) };out.write(']'.code) }
                else -> error("unsupported_json_value")
            }
        }
        fun encode(value: Any?,maximum: Int=BackupLimits.PAYLOAD): ByteArray {
            val out=ByteArrayOutputStream();val writer=OutputStreamWriter(BoundedOutput(out,maximum),Charsets.UTF_8)
            write(value,writer);writer.flush();return out.toByteArray()
        }
        fun base64(bytes: ByteArray,out: Writer) {
            var i=0
            while(i<bytes.size) {
                val a=bytes[i++].toInt() and 255;val b=if(i<bytes.size) bytes[i++].toInt() and 255 else -1
                val c=if(i<bytes.size) bytes[i++].toInt() and 255 else -1
                out.write(ALPHABET[a shr 2].code);out.write(ALPHABET[((a and 3) shl 4) or (if(b<0) 0 else b shr 4)].code)
                out.write(if(b<0) '='.code else ALPHABET[((b and 15) shl 2) or (if(c<0) 0 else c shr 6)].code)
                out.write(if(c<0) '='.code else ALPHABET[c and 63].code)
            }
        }
    }
}
