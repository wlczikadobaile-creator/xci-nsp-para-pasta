package com.nsxpasta.core

/**
 * Chaves no formato prod.keys (gerado pelo Lockpick_RCM a partir do SEU console).
 * Usa as chaves já derivadas quando existem; senão tenta derivar a partir das *_source + master_key_XX,
 * do mesmo jeito que o hactool faz.
 */
class KeySet(private val keys: Map<String, ByteArray>) {

    companion object {
        fun parse(text: String): KeySet {
            val map = HashMap<String, ByteArray>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore(';').substringBefore('#').trim()
                val sep = line.indexOfFirst { it == '=' || it == ',' }
                if (sep <= 0) continue
                val name = line.substring(0, sep).trim().lowercase()
                val value = line.substring(sep + 1).trim()
                runCatching { value.hexToBytes() }.getOrNull()?.let { map[name] = it }
            }
            return KeySet(map)
        }
    }

    val count: Int get() = keys.size

    private fun idx(name: String, rev: Int) = keys["%s_%02x".format(name, rev)]

    private fun generateKek(src: ByteArray, masterKey: ByteArray, kekSeed: ByteArray, keySeed: ByteArray?): ByteArray {
        val kek = Crypto.ecbDecrypt(masterKey, kekSeed)
        val srcKek = Crypto.ecbDecrypt(kek, src)
        return if (keySeed != null) Crypto.ecbDecrypt(srcKek, keySeed) else srcKek
    }

    val headerKey: ByteArray? by lazy {
        keys["header_key"]?.takeIf { it.size == 32 } ?: run {
            val kekSrc = keys["header_kek_source"] ?: return@run null
            val keySrc = keys["header_key_source"] ?: return@run null
            val mk = idx("master_key", 0) ?: return@run null
            val kekGen = keys["aes_kek_generation_source"] ?: return@run null
            val headerKek = generateKek(kekSrc, mk, kekGen, keys["aes_key_generation_source"])
            Crypto.ecbDecrypt(headerKek, keySrc)
        }
    }

    /** type: 0 = application, 1 = ocean, 2 = system. */
    fun keyAreaKey(type: Int, rev: Int): ByteArray? {
        val name = when (type) {
            0 -> "application"; 1 -> "ocean"; 2 -> "system"; else -> return null
        }
        idx("key_area_key_$name", rev)?.let { return it }
        val src = keys["key_area_key_${name}_source"] ?: return null
        val mk = idx("master_key", rev) ?: return null
        val kekGen = keys["aes_kek_generation_source"] ?: return null
        return generateKek(src, mk, kekGen, keys["aes_key_generation_source"])
    }

    fun titleKek(rev: Int): ByteArray? {
        idx("titlekek", rev)?.let { return it }
        val src = keys["titlekek_source"] ?: return null
        val mk = idx("master_key", rev) ?: return null
        return Crypto.ecbDecrypt(mk, src)
    }
}
