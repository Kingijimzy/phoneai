package com.phoneai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Durable executive ledger. The language model proposes intent and plans; this store enforces
 * objective phases, optimistic concurrency, step evidence and restart-visible recovery state.
 */
internal object OrionExecutiveStore {
    enum class Phase {
        GROUND, RETRIEVE, ASSESS, SELECT, PERFORM, VERIFY, LEARN,
        WAITING_FOR_OWNER, BLOCKED, COMPLETED, FAILED
    }

    enum class StepStatus { PENDING, READY, RUNNING, RECONCILIATION_REQUIRED, VERIFIED, FAILED, SKIPPED }
    enum class DispatchOutcome { VERIFIED, EXECUTED_UNVERIFIED, FAILED, RECONCILIATION_REQUIRED }
    private val processEpoch = System.currentTimeMillis().toString(36) + "-" + System.nanoTime().toString(36)

    data class Objective(
        val id: String,
        val intent: String,
        val phase: Phase,
        val revision: Long,
        val planRevision: Long,
        val recoveryCursor: String,
        val blocker: String,
        val updatedAt: Long
    )

    data class Step(
        val id: String,
        val objectiveId: String,
        val ordinal: Int,
        val title: String,
        val dependencies: List<String>,
        val status: StepStatus,
        val capability: String,
        val argumentsDigest: String,
        val attempts: Int,
        val resultSummary: String,
        val evidenceRef: String
    )

