package com.phoneai.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Phase 0C: Mission Ownership
 *
 * Room-backed persistent mission store. Missions survive process death,
 * app updates, and device restarts. Single active mission at a time.
 */
@Database(entities = [MissionEntity::class], version = 1, exportSchema = false)
abstract class OrionMissionDatabase : RoomDatabase() {
    abstract fun missionDao(): MissionDao

    companion object {
        @Volatile private var INSTANCE: OrionMissionDatabase? = null

        fun getInstance(context: Context): OrionMissionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrionMissionDatabase::class.java,
                    "orion_missions"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

@Entity(tableName = "missions")
data class MissionEntity(
    @PrimaryKey val id: String,
    val requestId: String,
    val intentJson: String,
    val decisionJson: String,
    val planJson: String?,
    val status: String,
    val checkpointJson: String?,
    val specialistReceiptsJson: String,
    val evidenceJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val failureReason: String?,
    val recoveryPlanJson: String?,
    val parentMissionId: String?
) {
    internal fun toMission(): OrionMission = OrionMission(
        id = id,
        requestId = requestId,
        intent = Json.decodeFromString(OrionIntentIntelligence.Intent.serializer(), intentJson),
        decision = Json.decodeFromString(OrionDecisionEngine.Decision.serializer(), decisionJson),
        plan = planJson?.let { Json.decodeFromString(OrionPlanningEngine.ExecutionPlan.serializer(), it) },
        status = OrionMissionStatus.valueOf(status),
        checkpointJson = checkpointJson,
        specialistReceipts = Json.decodeFromString<List<OrionSpecialistReceipt>>(specialistReceiptsJson),
        evidence = Json.decodeFromString<List<StepEvidence>>(evidenceJson),
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        failureReason = failureReason,
        recoveryPlan = recoveryPlanJson,
        parentMissionId = parentMissionId
    )
}

@Dao
interface MissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mission: MissionEntity)

    @Update
    suspend fun update(mission: MissionEntity)

    @Query("SELECT * FROM missions WHERE id = :id")
    suspend fun getById(id: String): MissionEntity?

    @Query("SELECT * FROM missions WHERE status IN ('INTAKE', 'PLANNED', 'EXECUTING', 'VERIFYING', 'SUSPENDED', 'CHECKPOINTED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveMission(): MissionEntity?

    @Query("SELECT * FROM missions WHERE status IN ('FAILED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLastFailedMission(): MissionEntity?

    @Query("SELECT * FROM missions WHERE parentMissionId = :parentId ORDER BY createdAt ASC")
    suspend fun getChildMissions(parentId: String): List<MissionEntity>

    @Query("SELECT * FROM missions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MissionEntity>

    @Query("DELETE FROM missions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM missions WHERE createdAt < :cutoff AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun cleanupOld(cutoff: Long)
}

/**
 * High-level mission store API used by OrionMissionController.
 */
internal class OrionMissionStore(private val context: Context) {

    private val dao = OrionMissionDatabase.getInstance(context).missionDao()
    private val json = Json { ignoreUnknownKeys = true }

    private fun toEntity(mission: OrionMission): MissionEntity = MissionEntity(
        id = mission.id,
        requestId = mission.requestId,
        intentJson = json.encodeToString(OrionIntentIntelligence.Intent.serializer(), mission.intent),
        decisionJson = json.encodeToString(OrionDecisionEngine.Decision.serializer(), mission.decision),
        planJson = mission.plan?.let { json.encodeToString(OrionPlanningEngine.ExecutionPlan.serializer(), it) },
        status = mission.status.name,
        checkpointJson = mission.checkpointJson,
        specialistReceiptsJson = json.encodeToString(mission.specialistReceipts),
        evidenceJson = json.encodeToString(mission.evidence),
        createdAt = mission.createdAt,
        updatedAt = mission.updatedAt,
        completedAt = mission.completedAt,
        failureReason = mission.failureReason,
        recoveryPlanJson = mission.recoveryPlan,
        parentMissionId = mission.parentMissionId
    )

    suspend fun save(mission: OrionMission) {
        withContext(Dispatchers.IO) { dao.insert(toEntity(mission)) }
    }

    suspend fun update(mission: OrionMission) {
        withContext(Dispatchers.IO) { dao.update(toEntity(mission)) }
    }

    suspend fun getById(id: String): OrionMission? {
        return withContext(Dispatchers.IO) { dao.getById(id)?.toMission() }
    }

    suspend fun getActiveMission(): OrionMission? {
        return withContext(Dispatchers.IO) { dao.getActiveMission()?.toMission() }
    }

    suspend fun getLastFailedMission(): OrionMission? {
        return withContext(Dispatchers.IO) { dao.getLastFailedMission()?.toMission() }
    }

    suspend fun getChildMissions(parentId: String): List<OrionMission> {
        return withContext(Dispatchers.IO) { dao.getChildMissions(parentId).map { it.toMission() } }
    }

    suspend fun getRecent(limit: Int = 50): List<OrionMission> {
        return withContext(Dispatchers.IO) { dao.getRecent(limit).map { it.toMission() } }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { dao.delete(id) }
    }

    suspend fun cleanupOlderThan(days: Int = 30) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        withContext(Dispatchers.IO) { dao.cleanupOld(cutoff) }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        OrionMissionDatabase.getInstance(context).openHelper.writableDatabase.query("SELECT COUNT(*) FROM missions", emptyArray()).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }
}