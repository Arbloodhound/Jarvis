package com.agent.jarvis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class,
        TaskShortcutEntity::class, ToolReliabilityEntity::class,
        RecipeEntity::class, ScreenMemoryEntity::class, LessonEntity::class,
        com.agent.jarvis.harness.ClaimEntity::class, com.agent.jarvis.harness.GrantEntity::class,
        com.agent.jarvis.harness.MemoryEmbeddingEntity::class,
        com.agent.jarvis.harness.BanditArmEntity::class],
    version = 9,
)
abstract class UltraDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun taskMemory(): TaskMemoryDao
    abstract fun recipes(): RecipeDao
    abstract fun screenMemory(): ScreenMemoryDao
    abstract fun lessons(): LessonDao
    abstract fun claims(): com.agent.jarvis.harness.ClaimDao
    abstract fun claimForget(): com.agent.jarvis.harness.ClaimForgetDao
    abstract fun grants(): com.agent.jarvis.harness.GrantDao
    abstract fun embeddings(): com.agent.jarvis.harness.EmbeddingDao
    abstract fun banditArms(): com.agent.jarvis.harness.BanditArmDao

    companion object {
        @Volatile private var instance: UltraDatabase? = null

        /** v2 → v3 adds the recipes table. Written as a real migration rather
         * than a destructive fallback: conversations and task memory are the
         * user's data now, not dev scratch. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recipes` (" +
                        "`name` TEXT NOT NULL, " +
                        "`stepsJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastRun` INTEGER NOT NULL, " +
                        "`runCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`name`))"
                )
            }
        }

        /** v3 → v4 records the full calls, not just the tool names. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `task_shortcuts` ADD COLUMN `stepsJson` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4 → v5 adds what the agent has learned about each screen. Structure
         * only: no text from any screen is stored here. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `screen_memory` (" +
                        "`screenKey` TEXT NOT NULL, " +
                        "`pkg` TEXT NOT NULL, " +
                        "`template` TEXT NOT NULL, " +
                        "`recordCount` INTEGER NOT NULL, " +
                        "`fieldsCsv` TEXT NOT NULL, " +
                        "`seenCount` INTEGER NOT NULL, " +
                        "`lastSeen` INTEGER NOT NULL, " +
                        "`confidence` TEXT NOT NULL, " +
                        "PRIMARY KEY(`screenKey`))"
                )
            }
        }

        /** v5 → v6 remembers where a screen's controls are. Ids and roles
         * only — no label and no value is written here. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `screen_memory` ADD COLUMN `controlsJson` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v6 → v7 adds lessons: the phone's own experience store (agent/Experience.kt). */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lessons` (" +
                        "`uid` TEXT NOT NULL, " +
                        "`whenText` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`served` INTEGER NOT NULL, " +
                        "`ok` INTEGER NOT NULL, " +
                        "`fail` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uid`))"
                )
            }
        }

        /** v7 → v8 adds the harness tables: memory claims (harness/MemoryClaims.kt) and durable
         * approval grants (harness/ApprovalGrants.kt). Columns and indices match what Room
         * generates for those entities (UltraDatabaseMigrationTest compares them). */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_claims` (" +
                        "`id` TEXT NOT NULL, `text` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`salience` TEXT NOT NULL, `confidence` REAL NOT NULL, `speaker` TEXT NOT NULL, " +
                        "`sourceMessage` TEXT NOT NULL, `quote` TEXT NOT NULL, `supersedes` TEXT NOT NULL, " +
                        "`firstSeen` INTEGER NOT NULL, `reinforcedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`evergreen` INTEGER NOT NULL, `invalidatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_claims_status` ON `memory_claims` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_claims_kind` ON `memory_claims` (`kind`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `approval_grants` (" +
                        "`id` TEXT NOT NULL, `level` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `tool` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `singleUse` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_grants_level` ON `approval_grants` (`level`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_grants_scopeKey` ON `approval_grants` (`scopeKey`)")
            }
        }

        /** v8 → v9 adds two harness tables: memory claim embeddings (harness/ClaimVerifier.kt,
         * the two-lens verifier's similarity search) and bandit arm state (harness/BanditArmState.kt,
         * the proactive-surface reward counters). Columns and indices match what Room generates for
         * those entities (HarnessDatabaseV9Test compares them). */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_embeddings` (" +
                        "`claim_id` TEXT NOT NULL, `model_name` TEXT NOT NULL, " +
                        "`embedding` BLOB NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`claim_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bandit_arm_state` (" +
                        "`policy_id` TEXT NOT NULL, `domain` TEXT NOT NULL, `lane` TEXT NOT NULL, " +
                        "`decision_points` INTEGER NOT NULL, `terminal_rewards` INTEGER NOT NULL, " +
                        "`terminal_observations` INTEGER NOT NULL, `fast_reward_sum` REAL NOT NULL, " +
                        "`fast_observations` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`policy_id`, `domain`, `lane`))"
                )
            }
        }

        fun get(context: Context): UltraDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, UltraDatabase::class.java, "ultra.db"
            ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            // Only the pre-migration dev schema (v1) may be wiped. A future version
            // bump without its migration now fails loudly instead of silently
            // deleting every lesson, task and screen the user built up.
            .fallbackToDestructiveMigrationFrom(1)
            .fallbackToDestructiveMigrationOnDowngrade()  // adb install -d of an older build
            .build().also { instance = it }
        }
    }
}