    private class Db(context: Context) : SQLiteOpenHelper(context, "orion_executive.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // SQLite foreign-key declarations are inert unless every connection enables them.
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE objectives(
                id TEXT PRIMARY KEY NOT NULL, intent TEXT NOT NULL, phase TEXT NOT NULL,
                revision INTEGER NOT NULL, plan_revision INTEGER NOT NULL DEFAULT 0,
                recovery_cursor TEXT NOT NULL DEFAULT '', blocker TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
            db.execSQL("CREATE INDEX idx_objectives_updated ON objectives(updated_at DESC)")
            db.execSQL("""CREATE TABLE objective_steps(
                id TEXT PRIMARY KEY NOT NULL, objective_id TEXT NOT NULL, ordinal INTEGER NOT NULL,
                title TEXT NOT NULL, dependencies TEXT NOT NULL, status TEXT NOT NULL,
                capability TEXT NOT NULL DEFAULT '', arguments_digest TEXT NOT NULL DEFAULT '',
                attempts INTEGER NOT NULL DEFAULT 0, result_summary TEXT NOT NULL DEFAULT '',
                evidence_ref TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL,
                FOREIGN KEY(objective_id) REFERENCES objectives(id) ON DELETE CASCADE,
                UNIQUE(objective_id, ordinal))""")
            db.execSQL("CREATE INDEX idx_steps_objective ON objective_steps(objective_id, ordinal)")
            createDispatchAttempts(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createDispatchAttempts(db)
        }
        private fun createDispatchAttempts(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS dispatch_attempts(
                attempt_id TEXT PRIMARY KEY NOT NULL, objective_id TEXT NOT NULL,
                graph_step_id TEXT NOT NULL DEFAULT '', capability TEXT NOT NULL,
                arguments_digest TEXT NOT NULL, process_epoch TEXT NOT NULL,
                outcome TEXT NOT NULL, evidence_ref TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '', started_at INTEGER NOT NULL,
                finished_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(objective_id) REFERENCES objectives(id) ON DELETE CASCADE)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_dispatch_objective ON dispatch_attempts(objective_id, started_at DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_dispatch_recovery ON dispatch_attempts(outcome, process_epoch)")
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private val terminal = setOf(Phase.COMPLETED, Phase.FAILED)
    private val allowed = mapOf(
        Phase.GROUND to setOf(Phase.RETRIEVE, Phase.BLOCKED, Phase.FAILED),
        Phase.RETRIEVE to setOf(Phase.ASSESS, Phase.BLOCKED, Phase.FAILED),
        Phase.ASSESS to setOf(Phase.SELECT, Phase.WAITING_FOR_OWNER, Phase.BLOCKED, Phase.FAILED),
        Phase.SELECT to setOf(Phase.PERFORM, Phase.WAITING_FOR_OWNER, Phase.BLOCKED, Phase.FAILED),
        Phase.PERFORM to setOf(Phase.VERIFY, Phase.WAITING_FOR_OWNER, Phase.BLOCKED, Phase.FAILED),
        Phase.VERIFY to setOf(Phase.LEARN, Phase.PERFORM, Phase.WAITING_FOR_OWNER, Phase.BLOCKED, Phase.FAILED),
        Phase.LEARN to setOf(Phase.GROUND, Phase.COMPLETED, Phase.BLOCKED, Phase.FAILED),
        Phase.WAITING_FOR_OWNER to setOf(Phase.GROUND, Phase.BLOCKED, Phase.FAILED),
        Phase.BLOCKED to setOf(Phase.GROUND, Phase.FAILED)
    )

    internal fun canTransition(from: Phase, to: Phase): Boolean = to in allowed[from].orEmpty()

    fun create(context: Context, id: String, intent: String): Objective? {
        val cleanId = id.trim().take(100)
        val cleanIntent = intent.trim().take(2_000)
        if (cleanId.isBlank() || cleanIntent.isBlank()) return null
        val now = System.currentTimeMillis()
        return runCatching {
            val db = Db(context.applicationContext).writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO objectives(id,intent,phase,revision,plan_revision,recovery_cursor,blocker,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf(cleanId, cleanIntent, Phase.GROUND.name, 0, 0, "", "", now, now)
                )
                val existing = get(db, cleanId)
                // An ID collision must never silently attach a new request to another objective.
                if (existing?.intent != cleanIntent) return null
                db.setTransactionSuccessful()
                existing
            } finally { db.endTransaction() }
        }.getOrNull()
    }

    fun get(context: Context, id: String): Objective? =
        runCatching { Db(context.applicationContext).readableDatabase.use { get(it, id) } }.getOrNull()

    private fun get(db: SQLiteDatabase, id: String): Objective? =
        db.rawQuery("SELECT id,intent,phase,revision,plan_revision,recovery_cursor,blocker,updated_at FROM objectives WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) null else Objective(
                it.getString(0), it.getString(1), Phase.valueOf(it.getString(2)), it.getLong(3),
                it.getLong(4), it.getString(5), it.getString(6), it.getLong(7)
            )
        }

    /** Compare-and-set transition: stale workers cannot overwrite a newer executive decision. */
    fun transition(
        context: Context,
        id: String,
        expectedRevision: Long,
        to: Phase,
        recoveryCursor: String = "",
        blocker: String = ""
    ): Objective? = runCatching {
        val db = Db(context.applicationContext).writableDatabase
        db.beginTransaction()
        try {
            val current = get(db, id) ?: return null
            if (current.revision != expectedRevision || terminal.contains(current.phase) ||
                !canTransition(current.phase, to)) return null
            val changed = db.compileStatement(
                "UPDATE objectives SET phase=?,revision=revision+1,recovery_cursor=?,blocker=?,updated_at=? WHERE id=? AND revision=?"
            ).run {
                bindString(1, to.name)
                bindString(2, recoveryCursor.take(240))
                bindString(3, blocker.take(500))
                bindLong(4, System.currentTimeMillis())
                bindString(5, id)
                bindLong(6, expectedRevision)
                executeUpdateDelete()
            }
            if (changed != 1) return null
            db.setTransactionSuccessful()
            get(db, id)
        } finally { db.endTransaction() }
    }.getOrNull()

    /** Validate a bounded dependency graph before it can become the persisted execution plan. */
    internal fun isValidPlan(steps: List<Step>): Boolean {
        if (steps.isEmpty() || steps.size > 32 ||
            steps.any { it.id.isBlank() || it.id.length > 120 || it.title.isBlank() || it.title.length > 240 }) return false
        val byId = steps.associateBy { it.id }
        if (byId.size != steps.size) return false
        if (steps.any { step -> step.dependencies.size != step.dependencies.distinct().size || step.dependencies.any { it == step.id || it !in byId } }) return false
        if (steps.any { it.objectiveId.isNotBlank() && it.objectiveId != steps.first().objectiveId }) return false
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return false
            if (id in visited) return true
            visiting.add(id)
            val node = byId[id] ?: return false
            for (dependency in node.dependencies) if (!visit(dependency)) return false
            visiting.remove(id)
            visited.add(id)
            return true
        }
        return byId.keys.all(::visit)
    }

    /** A prior result survives replanning only when the executable meaning is unchanged. */
    internal fun canResolveStepResult(current: StepStatus, next: StepStatus, evidenceRef: String): Boolean =
        current in setOf(StepStatus.RUNNING, StepStatus.RECONCILIATION_REQUIRED) &&
            next in setOf(StepStatus.VERIFIED, StepStatus.FAILED, StepStatus.SKIPPED) &&
            (next != StepStatus.VERIFIED || evidenceRef.isNotBlank())

    internal fun canPreserveStepState(existing: Step, proposed: Step): Boolean =
        existing.id == proposed.id &&
            existing.title == proposed.title &&
            existing.dependencies == proposed.dependencies &&
            existing.capability == proposed.capability &&
            existing.argumentsDigest == proposed.argumentsDigest

    /** Replace a proposed plan atomically. Evidence survives only for semantically identical steps. */
    fun replacePlan(context: Context, objectiveId: String, steps: List<Step>): Boolean {
        if (!isValidPlan(steps) || steps.any { it.objectiveId.isNotBlank() && it.objectiveId != objectiveId }) return false
        val db = Db(context.applicationContext).writableDatabase
        db.beginTransaction()
        return try {
            val current = get(db, objectiveId) ?: return false
            if (terminal.contains(current.phase) || current.phase == Phase.PERFORM || current.phase == Phase.VERIFY) return false
            val nextRevision = current.planRevision + 1
            val keepIds = steps.map { it.id }.toSet()
            // Avoid UNIQUE(objective_id, ordinal) collisions when a plan reorders existing rows.
            db.execSQL("UPDATE objective_steps SET ordinal = -ordinal - 1 WHERE objective_id=?", arrayOf(objectiveId))
            db.delete("objective_steps", "objective_id=? AND id NOT IN (${keepIds.joinToString(",") { "?" }})",
                (listOf(objectiveId) + keepIds).toTypedArray())
            steps.forEachIndexed { index, step ->
                val existing = db.rawQuery("""SELECT id,objective_id,ordinal,title,dependencies,status,capability,arguments_digest,attempts,result_summary,evidence_ref
                    FROM objective_steps WHERE id=? AND objective_id=?""", arrayOf(step.id, objectiveId)).use { cursor ->
                    if (!cursor.moveToFirst()) null else Step(
                        cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3),
                        runCatching { val json = JSONArray(cursor.getString(4)); (0 until json.length()).map { json.getString(it) } }.getOrDefault(emptyList()),
                        StepStatus.valueOf(cursor.getString(5)), cursor.getString(6), cursor.getString(7),
                        cursor.getInt(8), cursor.getString(9), cursor.getString(10)
                    )
                }
                val preserve = existing != null && canPreserveStepState(existing, step)
                db.execSQL("""INSERT OR REPLACE INTO objective_steps
                    (id,objective_id,ordinal,title,dependencies,status,capability,arguments_digest,attempts,result_summary,evidence_ref,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf(step.id, objectiveId, index, step.title.take(240), JSONArray(step.dependencies).toString(),
                        if (preserve) existing!!.status.name else StepStatus.PENDING.name,
                        step.capability.take(100), step.argumentsDigest.take(128),
                        if (preserve) existing!!.attempts else 0,
                        if (preserve) existing!!.resultSummary else "",
                        if (preserve) existing!!.evidenceRef else "", System.currentTimeMillis()))
            }
            db.execSQL("UPDATE objectives SET plan_revision=?,revision=revision+1,updated_at=? WHERE id=?",
                arrayOf(nextRevision, System.currentTimeMillis(), objectiveId))
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) { false } finally { db.endTransaction() }
    }

    fun steps(context: Context, objectiveId: String): List<Step> = runCatching {
        Db(context.applicationContext).readableDatabase.use { db ->
            db.rawQuery("""SELECT id,objective_id,ordinal,title,dependencies,status,capability,arguments_digest,attempts,result_summary,evidence_ref
                FROM objective_steps WHERE objective_id=? ORDER BY ordinal""", arrayOf(objectiveId)).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val deps = runCatching {
                            val json = JSONArray(cursor.getString(4)); (0 until json.length()).map { json.getString(it) }
                        }.getOrDefault(emptyList())
                        add(Step(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3), deps,
                            StepStatus.valueOf(cursor.getString(5)), cursor.getString(6), cursor.getString(7),
                            cursor.getInt(8), cursor.getString(9), cursor.getString(10)))
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    /** Persist attempt metadata before invoking a tool; arguments themselves are never stored here. */
    fun markStepRunning(context: Context, objectiveId: String, stepId: String, capability: String, argumentsDigest: String): Boolean =
        runCatching {
            val db = Db(context.applicationContext).writableDatabase
            db.beginTransaction()
            try {
                val dependencies = db.rawQuery(
                    "SELECT dependencies FROM objective_steps WHERE id=? AND objective_id=?",
                    arrayOf(stepId, objectiveId)
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return false
                    val json = JSONArray(cursor.getString(0))
                    (0 until json.length()).map { json.getString(it) }
                }
                // A step cannot start until every prerequisite has durable VERIFIED evidence.
                val prerequisitesVerified = dependencies.all { dependency ->
                    db.rawQuery(
                        "SELECT status,evidence_ref FROM objective_steps WHERE id=? AND objective_id=?",
                        arrayOf(dependency, objectiveId)
                    ).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0) == StepStatus.VERIFIED.name &&
                            cursor.getString(1).isNotBlank()
                    }
                }
                if (!prerequisitesVerified) return false
                db.execSQL("""UPDATE objective_steps SET status=?,capability=?,arguments_digest=?,attempts=attempts+1,updated_at=?
                    WHERE id=? AND objective_id=? AND status IN (?,?)""",
                    arrayOf(StepStatus.RUNNING.name, capability.take(100), argumentsDigest.take(128), System.currentTimeMillis(),
                        stepId, objectiveId, StepStatus.PENDING.name, StepStatus.FAILED.name))
                val changed = db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) == 1 }
                if (changed) db.setTransactionSuccessful()
                changed
            } finally { db.endTransaction() }
        }.getOrDefault(false)

    /**
     * A process death while a capability is RUNNING is an unknown outcome, not a failure.
     * Quarantine it so a normal planner cannot replay a possibly non-idempotent action.
     */
    fun reconcileInterruptedSteps(context: Context, objectiveId: String): Int = runCatching {
        val db = Db(context.applicationContext).writableDatabase
        db.execSQL(
            "UPDATE objective_steps SET status=?,updated_at=? WHERE objective_id=? AND status=?",
            arrayOf(StepStatus.RECONCILIATION_REQUIRED.name, System.currentTimeMillis(), objectiveId, StepStatus.RUNNING.name)
        )
        db.rawQuery("SELECT changes()", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }.getOrDefault(0)

    /** A step is VERIFIED only when the caller supplies a real evidence reference. */
    fun recordStepResult(context: Context, objectiveId: String, stepId: String, status: StepStatus, result: String, evidenceRef: String): Boolean {
        if (status !in setOf(StepStatus.VERIFIED, StepStatus.FAILED, StepStatus.SKIPPED) ||
            !canResolveStepResult(StepStatus.RUNNING, status, evidenceRef)) return false
        return runCatching {
            val db = Db(context.applicationContext).writableDatabase
            db.execSQL("""UPDATE objective_steps SET status=?,result_summary=?,evidence_ref=?,updated_at=?
                WHERE id=? AND objective_id=? AND status IN (?,?)""",
                arrayOf(status.name, result.take(500), evidenceRef.take(160), System.currentTimeMillis(), stepId, objectiveId,
                    StepStatus.RUNNING.name, StepStatus.RECONCILIATION_REQUIRED.name))
            db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) == 1 }
        }.getOrDefault(false)
    }

    data class DispatchHandle(val objectiveId: String, val attemptId: String)

    fun beginToolDispatch(
        context: Context,
        sessionId: String,
        intent: String,
        graphStepId: String,
        capability: String,
        arguments: JSONObject,
        startedAt: Long
    ): DispatchHandle? {
        val cleanIntent = intent.trim().take(2_000).ifBlank { "Capability dispatch" }
        // Each dispatch gets its own lifecycle record. A single user objective may invoke
        // several tools, including parallel calls; sharing one phase row would let one
        // completed tool incorrectly mark another in-flight tool as complete.
        val dispatchIdentity = listOf(
            sessionId, cleanIntent, capability, digest(arguments.toString()),
            startedAt.toString(), java.util.UUID.randomUUID().toString()
        ).joinToString("|")
        val objectiveId = "exec-" + digest(dispatchIdentity).take(40)
        val attempt = beginDispatchAttempt(context, objectiveId, cleanIntent, graphStepId,
            capability, digest(arguments.toString()), startedAt) ?: return null
        return DispatchHandle(objectiveId, attempt)
    }

    /**
     * Begin a durable per-tool attempt before entering the backend. The attempt is tied to the
     * current process epoch so a later process can quarantine stale RUNNING attempts.
     */
    fun beginDispatchAttempt(
        context: Context,
        objectiveId: String,
        intent: String,
        graphStepId: String,
        capability: String,
        argumentsDigest: String,
        startedAt: Long
    ): String? {
        val cleanObjective = objectiveId.trim().take(120)
        val cleanIntent = intent.trim().take(2_000)
        val cleanCapability = capability.trim().take(100)
        if (cleanObjective.isBlank() || cleanIntent.isBlank() || cleanCapability.isBlank() ||
            !argumentsDigest.matches(Regex("^[a-fA-F0-9]{64}$"))) return null
        return runCatching {
            val db = Db(context.applicationContext).writableDatabase
            db.beginTransaction()
            try {
                // Only attempts owned by a previous process are uncertain; concurrent attempts
                // in this process are left untouched.
                db.execSQL("""UPDATE dispatch_attempts SET outcome=?,finished_at=?
                    WHERE outcome=? AND process_epoch<>?""",
                    arrayOf(DispatchOutcome.RECONCILIATION_REQUIRED.name, System.currentTimeMillis(),
                        "RUNNING", processEpoch))
                // A process-death window is an unknown side-effect outcome. Surface it as
                // BLOCKED in the executive ledger so the planner must reconcile before retry.
                db.execSQL("""UPDATE objectives SET phase=?,revision=revision+1,
                    recovery_cursor=?,blocker=?,updated_at=?
                    WHERE id IN (
                        SELECT objective_id FROM dispatch_attempts
                        WHERE outcome=? AND process_epoch<>?
                    ) AND phase NOT IN (?,?,?)""",
                    arrayOf(Phase.BLOCKED.name, "reconcile_dispatch",
                        "Previous process ended during a tool action; inspect the real outcome before retrying.",
                        System.currentTimeMillis(), DispatchOutcome.RECONCILIATION_REQUIRED.name,
                        processEpoch, Phase.COMPLETED.name, Phase.FAILED.name, Phase.BLOCKED.name))
                db.execSQL("INSERT OR IGNORE INTO objectives(id,intent,phase,revision,plan_revision,recovery_cursor,blocker,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf(cleanObjective, cleanIntent, Phase.GROUND.name, 0, 0, "", "", startedAt, startedAt))
                val existing = get(db, cleanObjective)
                if (existing == null || existing.intent != cleanIntent) return null
                val attemptId = digest("$cleanObjective|$graphStepId|$cleanCapability|$argumentsDigest|$startedAt|$processEpoch").take(32)
                db.execSQL("""INSERT INTO dispatch_attempts
                    (attempt_id,objective_id,graph_step_id,capability,arguments_digest,process_epoch,outcome,evidence_ref,summary,started_at,finished_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf(attemptId, cleanObjective, graphStepId.take(120), cleanCapability,
                        argumentsDigest.lowercase(), processEpoch, "RUNNING", "", "", startedAt, 0))
                // The tool dispatcher has already completed live retrieval, policy assessment
                // and capability selection before it opens this execution boundary.
                if (!transitionPath(db, cleanObjective, dispatchStartPath())) return null
                db.setTransactionSuccessful()
                attemptId
            } finally { db.endTransaction() }
        }.getOrNull()
    }

    /** Resolve a dispatch attempt and its executive phase atomically after evidence is durable. */
    fun finishDispatchAttempt(
        context: Context,
        attemptId: String,
        outcome: DispatchOutcome,
        evidenceRef: String,
        summary: String
    ): Boolean {
        if (attemptId.isBlank() || outcome == DispatchOutcome.RECONCILIATION_REQUIRED ||
            (outcome == DispatchOutcome.VERIFIED && evidenceRef.isBlank())) return false
        return runCatching {
            val db = Db(context.applicationContext).writableDatabase
            db.beginTransaction()
            try {
                val objectiveId = db.rawQuery(
                    "SELECT objective_id FROM dispatch_attempts WHERE attempt_id=? AND outcome=? AND process_epoch=?",
                    arrayOf(attemptId, "RUNNING", processEpoch)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return false
                db.execSQL("""UPDATE dispatch_attempts SET outcome=?,evidence_ref=?,summary=?,finished_at=?
                    WHERE attempt_id=? AND outcome=? AND process_epoch=?""",
                    arrayOf(outcome.name, evidenceRef.take(160), summary.take(320),
                        System.currentTimeMillis(), attemptId, "RUNNING", processEpoch))
                val changed = db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) == 1 }
                if (!changed) return false
                val blocker = when (outcome) {
                    DispatchOutcome.VERIFIED -> ""
                    DispatchOutcome.EXECUTED_UNVERIFIED -> "Action returned, but trusted verification evidence is missing."
                    DispatchOutcome.FAILED -> "The capability execution failed; diagnose and select a different route before retrying."
                    DispatchOutcome.RECONCILIATION_REQUIRED -> "The previous process ended during execution; reconcile the action outcome before retrying."
                }
                if (!transitionPath(db, objectiveId, dispatchFinishPath(outcome), blocker = blocker)) return false
                db.setTransactionSuccessful()
                true
            } finally { db.endTransaction() }
        }.getOrDefault(false)
    }

