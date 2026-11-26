package com.togotiming.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.togotiming.app.domain.FlatNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DAO / 事务约束的仪器化测试（需要设备或模拟器：connectedDebugAndroidTest）。
 * 覆盖：进行中唯一、tagUid 唯一、path 唯一、导入整体回滚。
 */
@RunWith(AndroidJUnit4::class)
class RepositoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TimingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TimingRepository(db, db.activityTypeDao(), db.timeRecordDao(), db.nfcBindingDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun startingTwiceKeepsSingleActiveRecord() = runBlocking {
        val a = repo.createType(null, "工作")
        val b = repo.createType(null, "学习")
        repo.start(a, SOURCE_MANUAL)
        Thread.sleep(5)
        repo.start(b, SOURCE_NFC)
        val active = db.timeRecordDao().getActiveList()
        assertEquals(1, active.size)
        assertEquals(b, active.first().typeId)
        val finished = db.timeRecordDao().getAll().filter { it.endAt != null }
        assertEquals(1, finished.size)
    }

    @Test
    fun tagUidMustBeUnique() = runBlocking {
        val a = repo.createType(null, "工作")
        val b = repo.createType(null, "学习")
        repo.bindNfc("A1B2C3", a)
        // 同一个 UID 再绑定会改成新类型，而不是插入重复行
        repo.bindNfc("a1b2c3", b)
        val all = db.nfcBindingDao().getAll()
        assertEquals(1, all.size)
        assertEquals(b, all.first().typeId)
        assertEquals("A1B2C3", all.first().tagUid)
    }

    @Test
    fun pathMustBeUnique() = runBlocking {
        repo.createType(null, "工作")
        repo.createType(null, "工作")
        val roots = db.activityTypeDao().getAll().filter { it.parentId == null }
        assertEquals(2, roots.size)
        assertEquals(2, roots.map { it.path }.toSet().size)
    }

    @Test
    fun bindingOnlyAllowsLeaf() = runBlocking {
        val parent = repo.createType(null, "学习")
        repo.createType(parent, "英语")
        var failed = false
        try {
            repo.bindNfc("DEADBEEF", parent)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun importRollsBackWhenParentMissing() = runBlocking {
        val nodes = listOf(
            FlatNode("a", "A", null, null, 1, null),
            FlatNode("a/b", "B", null, null, 2, "missing-parent"),
        )
        val outcome = repo.importActivities(nodes, ImportConflictStrategy.SKIP)
        assertTrue(outcome is ImportOutcome.Failure)
        assertEquals(0, db.activityTypeDao().countAll())
    }

    @Test
    fun nestedDepthLimitedToThree() = runBlocking {
        val a = repo.createType(null, "一级")
        val b = repo.createType(a, "二级")
        val c = repo.createType(b, "三级")
        var failed = false
        try {
            repo.createType(c, "四级")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
