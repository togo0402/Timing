package com.togotiming.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityTypeDao {

    @Query("SELECT * FROM activity_types WHERE archived = 0 ORDER BY depth, sortOrder, id")
    fun observeAllActive(): Flow<List<ActivityTypeEntity>>

    @Query("SELECT * FROM activity_types ORDER BY depth, sortOrder, id")
    fun observeAll(): Flow<List<ActivityTypeEntity>>

    @Query("SELECT * FROM activity_types ORDER BY depth, sortOrder, id")
    suspend fun getAll(): List<ActivityTypeEntity>

    @Query("SELECT * FROM activity_types WHERE archived = 0 ORDER BY depth, sortOrder, id")
    suspend fun getAllActive(): List<ActivityTypeEntity>

    @Query("SELECT * FROM activity_types WHERE id = :id")
    suspend fun getById(id: Long): ActivityTypeEntity?

    @Query("SELECT * FROM activity_types WHERE path = :path")
    suspend fun getByPath(path: String): ActivityTypeEntity?

    /** 叶子 = 没有未归档子节点的未归档节点 */
    @Query(
        "SELECT * FROM activity_types t WHERE t.archived = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM activity_types c WHERE c.parentId = t.id AND c.archived = 0) " +
            "ORDER BY t.depth, t.sortOrder, t.id"
    )
    fun observeLeaves(): Flow<List<ActivityTypeEntity>>

    @Query(
        "SELECT * FROM activity_types t WHERE t.archived = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM activity_types c WHERE c.parentId = t.id AND c.archived = 0) " +
            "ORDER BY t.depth, t.sortOrder, t.id"
    )
    suspend fun getLeaves(): List<ActivityTypeEntity>

    @Query(
        "SELECT COUNT(*) FROM activity_types WHERE archived = 0 AND " +
            "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)"
    )
    suspend fun countChildren(parentId: Long?): Int

    @Query("SELECT COUNT(*) FROM activity_types WHERE archived = 0 AND parentId IS NULL")
    suspend fun countRoots(): Int

    @Query(
        "SELECT COALESCE(MAX(sortOrder), -1) FROM activity_types WHERE " +
            "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)"
    )
    suspend fun maxSortOrder(parentId: Long?): Int

    @Query("SELECT * FROM activity_types WHERE parentId = :parentId ORDER BY sortOrder, id")
    suspend fun getChildren(parentId: Long): List<ActivityTypeEntity>

    @Insert
    suspend fun insert(entity: ActivityTypeEntity): Long

    @Update
    suspend fun update(entity: ActivityTypeEntity)

    @Query("UPDATE activity_types SET archived = 1 WHERE id IN (:ids)")
    suspend fun archiveIds(ids: List<Long>)

    @Query("UPDATE activity_types SET parentId = :parentId, depth = :depth, path = :path, sortOrder = :sortOrder, colorSeed = NULL WHERE id = :id")
    suspend fun reparent(id: Long, parentId: Long?, depth: Int, path: String, sortOrder: Int)

    @Query("DELETE FROM activity_types")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM activity_types")
    suspend fun countAll(): Int
}

@Dao
interface TimeRecordDao {

    @Query("SELECT * FROM time_records WHERE endAt IS NULL ORDER BY startAt DESC LIMIT 1")
    fun observeActive(): Flow<TimeRecordEntity?>

    @Query("SELECT * FROM time_records WHERE endAt IS NULL ORDER BY startAt DESC LIMIT 1")
    suspend fun getActive(): TimeRecordEntity?

    @Query("SELECT * FROM time_records WHERE endAt IS NULL")
    suspend fun getActiveList(): List<TimeRecordEntity>

    /** 结束所有进行中的记录，返回受影响行数 */
    @Query("UPDATE time_records SET endAt = :endAt WHERE endAt IS NULL")
    suspend fun stopAllActive(endAt: Long): Int

    @Query("UPDATE time_records SET endAt = :endAt WHERE id = :id AND endAt IS NULL")
    suspend fun stopById(id: Long, endAt: Long): Int

    /** 与 [from, to) 有交集的所有记录（含进行中） */
    @Query("SELECT * FROM time_records WHERE startAt < :to AND (endAt IS NULL OR endAt > :from) ORDER BY startAt")
    fun observeOverlapping(from: Long, to: Long): Flow<List<TimeRecordEntity>>

    @Query("SELECT * FROM time_records WHERE startAt < :to AND (endAt IS NULL OR endAt > :from) ORDER BY startAt")
    suspend fun getOverlapping(from: Long, to: Long): List<TimeRecordEntity>

    @Query("SELECT * FROM time_records WHERE id = :id")
    suspend fun getById(id: Long): TimeRecordEntity?

    @Query("SELECT * FROM time_records ORDER BY startAt")
    suspend fun getAll(): List<TimeRecordEntity>

    @Query("SELECT * FROM time_records WHERE typeId = :typeId ORDER BY startAt DESC")
    suspend fun getByType(typeId: Long): List<TimeRecordEntity>

    @Insert
    suspend fun insert(entity: TimeRecordEntity): Long

    @Update
    suspend fun update(entity: TimeRecordEntity)

    @Query("DELETE FROM time_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM time_records")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM time_records WHERE endAt IS NULL")
    suspend fun countActive(): Int

    /** 最近一条 [before] 之前结束的记录，用于小部件空闲态展示「上次」 */
    @Query("SELECT * FROM time_records WHERE endAt IS NOT NULL AND endAt <= :before ORDER BY endAt DESC LIMIT 1")
    suspend fun getLastFinished(before: Long): TimeRecordEntity?
}

@Dao
interface NfcBindingDao {

    @Query("SELECT * FROM nfc_bindings ORDER BY id")
    fun observeAll(): Flow<List<NfcBindingEntity>>

    @Query("SELECT * FROM nfc_bindings ORDER BY id")
    suspend fun getAll(): List<NfcBindingEntity>

    @Query("SELECT * FROM nfc_bindings WHERE tagUid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): NfcBindingEntity?

    @Query("SELECT * FROM nfc_bindings WHERE typeId = :typeId LIMIT 1")
    suspend fun getByType(typeId: Long): NfcBindingEntity?

    @Insert
    suspend fun insert(entity: NfcBindingEntity): Long

    @Update
    suspend fun update(entity: NfcBindingEntity)

    @Query("DELETE FROM nfc_bindings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM nfc_bindings")
    suspend fun deleteAll()
}