    data class DispatchAttempt(
        val id: String, val objectiveId: String, val graphStepId: String, val capability: String,
        val outcome: String, val evidenceRef: String, val summary: String, val startedAt: Long
    )

    fun dispatchAttempts(context: Context, objectiveId: String): List<DispatchAttempt> = runCatching {
        Db(context.applicationContext).readableDatabase.use { db ->
            db.rawQuery("""SELECT attempt_id,objective_id,graph_step_id,capability,outcome,evidence_ref,summary,started_at
                FROM dispatch_attempts WHERE objective_id=? ORDER BY started_at""", arrayOf(objectiveId)).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(DispatchAttempt(cursor.getString(0),cursor.getString(1),
                        cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5),
                        cursor.getString(6),cursor.getLong(7)))
                }
            }
        }
    }.getOrDefault(emptyList())

    internal fun dispatchOutcomeFor(trustedVerification: Boolean, failed: Boolean): DispatchOutcome = when {
        failed -> DispatchOutcome.FAILED
        trustedVerification -> DispatchOutcome.VERIFIED
        else -> DispatchOutcome.EXECUTED_UNVERIFIED
    }

    /** Phases completed before the backend is entered. */
    internal fun dispatchStartPath(): List<Phase> =
        listOf(Phase.RETRIEVE, Phase.ASSESS, Phase.SELECT, Phase.PERFORM)

    /** A successful call is not complete until verification and learning have been recorded. */
    internal fun dispatchFinishPath(outcome: DispatchOutcome): List<Phase> = when (outcome) {
        DispatchOutcome.VERIFIED -> listOf(Phase.VERIFY, Phase.LEARN, Phase.COMPLETED)
        DispatchOutcome.EXECUTED_UNVERIFIED -> listOf(Phase.VERIFY, Phase.BLOCKED)
        DispatchOutcome.FAILED -> listOf(Phase.BLOCKED)
        DispatchOutcome.RECONCILIATION_REQUIRED -> listOf(Phase.BLOCKED)
    }

    private fun transitionPath(
        db: SQLiteDatabase,
        objectiveId: String,
        path: List<Phase>,
        blocker: String = ""
    ): Boolean {
        for (phase in path) {
            val current = get(db, objectiveId) ?: return false
            if (current.phase == phase) continue
            if (!canTransition(current.phase, phase)) return false
            val changed = db.compileStatement(
                "UPDATE objectives SET phase=?,revision=revision+1,recovery_cursor=?,blocker=?,updated_at=? WHERE id=? AND revision=?"
            ).run {
                bindString(1, phase.name)
                bindString(2, if (phase == Phase.BLOCKED) "dispatch_recovery" else phase.name.lowercase())
                bindString(3, blocker.take(500))
                bindLong(4, System.currentTimeMillis())
                bindString(5, objectiveId)
                bindLong(6, current.revision)
                executeUpdateDelete()
            }
            if (changed != 1) return false
        }
        return true
    }

    fun contextBlock(context: Context): String = runCatching {
        val db = Db(context.applicationContext).readableDatabase
        val objective = db.rawQuery("""SELECT id,intent,phase,revision,plan_revision,recovery_cursor,blocker
            FROM objectives WHERE phase NOT IN (?,?) ORDER BY updated_at DESC LIMIT 1""",
            arrayOf(Phase.COMPLETED.name, Phase.FAILED.name)).use {
            if (!it.moveToFirst()) null else Objective(it.getString(0),it.getString(1),Phase.valueOf(it.getString(2)),
                it.getLong(3),it.getLong(4),it.getString(5),it.getString(6),0L)
        } ?: return ""
        val steps = steps(context, objective.id)
        buildString {
            appendLine("SQLITE EXECUTIVE LEDGER — authoritative persisted state")
            appendLine("objective_id=${objective.id}; phase=${objective.phase}; revision=${objective.revision}; plan_revision=${objective.planRevision}")
            appendLine("intent=${objective.intent.take(500)}; recovery_cursor=${objective.recoveryCursor}; blocker=${objective.blocker}")
            steps.forEach { appendLine("step[${it.ordinal}]=${it.id}; ${it.status}; ${it.title}; capability=${it.capability}; attempts=${it.attempts}; evidence=${it.evidenceRef}; result=${it.resultSummary.take(180)}") }
            appendLine("Only VERIFIED steps with evidence references are complete. Reconcile RUNNING steps before retrying; do not infer completion from a model response.")
        }
    }.getOrDefault("")
}
