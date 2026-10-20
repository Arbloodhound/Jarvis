package com.agent.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every database and SharedPreferences file the main sources name is excluded from both Android
 * cloud backup and device-to-device transfer by res/xml/data_extraction_rules.xml. allowBackup=
 * "false" does not stop device-to-device transfer on Android 12+, and before this check only
 * jarvis.db and jarvis_provider.xml were excluded (heard speech in ambient.db, memory_notes.db and
 * every prefs file but one were not). A whole-domain exclude (path=".") covers every name in it.
 */
class DataExtractionRulesTest {
    private val rules = File("src/main/res/xml/data_extraction_rules.xml")
    private val sources = File("src/main/java").walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .map { it.readText() }.toList()

    private fun found(regex: Regex): Set<String> =
        sources.flatMap { s -> regex.findAll(s).map { it.groupValues[1] } }.toSortedSet()

    private val databases = found(Regex("\"([A-Za-z0-9_.-]+\\.db)\""))
    private val prefs = found(Regex("getSharedPreferences\\(\\s*\"([^\"]+)\"")) +
        found(Regex("PREFS\\s*=\\s*\"([^\"]+)\"")) +
        // LocalModelEngine.Role entries: THINKER("jarvis_local_model"), ...
        found(Regex("^\\s*[A-Z_]+\\(\"(jarvis_[a-z_]+)\"\\)", RegexOption.MULTILINE))

    /** domain -> exclude paths, for one section of the rules file. */
    private fun excludes(section: String): Map<String, Set<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rules)
        val sections = doc.getElementsByTagName(section)
        assertEquals("exactly one <$section>", 1, sections.length)
        val nodes = (sections.item(0) as Element).getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .groupBy({ it.getAttribute("domain") }, { it.getAttribute("path") })
            .mapValues { it.value.toSet() }
    }

    private fun covered(ex: Map<String, Set<String>>, domain: String, path: String): Boolean {
        val paths = ex[domain].orEmpty()
        return "." in paths || path in paths
    }

    @Test fun theSourceScanFindsTheKnownFiles() {
        // Guards the regexes: if they stop matching, the coverage test below would pass vacuously.
        assertTrue(databases.toString(), databases.containsAll(listOf("jarvis.db", "ambient.db", "memory_notes.db")))
        assertTrue(prefs.toString(), prefs.containsAll(listOf(
            "jarvis_provider", "jarvis_settings", "ambient", "jarvis_a11y", "jarvis_protected_apps",
            "jarvis_local_model", "jarvis_local_model_librarian")))
    }

    @Test fun everyDatabaseAndPrefsFileIsExcludedFromCloudBackupAndDeviceTransfer() {
        for (section in listOf("cloud-backup", "device-transfer")) {
            val ex = excludes(section)
            val missing = databases.flatMap { db ->
                listOf(db, "$db-wal", "$db-shm", "$db-journal").filterNot { covered(ex, "database", it) }
            } + prefs.map { "$it.xml" }.filterNot { covered(ex, "sharedpref", it) }
            assertEquals("not excluded from <$section>", emptyList<String>(), missing)
        }
    }

    @Test fun filesAndExternalStorageAreExcludedFromBoth() {
        // notifications.log, last_run.json, gate audit logs, notes/, notes-imported/ and models/
        // live under filesDir (domain "file") or getExternalFilesDir (domain "external").
        for (section in listOf("cloud-backup", "device-transfer")) {
            val ex = excludes(section)
            for (domain in listOf("root", "file", "external")) {
                assertTrue("<$section> does not exclude all of domain $domain", covered(ex, domain, "."))
            }
        }
    }
}
