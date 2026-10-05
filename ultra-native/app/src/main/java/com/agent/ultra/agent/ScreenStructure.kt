package com.agent.ultra.agent

import org.json.JSONArray

/**
 * Structured screen reading.
 *
 * A flat list of labels cannot say which price belongs to which product — the
 * pairing has to be inferred, and inference is where a confident wrong answer
 * comes from. The accessibility tree already knows: a results list is a
 * container whose children are each one item, and each item's labels are its
 * own descendants.
 *
 * This rebuilds that tree from the flattened dump and finds the container
 * whose children look like a repeating list of multi-field things. That
 * pattern is not specific to shopping — an inbox, a feed, a chat thread, a
 * settings list and a search results page all have it.
 */
object ScreenStructure {

    data class Node(
        val index: Int,
        val parent: Int,
        val depth: Int,
        val text: String,
        val desc: String,
        val clickable: Boolean,
        val top: Int,
        val bottom: Int,
        /** Widget class, short form: "TextView", "RecyclerView". */
        val cls: String = "",
        /** View id, short form: "price", "title_row". Native apps expose it;
         * web content usually does not. */
        val vid: String = "",
        val editable: Boolean = false,
        val enabled: Boolean = true,
    ) {
        val label: String get() = text.ifBlank { desc }.trim()

        /** What this node *is*, structurally. A view id is definitive when the
         * app provides one; the class is the fallback. */
        val kind: String get() = if (vid.isNotBlank()) "#$vid" else cls
    }

    /**
     * One row of a list: the labels that genuinely belong together.
     *
     * `ids` runs parallel to `labels` and holds the view id the label came
     * from, or "" when the app did not provide one. A native app names its own
     * fields — `alarm_item_time` really is the time — and guessing that from
     * the text with a regex when the app already said it is exactly the
     * re-derivation this file keeps having to unlearn.
     */
    data class Item(
        val labels: List<String>,
        val top: Int,
        val clickable: Boolean,
        val ids: List<String> = emptyList(),
    ) {
        /** Identity across overlapping reads while scrolling. */
        val signature: String get() = labels.joinToString("|").take(240)

        fun idAt(i: Int): String = ids.getOrElse(i) { "" }
    }

    /**
     * A field inside a row, named only when the pattern is unmistakable.
     *
     * `name` is null for anything the classifier does not recognise, and that
     * is the point. Grouping fixed which price belongs to which product; it
     * did not say which of a row's values *is* the price. The row arrived as
     * ordered anonymous lines and the model had to guess from position, which
     * is the same inference problem one level down.
     *
     * A wrong name is worse than no name, because a named field gets trusted.
     * So the engine names what it can prove from the text itself and leaves
     * the rest as plain lines for the model to read.
     */
    data class Field(val name: String?, val value: String)

