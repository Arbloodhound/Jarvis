package com.agent.jarvis

import com.agent.jarvis.agent.Recipes
import com.agent.jarvis.data.RecipeDao
import com.agent.jarvis.data.RecipeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Recipes against the real lookup code, with the database swapped for a map.
 *
 * Names are stored in their chosen form ("my morning sites") and must be found
 * by that form; a request only runs a routine whose whole name it says.
 */
class RecipeLookupTest {

    private class MapDao : RecipeDao {
        val rows = linkedMapOf<String, RecipeEntity>()
        override suspend fun list() = rows.values.toList()
        override suspend fun byName(name: String) = rows[name]
        override suspend fun upsert(r: RecipeEntity) { rows[r.name] = r }
        override suspend fun delete(name: String) { rows.remove(name) }
    }

    private fun recipes(vararg names: String): Pair<Recipes, MapDao> {
        val dao = MapDao()
        val r = Recipes(dao)
        runBlocking {
            for (n in names) r.save(n, listOf(Recipes.Step("battery_status", org.json.JSONObject())))
        }
        return r to dao
    }

    @Test
    fun `a name kept with its my is found by its stored name`() = runBlocking {
        val (r, _) = recipes("my morning sites")
        assertNotNull(r.stepsOf("my morning sites"))
        assertEquals("my morning sites", r.resolve("run my morning sites")?.name)
        assertEquals("Deleted recipe \"my morning sites\"", r.delete("my morning sites"))
    }

    @Test
    fun `a taught route is found by its stored name`() = runBlocking {
        val dao = MapDao()
        val r = Recipes(dao)
        assertEquals("my morning sites", r.saveJourney("my morning sites", "[]", "[]"))
        assertNotNull(r.journeyOf("my morning sites"))
        assertNotNull(r.competenceOf("my morning sites"))
    }

    @Test
    fun `do it does not run a routine that merely contains it`() = runBlocking {
        val (r, _) = recipes("edit photos", "fix it now", "it")
        // "it" is an exact (short) name: only an exact match may run it.
        assertEquals("it", r.resolve("do it")?.name)
        val (r2, _) = recipes("edit photos", "fix it now")
        assertNull(r2.resolve("do it"))
    }

    @Test
    fun `a request naming only part of a routine runs nothing`() = runBlocking {
        val (r, _) = recipes("morning briefing")
        assertNull(r.resolve("run morning"))
        assertNull(r.resolve("run morningbriefings"))
    }

    @Test
    fun `the whole name inside a longer request is found, most specific first`() = runBlocking {
        val (r, _) = recipes("morning sites", "my morning sites")
        assertEquals("my morning sites", r.resolve("run my morning sites please")?.name)
        assertEquals("morning sites", r.resolve("run the morning sites now")?.name)
    }

    @Test
    fun `a short name is never matched inside a request`() {
        assertNull(Recipes.pick("run it again later", listOf("it", "go")))
        assertEquals("news", Recipes.pick("play the news", listOf("news")))
    }
}
