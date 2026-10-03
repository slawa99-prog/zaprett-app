package com.cherret.zaprett.utils

/** Small, bounded search around the selected nfqws strategy's TCP 443 rules. */
object PersonalStrategyMutator {
    data class Variant(val label: String, val content: String)

    private data class Change(val option: String, val value: String, val label: String)

    private val changes = listOf(
        Change("--dpi-desync-split-pos", "1", "разрез 1"),
        Change("--dpi-desync-split-pos", "2", "разрез 2"),
        Change("--dpi-desync-split-pos", "3", "разрез 3"),
        Change("--dpi-desync-split-pos", "5", "разрез 5"),
        Change("--dpi-desync-repeats", "4", "повторы 4"),
        Change("--dpi-desync-repeats", "8", "повторы 8"),
        Change("--dpi-desync-repeats", "12", "повторы 12"),
        Change("--dpi-desync-fooling", "ts", "метод ts"),
        Change("--dpi-desync-fooling", "badseq", "метод badseq"),
        Change("--dpi-desync-fooling", "md5sig", "метод md5sig"),
        Change("--dpi-desync-split-seqovl", "64", "перекрытие 64"),
        Change("--dpi-desync-split-seqovl", "256", "перекрытие 256")
    )

    fun firstStage(seed: String): List<Variant> = variants(seed, setOf(seed), 8)

    /** Combine one promising change with a different parameter value. */
    fun nextStage(best: Variant, testedContents: Set<String>): List<Variant> =
        variants(best.content, testedContents, 4).map {
            it.copy(label = "${best.label} + ${it.label}")
        }

    private fun variants(source: String, excluded: Set<String>, limit: Int): List<Variant> =
        changes.asSequence()
            .mapNotNull { change ->
                changeTcp443(source, change.option, change.value)?.let { Variant(change.label, it) }
            }
            .filter { it.content !in excluded }
            .distinctBy { it.content }
            .take(limit)
            .toList()

    private fun changeTcp443(source: String, option: String, value: String): String? {
        val optionPattern = Regex("(?<!\\S)${Regex.escape(option)}=[^\\s\\\\]+")
        var changed = false
        val updated = source.lines().joinToString("\n") { line ->
            val isYouTubeTcp = Regex("--filter-tcp=(?:443|80,443)(?=\\s|$)").containsMatchIn(line)
            val hasFake = line.contains("--dpi-desync=fake")
            if (!isYouTubeTcp || (option == "--dpi-desync-fooling" && !hasFake) ||
                !optionPattern.containsMatchIn(line)) {
                line
            } else {
                val newLine = optionPattern.replace(line, "$option=$value")
                if (newLine != line) changed = true
                newLine
            }
        }
        return updated.takeIf { changed }
    }
}
