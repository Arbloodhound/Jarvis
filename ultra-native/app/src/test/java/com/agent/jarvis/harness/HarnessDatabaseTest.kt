package com.agent.jarvis.harness

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.agent.jarvis.data.UltraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §0 wire-in: claims and grants live in UltraDatabase v8. The v7→v8 migration must create exactly
 * the tables Room expects (Room refuses to open a database whose schema differs), and the DAOs work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessDatabaseTest {
    private lateinit var db: UltraDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UltraDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    @Test fun migration7to8CreatesTheSameTablesRoomGenerates() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        val raw = helper.writableDatabase
        UltraDatabase.MIGRATION_7_8.migrate(raw)
        val room = db.openHelper.writableDatabase
        for (t in listOf("memory_claims", "approval_grants")) {
            assertEquals(t, TableInfo.read(room, t), TableInfo.read(raw, t))
        }
        helper.close()
    }

    @Test fun claimsWriteSupersedeSearchAndHistory() = runBlocking {
        val claims = MemoryClaims(db.claims())
        val a = claims.write("Prefers tea in the morning", "preference", "user", "msg:1")
        val b = claims.write("Prefers coffee in the morning", "preference", "user", "msg:2", supersedes = a)
        val found = claims.search("morning coffee")
        assertEquals(listOf(b), found.map { it.id })
        assertEquals(listOf(b, a), claims.history(b).map { it.id })
        assertEquals("superseded", db.claims().byId(a)!!.status)
        assertTrue(claims.reinforce(b))
        assertFalse(claims.reinforce(a))
    }

    @Test fun claimForgetDaoDeletesForReal() = runBlocking {
        val claims = MemoryClaims(db.claims())
        val id = claims.write("Lives near the old mill", "fact", "user", "msg:9")
        assertEquals(1, db.claimForget().all().size)
        assertEquals(1, db.claimForget().delete(id))
        assertNull(db.claims().byId(id))
    }

    @Test fun grantsCoverExactlyTheirScopeAndPurchasesNever() = runBlocking {
        val g = ApprovalGrants(db.grants())
        assertTrue(g.isCovered(ApprovalLevel.NONE))
        assertFalse(g.isCovered(ApprovalLevel.SITE, website = "shop.example"))
        g.grantSite("Shop.Example")
        assertTrue(g.isCovered(ApprovalLevel.SITE, website = "shop.example"))
        g.grantRecipient("gmail", "me", "mum@example.com")
        assertTrue(g.isCovered(ApprovalLevel.RECIPIENT, channel = "gmail", account = "me", recipient = "mum@example.com"))
        assertFalse(g.isCovered(ApprovalLevel.RECIPIENT, channel = "gmail", account = "me", recipient = "boss@example.com"))
        g.grantOnce("gmail.send", "{a}")
        assertTrue(g.isCovered(ApprovalLevel.ONCE, tool = "gmail.send", argsJson = "{a}"))
        assertFalse("a once-grant is consumed", g.isCovered(ApprovalLevel.ONCE, tool = "gmail.send", argsJson = "{a}"))
        g.grantSite("pay.example")
        assertFalse(g.isCovered(ApprovalLevel.ALWAYS_ASK, website = "pay.example"))
        g.revokeSite("shop.example")
        assertFalse(g.isCovered(ApprovalLevel.SITE, website = "shop.example"))
    }

    @Suppress("unused") private val keepImport = SQLiteDatabase.OPEN_READWRITE
}
