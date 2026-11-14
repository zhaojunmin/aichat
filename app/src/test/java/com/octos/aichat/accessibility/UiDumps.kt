package com.octos.aichat.accessibility

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Loads `uiautomator dump` XML into the [UiNode] tree the parsers consume. */
object UiDumps {

    /** Root of the recorded fixtures (Gradle runs unit tests from the module dir). */
    val dir = File("src/test/resources/dumps")

    fun load(file: File): UiNode {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val hierarchy = doc.documentElement
        val first = hierarchy.childElements().first { it.tagName == "node" }
        return toNode(first)
    }

    private fun toNode(e: Element): UiNode = UiNode(
        // uiautomator writes "" for absent values; a live node reports null.
        viewId = e.getAttribute("resource-id").ifEmpty { null },
        className = e.getAttribute("class").ifEmpty { null },
        text = e.getAttribute("text").ifEmpty { null },
        contentDescription = e.getAttribute("content-desc").ifEmpty { null },
        bounds = parseBounds(e.getAttribute("bounds")),
        children = e.childElements().filter { it.tagName == "node" }.map(::toNode),
    )

    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parseBounds(s: String): Bounds {
        val (l, t, r, b) = BOUNDS.matchEntire(s)?.destructured
            ?: error("bad bounds: $s")
        return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(out::add)
        return out
    }
}
