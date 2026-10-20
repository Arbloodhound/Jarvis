package com.agent.jarvis.harness

import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.agent.jarvis.data.JarvisDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v8 → v9 adds memory_embeddings (ClaimVerifier) and bandit_arm_state (BanditArmState). The
 * migration must create exactly the tables Room expects, and the new DAOs must work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessDatabaseV9Test {
    private lateinit var db: JarvisDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), JarvisDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    @Test fun migration8to9CreatesTheSameTablesRoomGenerates() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        val raw = helper.writableDatabase
        JarvisDatabase.MIGRATION_8_9.migrate(raw)
        val room = db.openHelper.writableDatabase
        for (t in listOf("memory_embeddings", "bandit_arm_state")) {
            assertEquals(t, TableInfo.read(room, t), TableInfo.read(raw, t))
        }
        helper.close()
    }

    @Test fun embeddingDaoRoundTripsAndFindsActiveOnly() = runBlocking {
        val claims = MemoryClaims(db.claims())
        val active = claims.write("user likes trains", "preference", "user", "msg:1")
        val superseded = claims.write("user likes buses", "preference", "user", "msg:2")
        claims.write("x", "fact", "user", "msg:3", supersedes = superseded) // supersede the second

        db.embeddings().upsert(MemoryEmbeddingEntity(active, "e5", Embeddings.toBytes(floatArrayOf(1f, 0f))))
        db.embeddings().upsert(MemoryEmbeddingEntity(superseded, "e5", Embeddings.toBytes(floatArrayOf(0f, 1f))))

        val got = db.embeddings().byClaim(active)
        assertNotNull(got)
        assertTrue(floatArrayOf(1f, 0f).contentEquals(Embeddings.fromBytes(got!!.embedding)))

        // only the still-active claim's embedding is in the lens-(b) search space
        val activeEmb = db.embeddings().allActiveWithEmbedding()
        assertEquals(listOf(active), activeEmb.map { it.claimId })

        assertEquals(1, db.embeddings().delete(active))
        assertNull(db.embeddings().byClaim(active))
    }

    @Test fun banditArmDaoRecordsRewardPenalizeDecision() = runBlocking {
        val bandit = BanditArmState(db.banditArms())
        bandit.decision("ideas", "cycling", "high")
        bandit.reward("ideas", "cycling", "high")
        bandit.penalize("ideas", "cycling", "high")
        val a = bandit.get("ideas", "cycling", "high")!!
        assertEquals(1L, a.decisionPoints)
        assertEquals(1L, a.terminalRewards)
        assertEquals(2L, a.terminalObservations)
        assertEquals(0.0, a.fastRewardSum, 1e-9)
        assertEquals(2L, a.fastObservations)

        bandit.reward("ideas", "cycling", "low")
        // "high" has fastMean 0, "low" has fastMean 1 → low ranks first
        assertEquals(listOf("low", "high"), bandit.ranked("ideas", "cycling").map { it.lane })
    }

    @Test fun banditFeedbackBridgeUpdatesArms() = runBlocking {
        val bandit = BanditArmState(db.banditArms())
        BanditFeedback.onIdeaShown(bandit, "cooking", "a")
        BanditFeedback.onIdeaActed(bandit, "cooking", "a")
        BanditFeedback.onIdeaDismissed(bandit, "cooking", "a")
        val a = bandit.get(BanditFeedback.POLICY_IDEAS, "cooking", "a")!!
        assertEquals(1L, a.decisionPoints)
        assertEquals(1L, a.terminalRewards)
        assertEquals(2L, a.terminalObservations)
    }
}
