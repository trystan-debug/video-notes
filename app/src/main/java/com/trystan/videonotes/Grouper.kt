package com.trystan.videonotes

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Free, on-phone topic grouping. Turns each transcript into weighted keywords,
 * then files the note into the folder whose notes sound most alike.
 * If nothing is close, it starts a new folder named after the note's top two words.
 */
object Grouper {
    private val STOP: Set<String> = """
        a about above after again against all am an and any are as at be because been before being below
        between both but by can cannot did do does doing done down during each few for from further had has
        have having he her here hers herself him himself his how i if in into is it its itself just me more
        most my myself no nor not now of off on once only or other our ours out over own same she should so
        some such than that the their theirs them then there these they this those through to too under until
        up very was we were what when where which while who whom why will with you your yours yourself
        um uh umm uhh hmm like yeah yes yep okay ok gonna wanna gotta really know think thing things going get
        got getting would could also actually right well want need make made lot lots kind sort stuff way
        something anything everything nothing maybe basically literally pretty much said say says saying see
        look looking let lets come came take went goes back still even around one two three first last next
        little big good great new day today time guy guys hey hi hello thank thanks oh mean means probably
        definitely sure tell told put use used try trying start started give gave keep kept talk talking
        ive youre theyre were dont doesnt didnt cant wont thats theres heres whats its im id ill youll weve
    """.trim().split(Regex("\\s+")).toSet()

    const val MATCH_THRESHOLD = 0.22

    fun stem(w: String): String {
        if (w.endsWith("ies") && w.length > 4) return w.dropLast(3) + "y"
        if (w.endsWith("ing") && w.length > 5) return w.dropLast(3)
        if (w.endsWith("ed") && w.length > 4) return w.dropLast(2)
        if (w.endsWith("es") && w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses"))) return w.dropLast(2)
        if (w.endsWith("s") && !w.endsWith("ss") && w.length > 3) return w.dropLast(1)
        return w
    }

    /** (stem, original word) pairs for every meaningful word. */
    fun terms(text: String): List<Pair<String, String>> =
        Regex("[A-Za-z][A-Za-z']+").findAll(text)
            .map { it.value.lowercase().removeSuffix("'s").replace("'", "") }
            .filter { it.length >= 3 && it !in STOP }
            .map { stem(it) to it }
            .filter { it.first.length >= 3 && it.first !in STOP }
            .toList()

    private fun tf(text: String): Map<String, Double> =
        terms(text).groupingBy { it.first }.eachCount().mapValues { it.value.toDouble() }

    private fun idf(docs: List<Map<String, Double>>): Map<String, Double> {
        val n = docs.size
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.keys.forEach { df.merge(it, 1) { a, b -> a + b } } }
        return df.mapValues { ln((n + 1.0) / (it.value + 1.0)) + 1.0 }
    }

    private fun weigh(tf: Map<String, Double>, idf: Map<String, Double>): Map<String, Double> {
        val v = tf.mapValues { (1.0 + ln(it.value)) * (idf[it.key] ?: 1.0) }
        val norm = sqrt(v.values.sumOf { it * it })
        return if (norm == 0.0) v else v.mapValues { it.value / norm }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val nb = sqrt(b.values.sumOf { it * it })
        if (nb == 0.0) return 0.0
        return a.entries.sumOf { (k, v) -> v * (b[k] ?: 0.0) } / nb
    }

    private fun surfaceWords(text: String): Map<String, String> {
        val counts = HashMap<String, HashMap<String, Int>>()
        terms(text).forEach { (s, w) -> counts.getOrPut(s) { HashMap() }.merge(w, 1) { a, b -> a + b } }
        return counts.mapValues { e -> e.value.maxBy { it.value }.key }
    }

    fun cleanName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9 &\\-]"), "").replace(Regex("\\s+"), " ").trim().take(40)

    private fun isTopicFolder(f: String) = f != Note.INBOX && f != Note.UNSORTED

    /** Picks a folder for [note] by comparing it with every other finished note. */
    fun pickFolder(note: Note, all: List<Note>): String {
        val text = note.fullText
        val noteTf = tf(text)
        if (noteTf.values.sum() < 6) return Note.UNSORTED

        val others = all.filter { it.id != note.id && it.status == Note.STATUS_DONE && isTopicFolder(it.folder) }
        val otherTf = others.map { tf(it.fullText) }
        val idf = idf(otherTf + listOf(noteTf))
        val nv = weigh(noteTf, idf)

        val folderVecs = HashMap<String, HashMap<String, Double>>()
        others.forEachIndexed { i, o ->
            val fv = folderVecs.getOrPut(o.folder) { HashMap() }
            weigh(otherTf[i], idf).forEach { (k, x) -> fv.merge(k, x) { a, b -> a + b } }
        }
        val best = folderVecs.map { (f, v) -> f to cosine(nv, v) }.maxByOrNull { it.second }
        if (best != null && best.second >= MATCH_THRESHOLD) return best.first

        val surface = surfaceWords(text)
        val name = cleanName(
            nv.entries.sortedByDescending { it.value }.take(2)
                .joinToString(" & ") { (surface[it.key] ?: it.key).replaceFirstChar(Char::titlecase) }
        )
        if (name.isBlank()) return Note.UNSORTED
        return folderVecs.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
    }

    /** Top words that make this note different from the rest. Shown as tags. */
    fun keywords(text: String, all: List<Note>, count: Int = 4): List<String> {
        val noteTf = tf(text)
        if (noteTf.isEmpty()) return emptyList()
        val idf = idf(all.filter { it.status == Note.STATUS_DONE }.map { tf(it.fullText) } + listOf(noteTf))
        val surface = surfaceWords(text)
        return weigh(noteTf, idf).entries.sortedByDescending { it.value }.take(count)
            .map { surface[it.key] ?: it.key }
    }
}
