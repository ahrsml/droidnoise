package cl.droidnoise.player

import android.content.Context
import java.io.File

/**
 * Banco de parches: `/sdcard/DroidNoise/bank/<ROMANO>/main.pd` + `faders.txt`
 * ("cat: …", "desc: …" y 6 líneas "ETIQUETA default"). El nombre del parche es su número romano.
 */
object Bank {
    data class Fader(val label: String, val default: Float)
    data class Patch(val id: String, val dir: File, val cat: String, val desc: String, val faders: List<Fader>) {
        val main: File get() = File(dir, "main.pd")
        val roman: String get() = id
        val number: Int get() = fromRoman(id)
        val usesSamples: Boolean get() = cat == "samples"
    }

    val root: File get() = File(Paths.root, "bank")
    val lib: File get() = File(Paths.root, "lib")

    private val fallbackFaders = listOf("VOL", "F2", "F3", "F4", "F5", "F6").map { Fader(it, 0.5f) }

    fun scan(): List<Patch> =
        (root.listFiles { f -> f.isDirectory && File(f, "main.pd").isFile } ?: emptyArray())
            .sortedBy { fromRoman(it.name) }
            .map(::read)

    private fun read(dir: File): Patch {
        var cat = "-"
        var desc = ""
        val faders = mutableListOf<Fader>()
        File(dir, "faders.txt").takeIf { it.canRead() }?.readLines()?.forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> {}
                line.startsWith("cat:") -> cat = line.substringAfter(':').trim()
                line.startsWith("desc:") -> desc = line.substringAfter(':').trim()
                line.startsWith("seed:") || line.startsWith("gain:") -> {}
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    faders += Fader(parts[0].uppercase(), parts.getOrNull(1)?.toFloatOrNull() ?: 0.5f)
                }
            }
        }
        while (faders.size < 6) faders += fallbackFaders[faders.size]
        return Patch(dir.name, dir, cat, desc, faders.take(6))
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("bank", Context.MODE_PRIVATE)

    fun currentId(c: Context): String? = prefs(c).getString("current", null)
    fun setCurrentId(c: Context, id: String) = prefs(c).edit().putString("current", id).apply()

    fun current(c: Context, patches: List<Patch> = scan()): Patch? =
        patches.firstOrNull { it.id == currentId(c) } ?: patches.firstOrNull()

    fun isFavorite(c: Context, id: String) = prefs(c).getStringSet("fav", emptySet())!!.contains(id)
    fun toggleFavorite(c: Context, id: String) {
        val set = prefs(c).getStringSet("fav", emptySet())!!.toMutableSet()
        if (!set.add(id)) set.remove(id)
        prefs(c).edit().putStringSet("fav", set).apply()
    }

    fun fromRoman(s: String): Int {
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in s.indices) {
            val x = v[s[i]] ?: return Int.MAX_VALUE
            val next = if (i + 1 < s.length) v[s[i + 1]] ?: 0 else 0
            total += if (x < next) -x else x
        }
        return total
    }

    fun roman(n: Int): String {
        if (n <= 0) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        return buildString { for (i in v.indices) while (x >= v[i]) { append(s[i]); x -= v[i] } }
    }
}
