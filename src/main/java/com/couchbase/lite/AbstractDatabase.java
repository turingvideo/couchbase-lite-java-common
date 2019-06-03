//
// AbstractDatabase.java
//
// Copyright (c) 2017 Couchbase, Inc All rights reserved.
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.json.JSONException;

import com.couchbase.lite.internal.ExecutionService;
import com.couchbase.lite.internal.core.C4BlobStore;
import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Database;
import com.couchbase.lite.internal.core.C4DatabaseChange;
import com.couchbase.lite.internal.core.C4DatabaseObserver;
import com.couchbase.lite.internal.core.C4Document;
import com.couchbase.lite.internal.core.CBLVersion;
import com.couchbase.lite.internal.core.SharedKeys;
import com.couchbase.lite.internal.fleece.FLSliceResult;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.support.Run;
import com.couchbase.lite.internal.utils.JsonUtils;
import com.couchbase.lite.internal.utils.Preconditions;
import com.couchbase.lite.internal.utils.StringUtils;
import com.couchbase.lite.utils.FileUtils;


/**
 * AbstractDatabase is a base class of A Couchbase Lite Database.
 */
abstract class AbstractDatabase {
    //---------------------------------------------
    // Load LiteCore library and its dependencies
    //---------------------------------------------

    /**
     * Gets the logging controller for the Couchbase Lite library to configure the
     * logging settings and add custom logging.
     * <p>
     * This is part of the Public API.
     */
    @SuppressWarnings("ConstantName")
    @NonNull
    public static final com.couchbase.lite.Log log;

    static {
        NativeLibraryLoader.load();
        log = new com.couchbase.lite.Log(); // Don't move this, the native library is needed
        Log.setLogLevel(LogDomain.ALL, LogLevel.WARNING);
    }

    //---------------------------------------------
    // Constants
    //---------------------------------------------
    private static final LogDomain DOMAIN = LogDomain.DATABASE;
    private static final String DB_EXTENSION = "cblite2";

    private static final int MAX_CHANGES = 100;

    // How long to wait after a database opens before expiring docs
    private static final long OPENING_PURGE_DELAY_MS = 3;
    private static final long STANDARD_PURGE_INTERVAL_MS = 1000;

    private static final int DEFAULT_DATABASE_FLAGS
        = C4Constants.DatabaseFlags.CREATE
        | C4Constants.DatabaseFlags.AUTO_COMPACT
        | C4Constants.DatabaseFlags.SHARED_KEYS;

    // ---------------------------------------------
    // API - public static methods
    // ---------------------------------------------

    /**
     * Deletes a database of the given name in the given directory.
     *
     * @param name      the database's name
     * @param directory the path where the database is located.
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the operation.
     */
    public static void delete(@NonNull String name, @NonNull File directory) throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(name, "name");
        Preconditions.checkArgNotNull(directory, "directory");
        if (!exists(name, directory)) {
            throw new CouchbaseLiteException(CBLError.Domain.CBLITE, CBLError.Code.NOT_FOUND);
        }

