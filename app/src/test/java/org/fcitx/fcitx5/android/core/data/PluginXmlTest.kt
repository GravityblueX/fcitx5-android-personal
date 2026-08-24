package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Proxy

class PluginXmlTest {

    private data class Event(
        val type: Int,
        val name: String? = null,
        val text: String? = null,
    )

    private fun parser(vararg events: Event): XmlPullParser {
        var index = 0
        return Proxy.newProxyInstance(
            XmlPullParser::class.java.classLoader,
            arrayOf(XmlPullParser::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getEventType" -> events[index].type
                "getName" -> events[index].name
                "getText" -> events[index].text
                "next" -> events[++index].type
                else -> throw UnsupportedOperationException(method.name)
            }
        } as XmlPullParser
    }

    @Test
    fun doesNotReuseTextForEmptyElement() {
        val parsed = parsePluginXml(
            parser(
                Event(XmlPullParser.START_DOCUMENT),
                Event(XmlPullParser.START_TAG, "plugin"),
                Event(XmlPullParser.START_TAG, "apiVersion"),
                Event(XmlPullParser.TEXT, text = PluginDescriptor.pluginAPI),
                Event(XmlPullParser.END_TAG, "apiVersion"),
                Event(XmlPullParser.START_TAG, "description"),
                Event(XmlPullParser.END_TAG, "description"),
                Event(XmlPullParser.END_TAG, "plugin"),
                Event(XmlPullParser.END_DOCUMENT),
            )
        )

        assertEquals(PluginDescriptor.pluginAPI, parsed.apiVersion)
        assertEquals("", parsed.description)
    }

    @Test
    fun combinesConsecutiveTextEvents() {
        val parsed = parsePluginXml(
            parser(
                Event(XmlPullParser.START_DOCUMENT),
                Event(XmlPullParser.START_TAG, "plugin"),
                Event(XmlPullParser.START_TAG, "description"),
                Event(XmlPullParser.TEXT, text = "plug"),
                Event(XmlPullParser.TEXT, text = "in"),
                Event(XmlPullParser.END_TAG, "description"),
                Event(XmlPullParser.END_TAG, "plugin"),
                Event(XmlPullParser.END_DOCUMENT),
            )
        )

        assertEquals("plugin", parsed.description)
    }

    @Test
    fun rejectsNestedTextElements() {
        assertThrows(InvalidPluginXml::class.java) {
            parsePluginXml(
                parser(
                    Event(XmlPullParser.START_DOCUMENT),
                    Event(XmlPullParser.START_TAG, "plugin"),
                    Event(XmlPullParser.START_TAG, "description"),
                    Event(XmlPullParser.TEXT, text = "nested"),
                    Event(XmlPullParser.START_TAG, "domain"),
                    Event(XmlPullParser.END_TAG, "domain"),
                    Event(XmlPullParser.END_TAG, "description"),
                    Event(XmlPullParser.END_TAG, "plugin"),
                    Event(XmlPullParser.END_DOCUMENT),
                )
            )
        }
    }

    @Test
    fun rejectsOversizedElementText() {
        assertThrows(InvalidPluginXml::class.java) {
            parsePluginXml(
                parser(
                    Event(XmlPullParser.START_DOCUMENT),
                    Event(XmlPullParser.START_TAG, "plugin"),
                    Event(XmlPullParser.START_TAG, "description"),
                    Event(
                        XmlPullParser.TEXT,
                        text = "x".repeat(MAX_PLUGIN_XML_TEXT_CHARS + 1),
                    ),
                    Event(XmlPullParser.END_TAG, "description"),
                    Event(XmlPullParser.END_TAG, "plugin"),
                    Event(XmlPullParser.END_DOCUMENT),
                )
            )
        }
    }

    @Test
    fun rejectsExcessiveXmlEvents() {
        val events = buildList {
            add(Event(XmlPullParser.START_DOCUMENT))
            repeat(MAX_PLUGIN_XML_EVENTS) {
                add(Event(XmlPullParser.TEXT, text = "ignored"))
            }
            add(Event(XmlPullParser.END_DOCUMENT))
        }

        assertThrows(InvalidPluginXml::class.java) {
            parsePluginXml(parser(*events.toTypedArray()))
        }
    }
}
