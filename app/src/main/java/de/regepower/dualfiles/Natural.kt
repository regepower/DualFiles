package de.regepower.dualfiles

/**
 * "Natural" order for names: runs of digits compare by their number, so "IMG_9" comes before "IMG_10"
 * and "Datei2" before "Datei10". Letters compare as given (the callers pass lower-case keys).
 */
internal object Natural : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Skip leading zeros, then the longer number is bigger, else the first different digit
                val sa = i
                val sb = j
                while (i < a.length && a[i] == '0') i++
                while (j < b.length && b[j] == '0') j++
                val na = i
                val nb = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val la = i - na
                val lb = j - nb
                if (la != lb) return la - lb
                for (k in 0 until la) {
                    val d = a[na + k] - b[nb + k]
                    if (d != 0) return d
                }
                // Same number: fewer leading zeros first ("1" before "01")
                val za = na - sa
                val zb = nb - sb
                if (za != zb) return za - zb
            } else {
                if (ca != cb) return ca - cb
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