        final File path = getDatabasePath(directory, name);
        try {
            Log.i(DOMAIN, "delete(): path=%s", path.toString());
            C4Database.deleteAtPath(path.getPath());
        }
        catch (LiteCoreException e) {
            throw CBLStatus.convertException(e);
        }
    }

    /**
     * Checks whether a database of the given name exists in the given directory or not.
     *
     * @param name      the database's name
     * @param directory the path where the database is located.
     * @return true if exists, false otherwise.
     */
    public static boolean exists(@NonNull String name, @NonNull File directory) {
        Preconditions.checkArgNotNull(name, "name");
        Preconditions.checkArgNotNull(directory, "directory");
        return getDatabasePath(directory, name).exists();
    }

    public static void copy(
        @NonNull File path,
        @NonNull String name,
        @NonNull DatabaseConfiguration config)
        throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(path, "path");
        Preconditions.checkArgNotNull(name, "name");
        Preconditions.checkArgNotNull(config, "config");

        String fromPath = path.getPath();
        if (fromPath.charAt(fromPath.length() - 1) != File.separatorChar) { fromPath += File.separator; }
        String toPath = getDatabasePath(new File(config.getDirectory()), name).getPath();
        if (toPath.charAt(toPath.length() - 1) != File.separatorChar) { toPath += File.separator; }

        // Set the temp directory based on Database Configuration:
        config.setTempDir();

        try {
            C4Database.copy(
                fromPath,
                toPath,
                DEFAULT_DATABASE_FLAGS,
                null,
                C4Constants.DocumentVersioning.REVISION_TREES,
                C4Constants.EncryptionAlgorithm.NONE,
                null);
        }
        catch (LiteCoreException e) {
            FileUtils.deleteRecursive(toPath);
            throw CBLStatus.convertException(e);
        }
    }

    /**
     * Set log level for the given log domain.
     *
     * @param domain The log domain
     * @param level  The log level
     * @deprecated As of 2.5 because it is being replaced with the
     * {@link com.couchbase.lite.Log#getConsole() getConsole} method
     * from the {@link #log log} property.  This method has
     * been replaced with a no-op to preserve API compatibility.
     */
    @Deprecated
    public static void setLogLevel(@NonNull LogDomain domain, @NonNull LogLevel level) {
        Preconditions.checkArgNotNull(domain, "domain");
        Preconditions.checkArgNotNull(level, "level");

        Log.setLogLevel(domain, level);
    }

    private static File getDatabasePath(File dir, String name) {
        name = name.replaceAll("/", ":");
        name = String.format(Locale.ENGLISH, "%s.%s", name, DB_EXTENSION);
        return new File(dir, name);
    }

    //---------------------------------------------
    // Member variables
    //---------------------------------------------

    final Object lock = new Object();     // Main database lock object for thread-safety
    final DatabaseConfiguration config;

    private final SharedKeys sharedKeys;
    private final boolean shellMode;

    // Executor for purge and posting Database/Document changes.
    private final ExecutionService.CloseableExecutor postExecutor;
    // Executor for LiveQuery.
    private final ExecutionService.CloseableExecutor queryExecutor;

    private final Set<Replicator> activeReplications;

    private final Set<LiveQuery> activeLiveQueries;

    protected C4Database c4db;

    private ChangeNotifier<DatabaseChange> dbChangeNotifier;

    private C4DatabaseObserver c4DbObserver;
    private Map<String, DocumentChangeNotifier> docChangeNotifiers;

    private DocumentExpirationStrategy purgeStrategy;

    private String name;

    //---------------------------------------------
    // Constructors
    //---------------------------------------------

    /**
     * Construct a  AbstractDatabase with a given name and database config.
     * If the database does not yet exist, it will be created, unless the `readOnly` option is used.
     *
     * @param name   The name of the database. May NOT contain capital letters!
     * @param config The database config, Note: null config parameter is not allowed with Android platform
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the open operation.
     */
    protected AbstractDatabase(@NonNull String name, @NonNull DatabaseConfiguration config)
        throws CouchbaseLiteException {
        // Logging:
        Run.once(
            "DATABASE_INIT_LOGGING",
            new Runnable() {
                @Override
                public void run() {
                    // Log CBL version and build information
                    Log.info(DOMAIN, CBLVersion.getVersionInfo());

                    // Check file logging
                    if (Database.log.getFile().getConfig() == null) {
                        Log.w(
                            DOMAIN,
                            "Database.log.getFile().getConfig() is null, meaning file logging is disabled.  "
                                + "Log files required for product support are not being generated.");
                    }
                }
            });

        if (StringUtils.isEmpty(name)) { throw new IllegalArgumentException("db name may not be empty"); }
        Preconditions.checkArgNotNull(config, "config");

        // Name:
        this.name = name;

        // Copy configuration
        this.config = config.readonlyCopy();

        this.shellMode = false;
        this.postExecutor = CouchbaseLite.getExecutionService().getSerialExecutor();
        this.queryExecutor = CouchbaseLite.getExecutionService().getSerialExecutor();
        this.activeReplications = Collections.synchronizedSet(new HashSet<>());
        this.activeLiveQueries = Collections.synchronizedSet(new HashSet<>());

        // Set the temp directory based on Database Configuration:
        config.setTempDir();

        // Open the database:
        open();

        // Initialize a shared keys:
        this.sharedKeys = new SharedKeys(c4db);
    }

    /**
     * Initialize Database with a give C4Database object in the shell mode. The life of the
     * C4Database object will be managed by the caller. This is currently used for creating a
     * Dictionary as an input of the predict() method of the PredictiveModel.
     */
    protected AbstractDatabase(C4Database c4db) {
        this.c4db = c4db;
        this.config = null;
        this.shellMode = true;
        this.sharedKeys = null;

        this.postExecutor = null;
        this.queryExecutor = null;
        this.activeReplications = null;
        this.activeLiveQueries = null;
    }

    //---------------------------------------------
    // API - public methods
    //---------------------------------------------

    // GET EXISTING DOCUMENT

    /**
     * Return the database name
     *
     * @return the database's name
     */
    @NonNull
    public String getName() { return this.name; }

    /**
     * Return the database's path. If the database is closed or deleted, null value will be returned.
     *
     * @return the database's path.
     */
    public String getPath() {
        synchronized (lock) { return c4db == null ? null : getC4Database().getPath(); }
    }

    /**
     * The number of documents in the database.
     *
     * @return the number of documents in the database, 0 if database is closed.
     */
    public long getCount() {
        synchronized (lock) { return c4db == null ? 0L : getC4Database().getDocumentCount(); }
    }

    /**
     * Returns a READONLY config object which will throw a runtime exception
     * when any setter methods are called.
     *
     * @return the READONLY copied config object
     */
    @NonNull
    public DatabaseConfiguration getConfig() { return config.readonlyCopy(); }

    /**
     * Gets an existing Document object with the given ID. If the document with the given ID doesn't
     * exist in the database, the value returned will be null.
     *
     * @param id the document ID
     * @return the Document object
     */
    public Document getDocument(@NonNull String id) {
        Preconditions.checkArgNotNull(id, "id");

        synchronized (lock) {
            mustBeOpen();
            try { return new Document((Database) this, id, false); }
            catch (CouchbaseLiteException ex) {
                // only 404 - Not Found error throws CouchbaseLiteException
                return null;
            }
        }
    }

    /**
     * Saves a document to the database. When write operations are executed
     * concurrently, the last writer will overwrite all other written values.
     * Calling this method is the same as calling the ave(MutableDocument, ConcurrencyControl)
     * method with LAST_WRITE_WINS concurrency control.
     *
     * @param document The document.
     * @throws CouchbaseLiteException on error
     */
    public void save(@NonNull MutableDocument document) throws CouchbaseLiteException {
        save(document, ConcurrencyControl.LAST_WRITE_WINS);
    }

    /**
     * Saves a document to the database. When used with LAST_WRITE_WINS
     * concurrency control, the last write operation will win if there is a conflict.
     * When used with FAIL_ON_CONFLICT concurrency control, save will fail with false value
     *
     * @param document           The document.
     * @param concurrencyControl The concurrency control.
     * @return true if successful. false if the FAIL_ON_CONFLICT concurrency
     * @throws CouchbaseLiteException on error
     */
    public boolean save(@NonNull MutableDocument document, @NonNull ConcurrencyControl concurrencyControl)
        throws CouchbaseLiteException {
        return saveInternal(document, false, concurrencyControl);
    }

    /**
     * Saves a document to the database. When used with LAST_WRITE_WINS
     * concurrency control, the last write operation will win if there is a conflict.
     * When used with FAIL_ON_CONFLICT concurrency control, save will fail with false value
     *
     * @param document        The document.
     * @param conflictHandler A conflict handler.
     * @return true if successful. false if the FAIL_ON_CONFLICT concurrency
     * @throws CouchbaseLiteException on error
     */
    public boolean save(@NonNull MutableDocument document, @Nullable ConflictHandler conflictHandler)
        throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(document, "document");
        Preconditions.checkArgNotNull(conflictHandler, "conflictHandler");
        // !!! FIXME: use the custom conflict handler
        throw new UnsupportedOperationException("save with conflict handler unsupported");
    }

    /**
     * Deletes a document from the database. When write operations are executed
     * concurrently, the last writer will overwrite all other written values.
     * Calling this function is the same as calling the delete(Document, ConcurrencyControl)
     * function with LAST_WRITE_WINS concurrency control.
     *
     * @param document The document.
     * @throws CouchbaseLiteException on error
     */
    public void delete(@NonNull Document document) throws CouchbaseLiteException {
        delete(document, ConcurrencyControl.LAST_WRITE_WINS);
    }

    /**
     * Deletes a document from the database. When used with lastWriteWins concurrency
     * control, the last write operation will win if there is a conflict.
     * When used with FAIL_ON_CONFLICT concurrency control, delete will fail with
     * 'false' value returned.
     *
     * @param document           The document.
     * @param concurrencyControl The concurrency control.
     * @throws CouchbaseLiteException on error
     */
    public boolean delete(@NonNull Document document, @NonNull ConcurrencyControl concurrencyControl)
        throws CouchbaseLiteException {
        // NOTE: synchronized in save(Document, boolean, ConcurrencyControl, ConflictHandler) method
        return deleteInternal(document, concurrencyControl);
    }

    // Batch operations:

    /**
     * Purges the given document from the database. This is more drastic than delete(Document),
     * it removes all traces of the document. The purge will NOT be replicated to other databases.
     *
     * @param document the document to be purged.
     */
    public void purge(@NonNull Document document) throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(document, "document");

        if (document.isNewDocument()) {
            throw new CouchbaseLiteException(
                "Document doesn't exist in the database.",
                CBLError.Domain.CBLITE,
                CBLError.Code.NOT_FOUND);
        }

        synchronized (lock) {
            prepareDocument(document);

            try { purge(document.getId()); }
            catch (CouchbaseLiteException e) {
                // Ignore not found (already deleted)
                if (e.getCode() != CBLError.Code.NOT_FOUND) { throw e; }
            }

            document.replaceC4Document(null); // Reset c4doc:
        }
    }

    /**
     * Purges the given document id for the document in database. This is more drastic than delete(Document),
     * it removes all traces of the document. The purge will NOT be replicated to other databases.
     *
     * @param id the document ID
     */
    public void purge(@NonNull String id) throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(id, "id");
        synchronized (lock) { purgeSynchronized(id); }
    }

    // Database changes:

    /**
     * Sets an expiration date on a document. After this time, the document
     * will be purged from the database.
     *
     * @param id         The ID of the Document
     * @param expiration Nullable expiration timestamp as a Date, set timestamp to null
     *                   to remove expiration date time from doc.
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the operation.
     */
    public void setDocumentExpiration(@NonNull String id, Date expiration) throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(id, "id");

        synchronized (lock) {
            try {
                getC4Database().setExpiration(id, (expiration == null) ? 0 : expiration.getTime());
                getPurgeStrategy().schedulePurge(0);
            }
            catch (LiteCoreException e) {
                throw CBLStatus.convertException(e);
            }
        }
    }

    /**
     * Returns the expiration time of the document. null will be returned if there is
     * no expiration time set
     *
     * @param id The ID of the Document
     * @return Date a nullable expiration timestamp of the document or null if time not set.
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the operation.
     */
    public Date getDocumentExpiration(@NonNull String id) throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(id, "id");

        synchronized (lock) {
            try {
                if (getC4Database().get(id, true) == null) {
                    throw new CouchbaseLiteException(
                        "Document doesn't exist in the database.",
                        CBLError.Domain.CBLITE,
                        CBLError.Code.NOT_FOUND);
                }
                final long timestamp = getC4Database().getExpiration(id);
                return (timestamp == 0) ? null : new Date(timestamp);
            }
            catch (LiteCoreException e) {
                throw CBLStatus.convertException(e);
            }
        }
    }

    /**
     * Runs a group of database operations in a batch. Use this when performing bulk write operations
     * like multiple inserts/updates; it saves the overhead of multiple database commits, greatly
     * improving performance.
     *
     * @param runnable the action which is implementation of Runnable interface
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the operation.
     */
    public void inBatch(@NonNull Runnable runnable) throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(runnable, "runnable");

        synchronized (lock) {
            mustBeOpen();
            try {
                final C4Database db = getC4Database();
                boolean commit = false;
                db.beginTransaction();
                try {
                    runnable.run();
                    commit = true;
                }
                finally {
                    db.endTransaction(commit);
                }
            }
            catch (RuntimeException e) {
                throw new CouchbaseLiteException(e);
            }
            catch (LiteCoreException e) {
                throw CBLStatus.convertException(e);
            }
        }

        postDatabaseChanged();
    }

    // Compaction:

    /**
     * Compacts the database file by deleting unused attachment files and vacuuming the SQLite database
     */
    public void compact() throws CouchbaseLiteException {
        synchronized (lock) {
            mustBeOpen();
            try { getC4Database().compact(); }
            catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
        }
    }

    // Document changes:

    /**
     * Set the given DatabaseChangeListener to the this database.
     *
     * @param listener callback
     */
    @NonNull
    public ListenerToken addChangeListener(@NonNull DatabaseChangeListener listener) {
        return addChangeListener(null, listener);
    }

    // Others:

    /**
     * Set the given DatabaseChangeListener to the this database with an executor on which the
     * the changes will be posted to the listener.
     *
     * @param listener callback
     */
    @NonNull
    public ListenerToken addChangeListener(Executor executor, @NonNull DatabaseChangeListener listener) {
        Preconditions.checkArgNotNull(listener, "listener");

        synchronized (lock) {
            mustBeOpen();
            return addDatabaseChangeListener(executor, listener);
        }
    }

    /**
     * Remove the given DatabaseChangeListener from the this database.
     *
     * @param token returned by a previous call to addChangeListener or addDocumentListener.
     */
    public void removeChangeListener(@NonNull ListenerToken token) {
        Preconditions.checkArgNotNull(token, "token");

        synchronized (lock) {
            mustBeOpen();
            if (token instanceof ChangeListenerToken && ((ChangeListenerToken) token).getKey() != null) {
                removeDocumentChangeListener((ChangeListenerToken) token);
            }
            else {
                removeDatabaseChangeListener(token);
            }
        }
    }

    // Maintenance operations:

    /**
     * Add the given DocumentChangeListener to the specified document.
     */
    @NonNull
    public ListenerToken addDocumentChangeListener(@NonNull String id, @NonNull DocumentChangeListener listener) {
        return addDocumentChangeListener(id, null, listener);
    }

    /**
     * Add the given DocumentChangeListener to the specified document with an executor on which
     * the changes will be posted to the listener.
     */
    @NonNull
    public ListenerToken addDocumentChangeListener(
        @NonNull String id,
        Executor executor,
        @NonNull DocumentChangeListener listener) {
        Preconditions.checkArgNotNull(id, "id");
        Preconditions.checkArgNotNull(listener, "listener");

        synchronized (lock) {
            mustBeOpen();
            return addDocumentChangeListener(executor, listener, id);
        }
    }

    /**
     * Closes a database.
     *
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the operation.
     */
    public void close() throws CouchbaseLiteException {
        synchronized (lock) {
            if (c4db == null) { return; }

            Log.i(DOMAIN, "Closing %s at path %s", this, getC4Database().getPath());

            if (activeReplications.size() > 0) {
                throw new CouchbaseLiteException(
                    "Cannot close the database.  Please stop all replicators before closing.",
                    CBLError.Domain.CBLITE,
                    CBLError.Code.BUSY);
            }


            if (activeLiveQueries.size() > 0) {
                throw new CouchbaseLiteException(
                    "Cannot close the database.  Please remove all query listeners before closing.",
                    CBLError.Domain.CBLITE,
                    CBLError.Code.BUSY);
            }

            // cancel purge
            if (purgeStrategy != null) { getPurgeStrategy().cancelPurges(); }

            // close db
            closeC4DB();

            // release instances
            freeC4Observers();
            freeC4DB();

            // shutdown executor service
            shutdownExecutorService();
        }
    }

    /**
     * Deletes a database.
     *
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the operation.
     */
    public void delete() throws CouchbaseLiteException {
        synchronized (lock) {
            mustBeOpen();

            Log.i(DOMAIN, "Deleting %s at path %s", this, getC4Database().getPath());

            if (activeReplications.size() > 0) {
                throw new CouchbaseLiteException(
                    "Cannot delete the database.  Please stop all replicators before deleting.",
                    CBLError.Domain.CBLITE,
                    CBLError.Code.BUSY);
            }


            if (activeLiveQueries.size() > 0) {
                throw new CouchbaseLiteException(
                    "Cannot delete the database.  Please remove all query listeners before deleting.",
                    CBLError.Domain.CBLITE,
                    CBLError.Code.BUSY);
            }

            // cancel purge
            if (purgeStrategy != null) { getPurgeStrategy().cancelPurges(); }

            // delete db
            deleteC4DB();

            // release instances
            freeC4Observers();
            freeC4DB();

            // shutdown executor service
            shutdownExecutorService();
        }
    }

    @SuppressWarnings("unchecked")
    @NonNull
    public List<String> getIndexes() throws CouchbaseLiteException {
        synchronized (lock) {
            mustBeOpen();
            try { return (List<String>) c4db.getIndexes().asObject(); }
            catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
        }
    }

    public void createIndex(@NonNull String name, @NonNull Index index) throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(name, "name");
        Preconditions.checkArgNotNull(index, "index");

        synchronized (lock) {
            mustBeOpen();
            try {
                final AbstractIndex abstractIndex = (AbstractIndex) index;
                final String json = JsonUtils.toJson(abstractIndex.items()).toString();
                getC4Database().createIndex(
                    name,
                    json,
                    abstractIndex.type().getValue(),
                    abstractIndex.language(),
                    abstractIndex.ignoreAccents());
            }
            catch (LiteCoreException e) {
                throw CBLStatus.convertException(e);
            }
            catch (JSONException e) {
                throw new CouchbaseLiteException(e);
            }
        }
    }

    public void deleteIndex(@NonNull String name) throws CouchbaseLiteException {
        synchronized (lock) {
            mustBeOpen();
            try { c4db.deleteIndex(name); }
            catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
        }
    }

    //---------------------------------------------
    // Override public method
    //---------------------------------------------

    @NonNull
    @Override
    public String toString() {
        return "Database{@" + Integer.toHexString(hashCode()) + ", name='" + name + "'}";
    }

    //---------------------------------------------
    // Protected level access
    //---------------------------------------------

    protected void mustBeOpen() {
        if (c4db == null) { throw new IllegalStateException("Attempt to perform an operation on a closed database"); }
    }

    @SuppressWarnings("NoFinalizer")
    @Override
    protected void finalize() throws Throwable {
        freeC4Observers();
        freeC4DB();
        super.finalize();
    }

    //---------------------------------------------
    // Package level access
    //---------------------------------------------

    Object getLock() { return lock; }

    boolean equalsWithPath(Database other) {
        if (other == null) { return false; }

        final File path = getFilePath();
        final File otherPath = other.getFilePath();

        if ((path == null) && (otherPath == null)) { return true; }

        return (path != null) && path.equals(otherPath);
    }

    C4BlobStore getBlobStore() throws LiteCoreException {
        synchronized (lock) {
            mustBeOpen();
            return c4db.getBlobStore();
        }
    }

    // Instead of clone()
    Database copy() throws CouchbaseLiteException { return new Database(this.name, this.config); }

    //////// DATABASES:

    boolean isOpen() { return c4db != null; }

    SharedKeys getSharedKeys() { return sharedKeys; }

    File getFilePath() {
        final String path = getPath();
        return path != null ? new File(path) : null;
    }

    /**
     * NOTE: In general the mustBeOpen() method has already been called by the caller.
     * It locks the db to guarantee that c4db is not be closed.
     * Calling mustBeOpen here just avoids an NPE.
     *
     * @return a reference to C4Database instance if db is open.
     */
    C4Database getC4Database() {
        mustBeOpen();

        return c4db;
    }

    //////// DOCUMENTS:

    Set<Replicator> getActiveReplications() { return activeReplications; }

    void addActiveLiveQuery(@NonNull LiveQuery query) { activeLiveQueries.add(query); }

    void removeActiveLiveQuery(@NonNull LiveQuery query) { activeLiveQueries.remove(query); }

    //////// RESOLVING REPLICATED CONFLICTS:

    void resolveConflictInDocument(ConflictResolver resolver, String docID) throws CouchbaseLiteException {
        synchronized (lock) {
            boolean commit = false;
            beginTransaction();
            try {
                // Read local document:
                final Document localDoc = new Document((Database) this, docID, true);

                // Read the conflicting remote revision:
                final Document remoteDoc = new Document((Database) this, docID, true);
                if (!remoteDoc.selectConflictingRevision()) {
                    final String msg = "Unable to select conflicting revision for doc '" + docID + "'. Skipping.";
                    Log.w(DOMAIN, msg);
                    throw new CouchbaseLiteException(msg, CBLError.Domain.CBLITE, CBLError.Code.UNEXPECTED_ERROR);
                }

                // Resolve conflict:
                final Document resolvedDoc = resolveConflict(resolver, docID, localDoc, remoteDoc);

                if (resolvedDoc != null) { saveResolvedDocumentInTransaction(resolvedDoc, localDoc, remoteDoc); }
                else { saveInternal(localDoc, true, ConcurrencyControl.LAST_WRITE_WINS); }

                commit = true;
            }
            catch (LiteCoreException e) {
                throw CBLStatus.convertException(e);
            }
            finally {
                endTransaction(commit);
            }
        }
    }

    //////// Execution:

    void scheduleOnPostNotificationExecutor(@NonNull Runnable task, long delayMs) {
        CouchbaseLite.getExecutionService().postDelayedOnExecutor(delayMs, postExecutor, task);
    }

    void scheduleOnQueryExecutor(@NonNull Runnable task, long delayMs) {
        CouchbaseLite.getExecutionService().postDelayedOnExecutor(delayMs, queryExecutor, task);
    }

    abstract int getEncryptionAlgorithm();

    abstract byte[] getEncryptionKey();

    //---------------------------------------------
    // Private (in class only)
    //---------------------------------------------

    //////// DATABASES:

    private void beginTransaction() throws CouchbaseLiteException {
        try { getC4Database().beginTransaction(); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
    }

    private void endTransaction(boolean commit) throws CouchbaseLiteException {
        try { getC4Database().endTransaction(commit); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
    }

    private void open() throws CouchbaseLiteException {
        if (c4db != null) { return; }

        final File dir = config.getDirectory() != null ? new File(config.getDirectory()) : getDefaultDirectory();
        setupDirectory(dir);

        final File dbFile = getDatabasePath(dir, this.name);
        final int databaseFlags = getDatabaseFlags();

        Log.i(DOMAIN, "Opening %s at path %s", this, dbFile.getPath());

        try {
            c4db = new C4Database(
                dbFile.getPath(),
                databaseFlags,
                null,
                C4Constants.DocumentVersioning.REVISION_TREES,
                getEncryptionAlgorithm(),
                getEncryptionKey());
        }
        catch (LiteCoreException e) {
            if (e.code == CBLError.Code.NOT_A_DATABSE_FILE) {
                throw new CouchbaseLiteException(
                    "The provided encryption key was incorrect.",
                    e,
                    CBLError.Domain.CBLITE,
                    e.code);
            }

            if (e.code == CBLError.Code.CANT_OPEN_FILE) {
                throw new CouchbaseLiteException(
                    "Unable to create database directory.",
                    e,
                    CBLError.Domain.CBLITE,
                    e.code);
            }

            throw CBLStatus.convertException(e);
        }

        c4DbObserver = null;
        dbChangeNotifier = null;
        docChangeNotifiers = new HashMap<>();

        getPurgeStrategy().schedulePurge(OPENING_PURGE_DELAY_MS);
    }

    private int getDatabaseFlags() { return DEFAULT_DATABASE_FLAGS; }

    private File getDefaultDirectory() {
        throw new UnsupportedOperationException("getDefaultDirectory() is not supported.");
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    private void setupDirectory(File dir) throws CouchbaseLiteException {
        if (!dir.exists()) { dir.mkdirs(); }
        if (!dir.isDirectory()) { throw new CouchbaseLiteException("Unable to create directory for: " + dir); }
    }

    // --- C4Database
    private void closeC4DB() throws CouchbaseLiteException {
        try { getC4Database().close(); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
    }

    //////// DOCUMENTS:

    private void deleteC4DB() throws CouchbaseLiteException {
        try { getC4Database().delete(); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
    }

    private void freeC4DB() {
        if ((c4db != null) && !shellMode) {
            getC4Database().free();
            c4db = null;
        }
    }

    // --- Database changes:

    // NOTE: calling method must be synchronized.
    private ListenerToken addDatabaseChangeListener(Executor executor, DatabaseChangeListener listener) {
        if (dbChangeNotifier == null) {
            dbChangeNotifier = new ChangeNotifier<>();
            registerC4DBObserver();
        }
        return dbChangeNotifier.addChangeListener(executor, listener);
    }

    // --- Notification: - C4DatabaseObserver/C4DocumentObserver

    // NOTE: calling method must be synchronized.
    private void removeDatabaseChangeListener(ListenerToken token) {
        if (dbChangeNotifier.removeChangeListener(token) == 0) {
            freeC4DBObserver();
            dbChangeNotifier = null;
        }
    }

    // --- Document changes:

    // NOTE: calling method must be synchronized.
    private ListenerToken addDocumentChangeListener(Executor executor, DocumentChangeListener listener, String docID) {
        DocumentChangeNotifier docNotifier = docChangeNotifiers.get(docID);
        if (docNotifier == null) {
            docNotifier = new DocumentChangeNotifier((Database) this, docID);
            docChangeNotifiers.put(docID, docNotifier);
        }
        final ChangeListenerToken token = docNotifier.addChangeListener(executor, listener);
        token.setKey(docID);
        return token;
    }

    // NOTE: calling method must be synchronized.
    private void removeDocumentChangeListener(ChangeListenerToken token) {
        final String docID = (String) token.getKey();
        if (docChangeNotifiers.containsKey(docID)) {
            final DocumentChangeNotifier notifier = docChangeNotifiers.get(docID);
            if (notifier != null && notifier.removeChangeListener(token) == 0) {
                notifier.stop();
                docChangeNotifiers.remove(docID);
            }
        }
    }

    private void registerC4DBObserver() {
        c4DbObserver = c4db.createDatabaseObserver(
            (observer, context) -> scheduleOnPostNotificationExecutor(this::postDatabaseChanged, 0),
            this);
    }

    private void freeC4DBObserver() {
        if (c4DbObserver != null) {
            c4DbObserver.free();
            c4DbObserver = null;
        }
    }

    private void freeC4Observers() {
        freeC4DBObserver();

        if (docChangeNotifiers != null) {
            for (DocumentChangeNotifier notifier : docChangeNotifiers.values()) { notifier.stop(); }
            docChangeNotifiers.clear();
        }
    }

    private void postDatabaseChanged() {
        synchronized (lock) {
            if ((c4DbObserver == null) || (c4db == null)) { return; }

            boolean external = false;
            int nChanges;
            List<String> docIDs = new ArrayList<>();
            do {
                // Read changes in batches of kMaxChanges:
                final C4DatabaseChange[] c4DbChanges = c4DbObserver.getChanges(MAX_CHANGES);
                nChanges = (c4DbChanges == null) ? 0 : c4DbChanges.length;
                final boolean newExternal = (nChanges > 0) && c4DbChanges[0].isExternal();
                if (((nChanges <= 0) || (external != newExternal) || (docIDs.size() > 1000)) && (docIDs.size() > 0)) {
                    dbChangeNotifier.postChange(new DatabaseChange((Database) this, docIDs));
                    docIDs = new ArrayList<>();
                }

                external = newExternal;
                for (int i = 0; i < nChanges; i++) { docIDs.add(c4DbChanges[i].getDocID()); }
            }
            while (nChanges > 0);
        }
    }

    private void prepareDocument(Document document) throws CouchbaseLiteException {
        mustBeOpen();

        if (document.getDatabase() == null) { document.setDatabase((Database) this); }
        else if (document.getDatabase() != this) {
            throw new CouchbaseLiteException(
                "Cannot operate on a document from another database.",
                CBLError.Domain.CBLITE,
                CBLError.Code.INVALID_PARAMETER);
        }
    }

    private boolean deleteInternal(
        @NonNull Document document,
        @NonNull ConcurrencyControl concurrencyControl)
        throws CouchbaseLiteException {
        if (!document.exists()) {
            throw new CouchbaseLiteException(
                "Cannot delete a document that has not yet been saved.",
                CBLError.Domain.CBLITE,
                CBLError.Code.NOT_FOUND);
        }

        return saveInternal(document, true, concurrencyControl);
    }


    // The main save method.
    private boolean saveInternal(
        @NonNull Document document,
        boolean deleting,
        @NonNull ConcurrencyControl concurrencyControl)
        throws CouchbaseLiteException {
        Preconditions.checkArgNotNull(document, "document");
        Preconditions.checkArgNotNull(concurrencyControl, "concurrencyControl");

        synchronized (lock) {
            mustBeOpen();
            prepareDocument(document);

            CouchbaseLiteException fail = null;
            C4Document curDoc = null;
            C4Document newDoc = null;

            boolean commit = false;
            beginTransaction();
            try {
                try {
                    newDoc = saveInTransaction(document, null, deleting);
                    commit = true;
                }
                catch (CouchbaseLiteException e) {
                    if (!(e.getDomain().equals(CBLError.Domain.CBLITE) && (e.getCode() == CBLError.Code.CONFLICT))) {
                        throw e;
                    }
                }

                // Handle conflict:
                if (newDoc == null) {
                    // document is conflicted and return false because of OPTIMISTIC
                    if (concurrencyControl.equals(ConcurrencyControl.FAIL_ON_CONFLICT)) { return false; }

                    try { curDoc = getC4Database().get(document.getId(), true); }
                    catch (LiteCoreException e) {
                        if (!deleting
                            || e.domain != C4Constants.ErrorDomain.LITE_CORE
                            || e.code != C4Constants.LiteCoreError.NOT_FOUND) {
                            throw CBLStatus.convertException(e);
                        }

                        return true;
                    }

                    if (deleting && curDoc.deleted()) {
                        document.replaceC4Document(curDoc);
                        curDoc = null; // NOTE: prevent to call curDoc.free() in finally block ???
                        return true;
                    }

                    // Save changes on the current branch:
                    // NOTE: curDoc null check is done in prev try-catch block
                    newDoc = saveInTransaction(document, curDoc, deleting);
                }

                document.replaceC4Document(newDoc);
                commit = true;
            }
            finally {
                if (curDoc != null) {
                    curDoc.retain();
                    curDoc.release(); // curDoc is not retained ??? WTF?
                }

                // true: commit the transaction, false: abort the transaction
                try { endTransaction(commit); }
                catch (CouchbaseLiteException e) {
                    // newDoc is already retained
                    if (newDoc != null) { newDoc.release(); }
                    fail = e;
                }
            }

            if (fail != null) { throw fail; }

            return true;
        }
    }

    // Lower-level save method. On conflict, returns YES but sets *outDoc to NULL.
    @SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE")
    @NonNull
    private C4Document saveInTransaction(@NonNull Document document, @Nullable C4Document base, boolean deleting)
        throws CouchbaseLiteException {
        FLSliceResult body = null;
        try {
            int revFlags = 0;
            if (deleting) { revFlags = C4Constants.RevisionFlags.DELETED; }
            else if (!document.isEmpty()) {
                // Encode properties to Fleece data:
                body = document.encode();
                if (C4Document.dictContainsBlobs(body, sharedKeys.getFLSharedKeys())) {
                    revFlags |= C4Constants.RevisionFlags.HAS_ATTACHMENTS;
                }
            }

            // Save to database:
            final C4Document c4Doc = base != null ? base : document.getC4doc();

            return (c4Doc != null)
                ? c4Doc.update(body, revFlags)
                : getC4Database().create(document.getId(), body, revFlags);
        }
        catch (LiteCoreException e) {
            throw CBLStatus.convertException(e);
        }
        finally {
            if (body != null) { body.free(); }
        }
    }

    // !!! FIXME: use the custom conflict handler
    private Document resolveConflict(ConflictResolver resolver, String docID, Document localDoc, Document remoteDoc)
        throws CouchbaseLiteException {
        Log.v(
            DOMAIN,
            "Resolving doc '%s' (local=%s and remote=%s)",
            docID,
            localDoc.getRevID(),
            remoteDoc.getRevID());

        final ConflictResolver rez = (resolver != null) ? resolver : ConflictResolver.DEFAULT;

        final Document doc;
        try { doc = rez.resolve(new Conflict(localDoc, remoteDoc)); }
        catch (Exception err) {
            throw new CouchbaseLiteException(
                "Conflict resolver failed. Skipping.",
                err,
                CBLError.Domain.CBLITE,
                CBLError.Code.UNEXPECTED_ERROR);
        }
        if (doc == null) { return null; }

        if (!docID.equals(doc.getId())) {
            throw new CouchbaseLiteException(
                "Resolved document's id does not match that of the documents being resolved. Skipping.",
                CBLError.Domain.CBLITE,
                CBLError.Code.UNEXPECTED_ERROR);
        }

        if (!doc.getDatabase().equals(localDoc.getDatabase())) {
            throw new CouchbaseLiteException(
                "Resolved document belongs to a database different than the documents being resolved. Skipping.",
                CBLError.Domain.CBLITE,
                CBLError.Code.UNEXPECTED_ERROR);
        }

        return doc;
    }

    // Call holding lock and in a transaction
    private boolean saveResolvedDocumentInTransaction(Document resolvedDoc, Document localDoc, Document remoteDoc)
        throws LiteCoreException {

        if (remoteDoc != localDoc) { resolvedDoc.setDatabase((Database) this); }

        // The remote branch has to win, so that the doc revision history matches the server's.
        final String winningRevID = remoteDoc.getRevID();
        final String losingRevID = localDoc.getRevID();

        byte[] mergedBody = null;
        int mergedFlags = 0x00;
        if (resolvedDoc != remoteDoc) {
            // Unless the remote revision is being used as-is, we need a new revision:
            mergedBody = resolvedDoc.encode().getBuf();
            if (mergedBody == null) { return false; }
            if (resolvedDoc.isDeleted()) { mergedFlags |= C4Constants.RevisionFlags.DELETED; }
        }

        // Tell LiteCore to do the resolution:
        final C4Document rawDoc = localDoc.getC4doc();
        rawDoc.resolveConflict(winningRevID, losingRevID, mergedBody, mergedFlags);
        rawDoc.save(0);

        Log.i(DOMAIN, "Conflict resolved as doc '%s' rev %s", rawDoc.getDocID(), rawDoc.getRevID());

        return true;
    }

    private void purgeSynchronized(@NonNull String id) throws CouchbaseLiteException {
        boolean commit = false;
        beginTransaction();
        try {
            getC4Database().purgeDoc(id);
            commit = true;
        }
        catch (LiteCoreException e) {
            throw CBLStatus.convertException(e);
        }
        finally {
            endTransaction(commit);
        }
    }

    private void shutdownExecutorService() {
        postExecutor.stop(60, TimeUnit.SECONDS);
        queryExecutor.stop(60, TimeUnit.SECONDS);
    }

    private DocumentExpirationStrategy getPurgeStrategy() {
        if (purgeStrategy == null) {
            purgeStrategy = new DocumentExpirationStrategy(this, STANDARD_PURGE_INTERVAL_MS, postExecutor);
        }
        return purgeStrategy;
    }
}


