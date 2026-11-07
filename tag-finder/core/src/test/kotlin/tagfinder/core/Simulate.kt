package tagfinder.core

/** Monte-Carlo benchmark: ./gradlew -PcoreOnly :core:simulate */
fun main(args: Array<String>) {
    val trials = args.firstOrNull()?.toInt() ?: 60
    println("Synthetic 6×5 m room, $trials random tag placements (half on a table, half on the floor)")
    println("Horizontal error of the most likely cell (m):")
    println(String.format("%-30s %-7s %8s %8s %8s %10s", "variant", "point", "median", "p80", "mean", "in 50% r"))
    for (v in Variant.entries) for (usePeak in listOf(false, true)) {
        val errs = ArrayList<Double>()
        var covered = 0
        for (seed in 1..trials) {
            val sc = Scenario(seed)
            val r = runVariant(sc, v) ?: continue
            val e = horizontalError(if (usePeak) r.searchPoint else r.map, sc.tag)
            errs += e
            if (e <= r.radius50M) covered++
        }
        errs.sort()
        println(
            String.format(
                "%-30s %-7s %8.2f %8.2f %8.2f %9.0f%%", v.label, if (usePeak) "2-D" else "3-D",
                errs[errs.size / 2], errs[(errs.size * 0.8).toInt()], errs.average(), 100.0 * covered / errs.size,
            ),
        )
    }
}
