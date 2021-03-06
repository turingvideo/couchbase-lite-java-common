//
// Copyright (c) 2020, 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;

import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.ExecutionService;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.FileUtils;
import com.couchbase.lite.internal.utils.Fn;
import com.couchbase.lite.internal.utils.Report;
import com.couchbase.lite.internal.utils.StringUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public abstract class BaseTest extends PlatformBaseTest {
    protected static final String TEST_DATE = "2019-02-21T05:37:22.014Z";
    protected static final String BLOB_CONTENT = "Knox on fox in socks in box. Socks on Knox and Knox in box.";

    private final AtomicReference<AssertionError> testFailure = new AtomicReference<>();

    protected ExecutionService.CloseableExecutor testSerialExecutor;

    @AfterClass
    public static void tearDownBaseTestClass() {
        String path = CouchbaseLiteInternal.getDbDirectoryPath();
        Report.log(LogLevel.INFO, "Deleting db directory: " + path);
        FileUtils.deleteContents(new File(path));
        path = CouchbaseLiteInternal.getTmpDirectoryPath();
        Report.log(LogLevel.INFO, "Deleting tmp directory: " + path);
        FileUtils.deleteContents(new File(CouchbaseLiteInternal.getTmpDirectoryPath()));
        Report.log(LogLevel.INFO, "Directories deleted");
    }

    @Before
    public final void setUpBaseTest() {
        // reset the directories
        CouchbaseLiteInternal.setupDirectories(null);

        Log.initLogging();

        setupPlatform();

        testFailure.set(null);

        testSerialExecutor = CouchbaseLiteInternal.getExecutionService().getSerialExecutor();
    }

    @After
    public final void tearDownBaseTest() {
        Report.log(LogLevel.INFO, "Stopping executor: " + testSerialExecutor);
        boolean succeeded = false;
        if (testSerialExecutor != null) { succeeded = testSerialExecutor.stop(2, TimeUnit.SECONDS); }
        Report.log(LogLevel.INFO, "Executor stopped: " + succeeded);
    }

    protected final String getUniqueName(@NonNull String prefix) { return StringUtils.getUniqueName(prefix, 24); }

    // Prefer this method to any other way of creating a new database
    protected final Database createDb(@NonNull String name) throws CouchbaseLiteException {
        return createDb(name, null);
    }

    // Prefer this method to any other way of creating a new database
    protected final Database createDb(@NonNull String name, @Nullable DatabaseConfiguration config)
        throws CouchbaseLiteException {
        if (config == null) { config = new DatabaseConfiguration(); }
        final String dbName = getUniqueName(name);
        final File dbDir = new File(config.getDirectory(), dbName + DB_EXTENSION);
        assertFalse(dbDir.exists());
        final Database db = new Database(dbName, config);
        assertTrue(dbDir.exists());
        return db;
    }

    protected final Database duplicateDb(@NonNull Database db) throws CouchbaseLiteException {
        return duplicateDb(db, new DatabaseConfiguration());
    }

    protected final Database duplicateDb(@NonNull Database db, @Nullable DatabaseConfiguration config)
        throws CouchbaseLiteException {
        return new Database(db.getName(), (config != null) ? config : new DatabaseConfiguration());
    }

    protected final Database reopenDb(@NonNull Database db) throws CouchbaseLiteException {
        return reopenDb(db, null);
    }

    protected final Database reopenDb(@NonNull Database db, @Nullable DatabaseConfiguration config)
        throws CouchbaseLiteException {
        final String dbName = db.getName();
        assertTrue(closeDb(db));
        return new Database(dbName, (config != null) ? config : new DatabaseConfiguration());
    }

    protected final Database recreateDb(@NonNull Database db) throws CouchbaseLiteException {
        return recreateDb(db, null);
    }

    protected final Database recreateDb(@NonNull Database db, @Nullable DatabaseConfiguration config)
        throws CouchbaseLiteException {
        final String dbName = db.getName();
        assertTrue(deleteDb(db));
        return new Database(dbName, (config != null) ? config : new DatabaseConfiguration());
    }

    protected final boolean closeDb(@Nullable Database db) {
        if ((db == null) || (!db.isOpen())) { return true; }
        return doSafely("Close db " + db.getName(), db::close);
    }

    protected final boolean deleteDb(@Nullable Database db) {
        if (db == null) { return true; }
        return (db.isOpen())
            ? doSafely("Delete db " + db.getName(), db::delete)
            : FileUtils.eraseFileOrDir(db.getDbFile());
    }

    protected final void runSafely(Runnable test) {
        try { test.run(); }
        catch (AssertionError failure) {
            Report.log(LogLevel.DEBUG, "Test failed", failure);
            testFailure.compareAndSet(null, failure);
        }
    }

    protected final void runSafelyInThread(CountDownLatch latch, Runnable test) {
        new Thread(() -> {
            try { test.run(); }
            catch (AssertionError failure) {
                Report.log(LogLevel.DEBUG, "Test failed", failure);
                testFailure.compareAndSet(null, failure);
            }
            finally { latch.countDown(); }
        }).start();
    }

    protected final void checkForFailure() {
        AssertionError failure = testFailure.get();
        if (failure != null) { throw new AssertionError(failure); }
    }

    private boolean doSafely(@NonNull String taskDesc, @NonNull Fn.TaskThrows<CouchbaseLiteException> task) {
        try {
            task.run();
            Report.log(LogLevel.DEBUG, taskDesc + " succeeded");
            return true;
        }
        catch (CouchbaseLiteException ex) {
            Report.log(LogLevel.WARNING, taskDesc + " failed", ex);
        }
        return false;
    }
}