    fun parse(flatJson: String): List<Node> {
        if (flatJson == com.agent.ultra.AgentAccessibilityService.PROTECTED) return emptyList()
        return try {
        val arr = JSONArray(flatJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Node(
                index = o.optInt("i", i),
                parent = o.optInt("p", -1),
                depth = o.optInt("dep", 0),
                text = o.optString("t").trim(),
                desc = o.optString("d").trim(),
                clickable = o.optBoolean("c", false),
                top = o.optInt("tp", o.optInt("y", 0)),
                bottom = o.optInt("b", o.optInt("y", 0)),
                cls = o.optString("cls"),
                vid = o.optString("vid"),
                editable = o.optBoolean("e", false),
                enabled = o.optBoolean("en", true),
            )
        }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Find the repeating list on screen and return its rows.
     *
     * Empty when the screen has no such structure — a settings page with one
     * control per section, a dialog, an article. The caller falls back to a
     * flat read, which is the honest thing to do rather than inventing groups.
     */
    /**
     * Find the repeating list on screen and return its rows.
     *
     * The winning signal is uniformity, not size. A results grid is a
     * container whose children all carry a similar amount of content —
     * measured on a live Amazon page, the product grid's children had
     * identical label counts. Height is useless here: virtualised rows report
     * zero bounds, which is why an area-based score picked the page banner.
     *
     * The count of items is multiplied by how substantial each one is, where
     * substance is characters of text in the median row. A fourteen-entry
     * navigation menu of two words each loses to a product list carrying
     * titles, ratings and prices — and now loses to a five-entry one, which
     * it did not when substance was counted in labels rather than characters.
     *
     * Empty when the screen has no such structure — a dialog, an article, a
     * settings page. The caller falls back to a flat read, which is honest
     * rather than inventing groups that are not there.
     */
    /**
     * The shape of a node's subtree, ignoring every word of text.
     *
     * Two product cards contain different words and the same structure. That
     * is what makes them the same kind of thing, and it is the fact the reader
     * needs. Depth is capped because the top of a deep card is enough to
     * identify it, and because a full-depth signature makes every node unique
     * the moment one child differs.
     */
    private fun shapeOf(
        n: Node,
        children: Map<Int, MutableList<Node>>,
        depth: Int,
        memo: HashMap<Long, String>,
    ): String {
        val key = n.index.toLong() * 8 + depth
        memo[key]?.let { return it }
        val kids = children[n.index].orEmpty()
        val s = if (depth <= 0 || kids.isEmpty()) {
            n.kind
        } else {
            // The DISTINCT kinds of child, sorted — a set, not a list.
            //
            // Matching the exact sequence of children was too strict for a
            // real page. Store cards are not identical: one carries a
            // "Limited time deal" badge, the next does not, a third has two
            // lines of title instead of one. Comparing exact child lists split
            // one product grid into many groups of two or three, and a swarm
            // of small groups then outscored the real one.
            //
            // What actually identifies a card is the kinds of thing it is made
            // of, not how many of each. A set tolerates the optional badge and
            // the extra line while still separating a product card from a
            // banner.
            n.kind + "{" + kids.map { shapeOf(it, children, depth - 1, memo) }
                .groupingBy { it }.eachCount()
                .map { (kind, count) -> "$kind*${countBand(count)}" }
                .sorted().joinToString(",") + "}"
        }
        memo[key] = s
        return s
    }

    /**
     * Find the repeating record template, using the page's own structure.
     *
     * This replaces scoring containers by how uniform their children looked.
     * That heuristic had to be corrected twice — it read a navigation menu as
     * a product list, and on a real store page it returned eighteen rows from
     * eleven screens, mixing whole product cards with orphaned fragments of
     * other cards.
     *
     * A results page is built from one template repeated. Nodes sharing a
     * structural signature are instances of that template, so the records are
     * the largest such group carrying real content — no scoring, no guess
     * about which container "looks like" a list.
     *
     * Returns an empty list when the tree carries no class information, which
     * is the case for a dump made by an older build. The caller falls back to
     * the previous method rather than failing.
     */
    private fun templateGroups(
        nodes: List<Node>,
        children: Map<Int, MutableList<Node>>,
        charsOf: (Node) -> Int,
        labelsOf: (Node) -> Int,
        minItems: Int,
    ): List<Node> {
        if (nodes.none { it.cls.isNotBlank() }) return emptyList()
        val memo = HashMap<Long, String>()
        val groups = HashMap<String, MutableList<Node>>()
        for (n in nodes) {
            // A record has fields. A leaf IS a field, and there are always more
            // leaves than records — on a twelve-product page the bare TextViews
            // formed a group of thirty and outscored the twelve cards that
            // contain them, so every row came back with a single label and was
            // then discarded as too thin. Requiring two labels is what makes a
            // record a record.
            if (labelsOf(n) < 2) continue
            if (charsOf(n) < MIN_RECORD_CHARS) continue
            val shape = shapeOf(n, children, SHAPE_DEPTH, memo)
            if (shape.length < 4) continue
            groups.getOrPut(shape) { mutableListOf() }.add(n)
        }

        var best: List<Node> = emptyList()
        var bestScore = 0.0
        for ((_, members) in groups) {
            if (members.size < minItems) continue
            // Never let an ancestor and its own descendant both count as
            // records: a nested identical layout would report the card and the
            // block inside it as two results.
            val kept = dropNested(members, nodes)
            if (kept.size < minItems) continue
            // Coverage, not count.
            //
            // Scoring count x median picked the RATING WIDGET on a real store
            // page: twenty-four of them, tidy and identical, nested inside the
            // product cards that were the actual answer. Every record came
            // back as "4.3 out of 5 stars" and nothing else.
            //
            // Total text under the group is what separates an inner widget
            // from the thing that contains it. A rating widget accounts for a
            // sliver of the page; the card group accounts for nearly all of
            // it. The outermost repeating unit wins, which is the one a person
            // would point at and call a result.
            val coverage = kept.sumOf { charsOf(it).toDouble() }
            val score = coverage
            if (score > bestScore ||
                (score > bestScore * NEAR_TIE && kept.size > best.size)
            ) {
                if (score > bestScore) bestScore = score
                best = kept
            }
        }
        return best
    }

    /**
     * Take in the other half of each record when a table splits them.
     *
     * A shape says where the list is; it does not always cover a whole record.
     * On a link aggregator each story is two sibling table rows — the headline
     * in one, "123 points by tosh" in the next — and they have different
     * shapes, so matching a template picked up thirty headlines and left every
     * score behind. Matching the other shape instead would have done the
     * mirror image.
     *
     * If the matched records share a parent, that parent holds the whole list,
     * both halves included. Taking all of its qualifying children hands the
     * complete list to [mergeSplitRows], which already knows how to pair rows
     * that strictly alternate.
     *
     * The expansion is kept only when it genuinely pairs up. On a page whose
     * records are whole cards, the parent also holds banners and fragments,
     * and those do not alternate — so nothing is taken in and the matched
     * records stand as they are.
     */
    private fun withSplitHalves(
        records: List<Node>,
        children: Map<Int, MutableList<Node>>,
        labelCount: Map<Int, Int>,
        nodes: List<Node>,
    ): List<Node> {
        // The container, not the immediate parent. A matched shape often sits
        // INSIDE a row rather than being the row: on a link aggregator the
        // template matched a block within each headline row, so the thirty
        // records had thirty different parents and there was no list to widen
        // to. Their deepest common ancestor is the list itself.
        val container = commonAncestor(records, nodes) ?: return records
        val siblings = children[container].orEmpty()
            .filter { (labelCount[it.index] ?: 0) >= 2 }
            .sortedBy { it.top }
        if (siblings.size <= records.size) return records

        val asRows = siblings.map { Item(labelsUnderOf(it, children), it.top, false) }
        val merged = mergeSplitRows(asRows)
        // Merging happened, and it produced records carrying real fields.
        val paired = merged.size < asRows.size && merged.any { hasMetadata(it) }
        return if (paired) siblings else records
    }

    /**
     * The deepest node that has every record somewhere beneath it.
     *
     * Null when they share nothing, which means they are not one list.
     */
    private fun commonAncestor(records: List<Node>, nodes: List<Node>): Int? {
        if (records.isEmpty()) return null
        val byIndex = nodes.associateBy { it.index }
        fun chain(n: Node): List<Int> {
            val out = mutableListOf<Int>()
            var p: Node? = n
            var hops = 0
            while (p != null && hops < 60) { out.add(p.index); p = byIndex[p.parent]; hops++ }
            return out.asReversed()   // root first
        }
        val chains = records.map { chain(it) }
        val shortest = chains.minOf { it.size }
        var last: Int? = null
        for (depth in 0 until shortest) {
            val here = chains[0][depth]
            if (chains.all { it[depth] == here }) last = here else break
        }
        // The common ancestor is only useful if it is above the records.
        return if (last != null && records.none { it.index == last }) last else null
    }

    /** Labels beneath a node, first occurrence of each. */
    private fun labelsUnderOf(n: Node, children: Map<Int, MutableList<Node>>): List<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        fun walk(x: Node) {
            val l = x.label
            if (isUseful(l) && seen.add(l.take(160))) out.add(l.take(160))
            for (kid in children[x.index].orEmpty()) walk(kid)
        }
        walk(n)
        return out
    }

    /**
     * The nodes on this screen matching a remembered template.
     *
     * Empty when too few match, which is the signal that the memory is stale.
     * The same minimum applies as when the template was learned, so a screen
     * that has genuinely changed falls back to being read properly rather than
     * returning one lonely row that happens to still fit.
     */
    private fun groupMatching(
        nodes: List<Node>,
        children: Map<Int, MutableList<Node>>,
        template: String,
        labelCount: Map<Int, Int>,
        minItems: Int,
    ): List<Node> {
        val memo = HashMap<Long, String>()
        val hits = nodes.filter {
            (labelCount[it.index] ?: 0) >= 2 &&
                shapeOf(it, children, SHAPE_DEPTH, memo) == template
        }
        if (hits.size < minItems) return emptyList()
        val kept = dropNested(hits, nodes)
        return if (kept.size < minItems) emptyList() else kept
    }

    /** Remove any node that is a descendant of another node in the same set. */
    private fun dropNested(members: List<Node>, all: List<Node>): List<Node> {
        val byIndex = all.associateBy { it.index }
        val chosen = members.map { it.index }.toHashSet()
        return members.filter { n ->
            var p = byIndex[n.parent]
            var hops = 0
            while (p != null && hops < 60) {
                if (p.index in chosen) return@filter false
                p = byIndex[p.parent]
                hops++
            }
            true
        }
    }

    /** The record template that won the last call to [items]. The caller
     * stores it against the screen so the next visit can start from it. */
    @Volatile var lastTemplate: String = ""
        private set

    /**
     * @param knownTemplate a template remembered from a previous visit to this
     *   screen. When it still fits, it is used as-is: the same screen then
     *   groups the same way every time instead of drifting as its content
     *   changes, and the scoring pass is skipped entirely. When it no longer
     *   fits — the app updated, the layout changed — it is ignored and the
     *   screen is worked out afresh. A remembered answer is a shortcut, never
     *   an override of what is actually on screen.
     */
    fun items(nodes: List<Node>, minItems: Int = 3, knownTemplate: String = ""): List<Item> {
        lastTemplate = ""
        if (nodes.isEmpty()) return emptyList()
        val children = HashMap<Int, MutableList<Node>>()
        for (n in nodes) children.getOrPut(n.parent) { mutableListOf() }.add(n)

        // DISTINCT labels, because that is what a row ends up holding.
        //
        // Counting every occurrence disagreed with the rows themselves: a link
        // carries the same words as a content-description on the wrapper and
        // as text on the child, so it counted as two fields and became one
        // label. Groups of those passed the "a record has two fields" test,
        // won on coverage, and then every row was dropped for having a single
        // label — the read fell back to flat with nothing structured at all.
        // The two counts have to mean the same thing.
        val labelSets = HashMap<Int, Set<String>>()
        fun labelsOfSubtree(n: Node): Set<String> = labelSets.getOrPut(n.index) {
            val out = HashSet<String>()
            if (isUseful(n.label)) out.add(n.label.take(160))
            for (kid in children[n.index].orEmpty()) out.addAll(labelsOfSubtree(kid))
            out
        }
        for (n in nodes) labelsOfSubtree(n)
        val labelCount = HashMap<Int, Int>()
        for (n in nodes) labelCount[n.index] = labelSets[n.index]?.size ?: 0

        // Substance is measured in characters, not in number of labels.
        //
        // Counting labels made this size-biased: a fourteen-entry navigation
        // menu of two words each scored 14 x 2 = 28 and beat a five-product
        // grid at 5 x 4 = 20, because the count term is linear and two labels
        // versus four is not enough to overcome it. The earlier page happened
        // to have eleven products, so it won and hid this.
        //
        // A nav entry and a product row differ most in how much text they
        // carry — "Dept 0" is seven characters, a product row is forty-plus.
        // That is the signal.
        val labelChars = HashMap<Int, Int>()
        fun countChars(n: Node): Int = labelChars.getOrPut(n.index) {
            var c = if (isUseful(n.label)) n.label.length else 0
            for (kid in children[n.index].orEmpty()) c += countChars(kid)
            c
        }
        for (n in nodes) countChars(n)

        // Remembered first. A template that still matches this screen is the
        // answer we computed last time, and recomputing it cannot improve on
        // it — it can only differ, which is how the same page came back as
        // 130, then 126, then 123 records across one read.
        if (knownTemplate.isNotBlank()) {
            val recalled = groupMatching(nodes, children, knownTemplate, labelCount, minItems)
            if (recalled.isNotEmpty()) {
                lastTemplate = knownTemplate
                // Through the same widening as a fresh read. Recalling a
                // template and stopping there gave a WORSE answer than working
                // it out: on a link aggregator the remembered shape is the
                // headline block, and without this the thirty stories came
                // back with their scores stripped off again. A shortcut that
                // changes the answer is not a shortcut.
                val records = withSplitHalves(recalled, children, labelCount, nodes)
                android.util.Log.i(
                    "UltraPerceive",
                    "structure: recalled template, ${records.size} records",
                )
                return finish(records, children)
            }
            android.util.Log.i("UltraPerceive", "structure: remembered template no longer fits")
        }

        // Preferred: the page's own repeating template. Falls through to the
        // older statistical method when the tree carries no class information.
        val byTemplate = templateGroups(
            nodes, children,
            charsOf = { labelChars[it.index] ?: 0 },
            labelsOf = { labelCount[it.index] ?: 0 },
            minItems = minItems,
        )
        if (byTemplate.isNotEmpty()) {
            lastTemplate = shapeOf(byTemplate.first(), children, SHAPE_DEPTH, HashMap())
            val records = withSplitHalves(byTemplate, children, labelCount, nodes)
            android.util.Log.i(
                "UltraPerceive",
                "structure: template match, ${byTemplate.size} records" +
                    if (records !== byTemplate) " (+${records.size - byTemplate.size} paired halves)" else "",
            )
            return finish(records, children)
        }

        var bestScore = 0.0
        var bestKids: List<Node> = emptyList()
        for ((_, kids) in children) {
            val items = kids.filter { (labelCount[it.index] ?: 0) >= 2 }
            if (items.size < minItems) continue
            val counts = items.map { (labelChars[it.index] ?: 0).toDouble() }
            val mean = counts.average()
            if (mean <= 0) continue
            val sd = kotlin.math.sqrt(counts.sumOf { (it - mean) * (it - mean) } / counts.size)
            val uniformity = (1.0 - sd / mean).coerceIn(0.0, 1.0)
            val substance = counts.sorted()[counts.size / 2].coerceAtMost(SUBSTANCE_CAP)
            val score = items.size * uniformity * substance
            if (score > bestScore) { bestScore = score; bestKids = items }
        }
        if (bestKids.isEmpty()) return emptyList()
        android.util.Log.i("UltraPerceive", "structure: fallback scoring, ${bestKids.size} rows")
        return finish(bestKids, children)
    }

    /** Turn chosen record nodes into rows, whichever method chose them. */
    private fun finish(records: List<Node>, children: Map<Int, MutableList<Node>>): List<Item> {
        fun labelsUnder(n: Node): Pair<List<String>, List<String>> {
            val labels = mutableListOf<String>()
            val ids = mutableListOf<String>()
            val seen = HashSet<String>()
            fun walk(x: Node) {
                val l = x.label
                // Pages repeat a title across the image link, the heading and
                // the anchor; the reader only needs it once. Keep the first
                // occurrence, and the id that came with it.
                if (isUseful(l) && seen.add(l.take(160))) {
                    labels.add(l.take(160))
                    ids.add(x.vid)
                }
                for (kid in children[x.index].orEmpty()) walk(kid)
            }
            walk(n)
            return labels to ids
        }

        // Document order, not screen position.
        //
        // Bounds are only trustworthy for what is currently visible: rows
        // above and below the viewport report stale or identical tops, so
        // sorting by `top` shuffled a link aggregator's 60 rows into 14 correct
        // pairs followed by a block of 17 score rows with no headlines near
        // them. The tree arrives in reading order, and reading order is what a
        // list means.
        val rows = records
            .sortedBy { it.index }
            .map {
                val (labels, ids) = labelsUnder(it)
                Item(labels, it.top, it.clickable || anyClickableUnder(it, children), ids)
            }
            .filter { it.labels.size >= 2 }

        return mergeSplitRows(dropContained(rows))
    }

    /**
     * Keep the outer record when one record's content sits inside another's.
     *
     * Chrome wraps a product card in several layers, and each layer matches
     * the template, so one product came back three times. They are not exact
     * duplicates either — an outer layer picks up a "More like this" heading
     * the inner one does not — so removing exact repeats was not enough.
     *
     * Containment is the honest test: if everything a record says is already
     * said by a bigger record, it is that record seen from further in. The
     * bigger one is kept because it is the whole card, which is what a person
     * would point at and call a result.
     */
    private fun dropContained(rows: List<Item>): List<Item> {
        val bySize = rows.sortedByDescending { it.labels.size }
        val kept = mutableListOf<Item>()
        val keptSets = mutableListOf<Set<String>>()
        for (r in bySize) {
            val set = r.labels.toSet()
            if (keptSets.any { it.containsAll(set) }) continue
            kept += r
            keptSets += set
        }
        // Back into the order they came in. Sorting by `top` here reordered
        // off-screen rows, which is what broke the pairing above.
        val keptSet = kept.toHashSet()
        return rows.filter { it in keptSet }
    }


    /** The named fields that make a row a *record* rather than a heading. */
    private val METADATA = setOf("points", "price", "rating", "reviews", "availability")

    /**
     * Classifies every label, not `fields()`.
     *
     * `fields()` names the first label "title" unconditionally, so a detail
     * row whose only content is "120 points by tosh" would report no metadata
     * at all and no pair would ever merge.
     */
    private fun hasMetadata(item: Item): Boolean =
        item.labels.any { nameOf(it) in METADATA }

    /**
     * Join rows that are two halves of one thing.
     *
     * Measured on Hacker News: each story is two sibling table rows — the
     * title in one, "120 points by tosh 1 hour ago" in the next. Grouping by
     * container gave two separate items, so the model was pairing a score to a
     * headline by adjacency. That is the guess this whole file exists to
     * remove, and it was still happening one level up.
     *
     * Merged only on strict evidence: the list alternates, every even row
     * carries no metadata field, and every odd row carries one. A product grid
     * fails that test because every card has a price, so it is left alone. A
     * list where only some rows are sponsored fails it too — the alternation
     * has to hold all the way down.
     */
    internal fun mergeSplitRows(rows: List<Item>): List<Item> {
        if (rows.size < 4) return rows

        // Pair greedily, left to right: a row with no metadata field followed
        // by one that has it are two halves of the same thing.
        //
        // Requiring perfect alternation down the whole list was too brittle
        // for a real page. A link aggregator carries the occasional job post
        // with no score, and one exception aborted every pair on the page,
        // leaving thirty headlines with their scores stranded beside them.
        val out = mutableListOf<Item>()
        var paired = 0
        var i = 0
        while (i < rows.size) {
            val head = rows[i]
            val tail = rows.getOrNull(i + 1)
            if (tail != null && !hasMetadata(head) && hasMetadata(tail)) {
                val labels = mutableListOf<String>()
                val ids = mutableListOf<String>()
                for (src in listOf(head, tail)) {
                    src.labels.forEachIndexed { k, l ->
                        if (l !in labels) { labels.add(l); ids.add(src.idAt(k)) }
                    }
                }
                out += Item(labels, head.top, head.clickable || tail.clickable, ids)
                paired++
                i += 2
            } else {
                out += head
                i++
            }
        }

        // Pairing is a property of the list, not of one lucky row. A page where
        // a single pair happens to fit is a coincidence, and merging there
        // would join two unrelated records.
        val couldPair = rows.size / 2
        return if (paired * 100 >= couldPair * PAIR_MAJORITY_PCT) out else rows
    }

    /** How much of a list must pair up before the pairing is believed. */
    private const val PAIR_MAJORITY_PCT = 60

    /** Tracking parameters and opaque ids are labels to a screen reader and
     * noise to everyone else. `vote?id=...&how=up` is a real one, read off
     * Hacker News: a bare query string with no host. */
    private val JUNK = Regex("""^(ref=|https?://|[a-z]+\?[a-z]+=|[A-Za-z0-9+/=_.\-]{28,}$)""")

    /**
     * A label that is only punctuation or a bare separator carries nothing.
     * Real ones read off a page: "(", ")", "|", "·".
     *
     * Tested by character rather than by regex on purpose. The first attempt
     * used `[\p{Punct}\s]` and still let "|" through, because the page emits
     * it wrapped in non-breaking spaces — Java's `\s` does not match U+00A0,
     * and neither Kotlin's `trim()` nor `isBlank()` removes it. Stripping
     * every separator first, then asking whether anything alphanumeric is
     * left, cannot be fooled by whichever space character a page happens to
     * use.
     */
    private fun isPunctuationOnly(label: String): Boolean {
        val core = label.filterNot { it.isWhitespace() || it in SPACE_LOOKALIKES }
        return core.isNotEmpty() && core.length <= 3 && core.none { it.isLetterOrDigit() }
    }

    private val SPACE_LOOKALIKES = charArrayOf(
        ' ', // non-breaking space
        '​', // zero-width space
        ' ', // thin space
        '﻿', // zero-width no-break space
    )

    private fun isUseful(label: String): Boolean =
        label.isNotBlank() &&
            !JUNK.containsMatchIn(label) &&
            !isPunctuationOnly(label)


    private fun anyClickableUnder(n: Node, children: Map<Int, MutableList<Node>>): Boolean {
        if (n.clickable) return true
        return children[n.index].orEmpty().any { anyClickableUnder(it, children) }
    }

    /** Characters of text in a median row, past which extra length stops
     * counting. Without a cap, one container of long paragraphs outscores a
     * genuine list of short rows. */
    private const val SUBSTANCE_CAP = 300.0

    /**
     * How many children of one kind, banded.
     *
     * A bare set of child kinds was too coarse: `View{TextView}` matched a
     * wrapper holding one line of text and a story row holding four, so on a
     * link aggregator a swarm of 153 tiny wrappers outweighed the actual
     * stories and the read collapsed to nothing. An exact count is too strict
     * the other way — it split one product grid into groups of two.
     *
     * A band tolerates a card with four fields next to one with five, while
     * keeping one field distinct from four.
     */
    private fun countBand(n: Int): Int = when {
        n <= 1 -> 1
        n == 2 -> 2
        n == 3 -> 3
        n <= 6 -> 4
        n <= 12 -> 5
        else -> 6
    }

    /** How deep a structural signature looks. Two levels distinguishes a
     * product card from a heading without making every node unique the moment
     * one grandchild differs. */
    private const val SHAPE_DEPTH = 2

    /** Text a node must carry before it can be a record. A leaf with one word
     * is a field, not a result. */
    private const val MIN_RECORD_CHARS = 12

    /** Two groups covering nearly the same text are the same list seen at
     * two depths. Prefer the finer one, so a page of cards does not come back
     * as three mega-rows. */
    private const val NEAR_TIE = 0.8

    private val PRICE = Regex("""[$£€]\s?\d[\d,]*(?:\.\d{2})?""")

    /** "4.5 out of 5 stars", "4.5/5", "Rated 4.5 stars". The bound to 0-5
     * keeps it off prices and quantities. */
    private val RATING = Regex(
        """\b([0-5](?:[.,]\d)?)\s*(?:out of\s*5|/\s*5|star)""",
        RegexOption.IGNORE_CASE,
    )

    /** "1,234 ratings", "89 reviews". Needs the noun — a bare number is not
     * evidence of anything. */
    private val REVIEWS = Regex(
        """\b(\d[\d,]*)\s*(?:ratings?|reviews?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Points, votes, likes — the counter on a link aggregator or a feed. */
    private val POINTS = Regex(
        """\b(\d[\d,]*)\s*(?:points?|upvotes?|votes?|likes?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Paid placement. The cheapest result is not the best buy if it is an ad,
     * and the model cannot tell unless the row says so.
     *
     * Anchored to the whole label: these are short badges that stand alone.
     * Matching "ad" as a substring would hit "Adapter" and "Radio".
     */
    private val SPONSORED = Regex(
        """^(sponsored|ad|advertisement|promoted|paid)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether the thing can actually be bought. A price on an unavailable
     * item is the classic wrong answer to "find the cheapest".
     */
    private val UNAVAILABLE = Regex(
        """\b(out of stock|currently unavailable|temporarily unavailable|sold out|unavailable|back ?ordered|pre-?order)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val AVAILABLE = Regex(
        """\b(in stock|available now|ships? (?:today|tomorrow)|arrives)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Name the fields in a row.
     *
     * The first label is the title: across a list, the first descendant that
     * carries text is the heading in every layout measured. Everything after
     * it is matched against the patterns above, and anything that matches
     * none of them keeps its text with no name attached.
     */
    fun fields(item: Item): List<Field> {
        if (item.labels.isEmpty()) return emptyList()

        // A ranked list puts "1." in front of the headline, and taking the
        // first label blindly made the rank the title — every row read as
        // "1. 1." with the real headline demoted to a detail line.
        val titleIndex = item.labels.indexOfFirst { !ORDINAL.matches(it.trim()) }
            .let { if (it < 0) 0 else it }

        val raw = item.labels.mapIndexed { i, label ->
            when {
                i == titleIndex -> Field("title", label)
                ORDINAL.matches(label.trim()) -> Field("rank", label)
                // The app's own name for the field beats any guess from the
                // text. A native screen labels its nodes `alarm_item_time`,
                // `price`, `sender` — reading a regex over the value to work
                // out what it is, when the app already said so, is the same
                // mistake as rebuilding the page structure from text
                // statistics. Web content rarely provides one, so this is
                // mostly a native-app win.
                else -> Field(fieldNameFor(item.idAt(i)) ?: nameOf(label), label)
            }
        }
        return dropRedundant(raw)
    }

    /**
     * Drop a field that only restates a shorter one.
     *
     * A page often exposes the same fact twice: Hacker News reports both
     * "123 points" and the whole subtext line "123 points by tosh 1 hour ago
     * | hide | 127 comments", and both name themselves `points`. Keeping both
     * spends the read budget saying one thing twice, and offers the model two
     * candidate scores for one row.
     *
     * Only an exact superset is dropped — the longer text has to contain the
     * shorter one. Two genuinely different values keep both, since a row with
     * two different prices is information, not noise.
     */
    private fun dropRedundant(fields: List<Field>): List<Field> {
        val shortestByName = fields
            .filter { it.name != null && it.name in METADATA }
            .groupBy { it.name }
            .mapValues { (_, v) -> v.minByOrNull { it.value.length }!!.value }
        return fields.filterNot { f ->
            val keep = shortestByName[f.name] ?: return@filterNot false
            f.value != keep && f.value.contains(keep)
        }
    }

    /** "1.", "12)", "3" — a position marker, not a heading. */
    private val ORDINAL = Regex("""^\d{1,3}[.)]?$""")

    /**
     * Turn a view id into a field name, or null when it says nothing useful.
     *
     * Ids describing layout rather than content are rejected: `text1`,
     * `container`, `row_2` name a slot, not a meaning, and a field called
     * "container" is worse than an unnamed one. Common wrapper words are
     * trimmed so `alarm_item_time` reads as `alarm_time`.
     */
    internal fun fieldNameFor(vid: String): String? {
        if (vid.isBlank()) return null
        if (!looksLikeAName(vid)) return null
        var v = vid.lowercase()
        for (noise in ID_NOISE) v = v.replace(noise, "_")
        v = v.trim('_').replace(Regex("_+"), "_")
        if (v.isBlank() || v.length > 32) return null
        if (v in ID_MEANINGLESS) return null
        if (Regex("^[a-z]{1,4}\\d*$").matches(v)) return null   // t1, tv, txt2
        return v
    }

    /**
     * Does this id look like a name a developer wrote, or a value the content
     * generated?
     *
     * Read off two live pages: Amazon exposes product ASINs and UUIDs as ids
     * ("1248879011", "0d6f55ac-a8a3-46bb-a6a5-f7e069fa3ff1"), and Hacker News
     * exposes story ids ("49417298"). Those are content. Taking them as field
     * names would hand the model `49417298: 130 points`, and fingerprinting a
     * screen on them would mean the same page never looked the same twice,
     * because the ids change with every story on it.
     *
     * A real name has a word in it.
     */
    internal fun looksLikeAName(id: String): Boolean {
        if (id.length > 48) return false
        if (UUID_LIKE.matches(id)) return false
        if (id.none { it.isLetter() }) return false
        // At least one run of three letters — "alarm_time" passes, "a1b2c3"
        // and "0d6f55ac" do not.
        if (!Regex("[A-Za-z]{3,}").containsMatchIn(id)) return false
        // Mostly digits with a letter or two mixed in is still an identifier.
        val digits = id.count { it.isDigit() }
        return digits * 2 <= id.length
    }

    private val UUID_LIKE =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val ID_NOISE = listOf("_item_", "_view_", "_label_", "_text_")

    /** Ids that name a slot rather than a meaning. */
    private val ID_MEANINGLESS = setOf(
        "text", "text1", "text2", "title_container", "container", "content",
        "row", "item", "layout", "wrapper", "holder", "value", "label",
        "icon", "image", "img", "summary_container", "list_item",
    )

    /**
     * The single field name this text proves, or null.
     *
     * Order matters. "4.5 out of 5 stars, 1,234 ratings" contains a rating and
     * a count, and it is a rating line — the count is a detail of it. A text
     * that matches two unrelated patterns is left unnamed rather than
     * arbitrated, because guessing between them is the failure this exists to
     * prevent.
     */
    private fun nameOf(label: String): String? {
        val trimmed = label.trim()
        // Checked before the numeric patterns: these are decisive for a buying
        // decision and carry no digits to confuse them with.
        if (SPONSORED.matches(trimmed)) return "sponsored"
        if (UNAVAILABLE.containsMatchIn(trimmed)) return "availability"
        if (AVAILABLE.containsMatchIn(trimmed)) return "availability"

        val price = PRICE.containsMatchIn(label)
        val rating = RATING.containsMatchIn(label)
        val reviews = REVIEWS.containsMatchIn(label)
        val points = POINTS.containsMatchIn(label)
        return when {
            rating -> "rating"
            price && !reviews && !points -> "price"
            reviews && !price && !points -> "reviews"
            points && !price && !reviews -> "points"
            else -> null
        }
    }

    /**
     * Render rows for the brain: one numbered item, its fields beneath it.
     *
     * A named field reads `price: $89.99`. An unnamed one is printed as it
     * appeared. When a row shows more than one price the values are listed
     * together and flagged, because a list price beside a sale price is the
     * normal case and silently picking one of them is how the agent buys the
     * wrong thing.
     */
    fun render(items: List<Item>, budget: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var used = 0
        var shown = 0
        for ((n, item) in items.withIndex()) {
            val f = fields(item)
            val title = f.firstOrNull { it.name == "title" } ?: f.first()
            val lines = mutableListOf<String>()
            for (field in f) {
                if (field === title) continue
                lines.add(if (field.name != null) "   ${field.name}: ${field.value}" else "   ${field.value}")
            }
            val prices = item.labels
                .flatMap { PRICE.findAll(it).map { m -> m.value }.toList() }
                .distinct()
            if (prices.size > 1) {
                lines.add("   prices shown: ${prices.joinToString(", ")} " +
                    "(more than one — do not assume which is charged)")
            }
            val head = "${n + 1}. ${title.value}"
            val block = head + (if (lines.isEmpty()) "" else "\n" + lines.joinToString("\n")) + "\n"
            if (used + block.length > budget) break
            sb.append(block)
            used += block.length
            shown++
        }
        return sb.toString().trimEnd() to shown
    }
}
