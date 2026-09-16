package com.example.thinkv2.ai

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded response-only JSON parser. Duplicate keys, invalid Unicode and trailing data fail closed. */
internal class AiJson(bytes: ByteArray) {
    private val s: String
    private var i=0;private var nodes=0
    init { aiCheck(bytes.size<=AiProtocol.RESPONSE_BYTES,"response_limit");s=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
    fun parse(): Any? { val v=value(0);space();aiCheck(i==s.length);return v }
    private fun space() { while(i<s.length && s[i] in " \r\n\t") i++ }
    private fun take(c: Char) { aiCheck(i<s.length && s[i++]==c) }
    private fun value(depth: Int): Any? {
        aiCheck(depth<=8 && ++nodes<=512);space();aiCheck(i<s.length)
        return when(s[i]) {
            '{' -> { i++;space();val m=linkedMapOf<String,Any?>();if(i<s.length && s[i]!='}') while(true) {
                space();val k=string();aiCheck(k.length<=128 && k !in m && m.size<64);space();take(':');m[k]=value(depth+1);space()
                if(i>=s.length || s[i]!=',') break;i++
            };take('}');m }
            '[' -> { i++;space();val a=mutableListOf<Any?>();if(i<s.length && s[i]!=']') while(true) { aiCheck(a.size<64);a+=value(depth+1);space();if(i>=s.length || s[i]!=',') break;i++ };take(']');a }
            '"' -> string()
            't' -> literal("true",true)
            'f' -> literal("false",false)
            'n' -> literal("null",null)
            else -> { val start=i;while(i<s.length && s[i] in "-+0123456789.eE") i++;val n=s.substring(start,i);aiCheck(n.length<=64 && n.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")));n.toBigDecimal() }
        }
    }
    private fun literal(text: String,v: Any?): Any? { aiCheck(s.startsWith(text,i));i+=text.length;return v }
    private fun string(): String {
        take('"');val b=StringBuilder()
        while(i<s.length && s[i]!='"') {
            var c=s[i++];aiCheck(c.code>=32)
            if(c=='\\') { aiCheck(i<s.length);c=when(val e=s[i++]) {
                '"','\\','/' -> e;'b' -> '\b';'f' -> '\u000c';'n' -> '\n';'r' -> '\r';'t' -> '\t'
                'u' -> { aiCheck(i+4<=s.length);val h=s.substring(i,i+4);aiCheck(h.all { it in "0123456789abcdefABCDEF" });i+=4;h.toInt(16).toChar() }
                else -> throw AiFailure("invalid_response")
            } }
            b.append(c)
        };take('"');val result=b.toString();var j=0
        while(j<result.length) { val c=result[j++];if(Character.isHighSurrogate(c)) { aiCheck(j<result.length && Character.isLowSurrogate(result[j++])); } else aiCheck(!Character.isLowSurrogate(c)) }
        return result
    }
}
