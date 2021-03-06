//
// Copyright (c) 2020, 2019 Couchbase, Inc All rights reserved.
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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.ExecutionService;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Fn;


/**
 * Platform test class for Java.
 */
public abstract class PlatformBaseTest implements PlatformTest {
    public static final String PRODUCT = "Java";
    public static final String LEGAL_FILE_NAME_CHARS = "`~@#$%&'()_+{}][=-.,;'ABCDEabcde";
    public static final String DB_EXTENSION = AbstractDatabase.DB_EXTENSION;
    private static final String TEST_DIR = ".test";
    private static final File LOG_DIR = new File(TEST_DIR, "logs");
    private static final File SCRATCH_DIR = new File(TEST_DIR, "scratch");
    private static final long MAX_LOG_FILE_BYTES = Long.MAX_VALUE; // lots
    private static final int MAX_LOG_FILES = Integer.MAX_VALUE; // lots

    private static final Map<String, Fn.Provider<Boolean>> PLATFORM_DEPENDENT_TESTS;

    static {
        final Map<String, Fn.Provider<Boolean>> m = new HashMap<>();
        m.put("windows", () -> {
            final String os = System.getProperty("os.name");
            return (os != null) && os.toLowerCase().contains("win");
        });
        PLATFORM_DEPENDENT_TESTS = Collections.unmodifiableMap(m);
    }

    // this should probably go in the BaseTest but
    // there are several tests (C4 tests) that are not subclasses
    static {
        initCouchbase();
        makeTestDir();
    }

    private static LogFileConfiguration logConfig;

    // for testing, use the current directory as the root
    public static void initCouchbase() { CouchbaseLite.init(); }

    public static void deinitCouchbase() { CouchbaseLiteInternal.reset(); }

    @BeforeClass
    public static void setUpPlatformSuite() { System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>> Suite started"); }

    @AfterClass
    public static void tearDownBaseTestClass() { System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<< Suite completed"); }

    private static boolean makeTestDir() {
        boolean ok = false;
        try { ok = new File(TEST_DIR).mkdirs(); }
        catch (Exception ignore) { }
        return ok;
    }


    private String testName;

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) { testName = description.getMethodName(); }
    };

    @Before
    public void setUpPlatformTest() { System.out.println(">>>>>>>>> Test started: " + testName); }

    @After
    public void tearDownPlatformTest() { System.out.println("<<<<<<<<< Test completed: " + testName); }

    // set up the file logger...
    @Override
    public void setupPlatform() {
        if (logConfig == null) {
            logConfig = new LogFileConfiguration(getDirPath(LOG_DIR))
                .setUsePlaintext(true)
                .setMaxSize(MAX_LOG_FILE_BYTES)
                .setMaxRotateCount(MAX_LOG_FILES);
        }

        final FileLogger fileLogger = Database.log.getFile();
        if (!logConfig.equals(fileLogger.getConfig())) { fileLogger.setConfig(logConfig); }
        fileLogger.setLevel(LogLevel.DEBUG);
        Log.d(LogDomain.DATABASE, "========= Test initialized: %s", testName);
    }

    @Override
    public void reloadStandardErrorMessages() { Log.initLogging(CouchbaseLiteInternal.loadErrorMessages()); }

    @NotNull
    @Override
    public String getDatabaseDirectoryPath() { return CouchbaseLiteInternal.getDbDirectoryPath(); }

    @Override
    public String getScratchDirectoryPath(String name) { return getDirPath(new File(SCRATCH_DIR, name)); }

    @Override
    public final boolean handlePlatformSpecially(String tag) {
        final Fn.Provider<Boolean> test = PLATFORM_DEPENDENT_TESTS.get(tag);
        return (test != null) && test.get();
    }

    @Override
    public void executeAsync(long delayMs, Runnable task) {
        ExecutionService executionService = CouchbaseLiteInternal.getExecutionService();
        executionService.postDelayedOnExecutor(delayMs, executionService.getMainExecutor(), task);
    }

    @NotNull
    private String getDirPath(File dir) {
        try {
            if (dir.exists() || dir.mkdirs()) { return dir.getCanonicalPath(); }
            throw new IOException("Cannot create directory: " + dir);
        }
        catch (IOException e) { throw new IllegalStateException("Cannot create log directory", e); }
    }
}



