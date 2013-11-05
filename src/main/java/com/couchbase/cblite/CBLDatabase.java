/**
 * Original iOS version by  Jens Alfke
 * Ported to Android by Marty Schoch
 *
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.couchbase.cblite;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.support.v4.util.LruCache;
import android.text.TextUtils;
import android.util.Log;

import com.couchbase.cblite.CBLDatabase.TDContentOptions;
import com.couchbase.cblite.internal.CBLAttachmentInternal;
import com.couchbase.cblite.internal.CBLBody;
import com.couchbase.cblite.internal.CBLRevisionInternal;
import com.couchbase.cblite.replicator.CBLPuller;
import com.couchbase.cblite.replicator.CBLPusher;
import com.couchbase.cblite.replicator.CBLReplicator;
import com.couchbase.cblite.support.Base64;
import com.couchbase.cblite.support.FileDirUtils;
import com.couchbase.cblite.support.HttpClientFactory;
import com.couchbase.touchdb.TDCollateJSON;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A CouchbaseLite database.
 */
public class CBLDatabase {

    private static final int MAX_DOC_CACHE_SIZE = 50;
    private static CBLFilterCompiler filterCompiler;

    private String path;
    private String name;
    private SQLiteDatabase sqliteDb;
    private boolean open = false;
    private int transactionLevel = 0;
    public static final String TAG = "CBLDatabase";

    private Map<String, CBLView> views;
    private Map<String, CBLFilterBlock> filters;
    private Map<String, CBLValidationBlock> validations;

    private Map<String, CBLBlobStoreWriter> pendingAttachmentsByDigest;
    private List<CBLReplicator> activeReplicators;
    private CBLBlobStore attachments;
    private CBLManager manager;
    private List<CBLDatabaseChangedFunction> changeListeners;
    private LruCache<String, CBLDocument> docCache;

    // Length that constitutes a 'big' attachment
    public static int kBigAttachmentLength = (16*1024);

    /**
     * Options for what metadata to include in document bodies
     */
    public enum TDContentOptions {
        TDIncludeAttachments, TDIncludeConflicts, TDIncludeRevs, TDIncludeRevsInfo, TDIncludeLocalSeq, TDNoBody, TDBigAttachmentsFollow
    }

    private static final Set<String> KNOWN_SPECIAL_KEYS;

    static {
        KNOWN_SPECIAL_KEYS = new HashSet<String>();
        KNOWN_SPECIAL_KEYS.add("_id");
        KNOWN_SPECIAL_KEYS.add("_rev");
        KNOWN_SPECIAL_KEYS.add("_attachments");
        KNOWN_SPECIAL_KEYS.add("_deleted");
        KNOWN_SPECIAL_KEYS.add("_revisions");
        KNOWN_SPECIAL_KEYS.add("_revs_info");
        KNOWN_SPECIAL_KEYS.add("_conflicts");
        KNOWN_SPECIAL_KEYS.add("_deleted_conflicts");
    }

    public static final String SCHEMA = "" +
            "CREATE TABLE docs ( " +
            "        doc_id INTEGER PRIMARY KEY, " +
            "        docid TEXT UNIQUE NOT NULL); " +
            "    CREATE INDEX docs_docid ON docs(docid); " +
            "    CREATE TABLE revs ( " +
            "        sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "        doc_id INTEGER NOT NULL REFERENCES docs(doc_id) ON DELETE CASCADE, " +
            "        revid TEXT NOT NULL, " +
            "        parent INTEGER REFERENCES revs(sequence) ON DELETE SET NULL, " +
            "        current BOOLEAN, " +
            "        deleted BOOLEAN DEFAULT 0, " +
            "        json BLOB); " +
            "    CREATE INDEX revs_by_id ON revs(revid, doc_id); " +
            "    CREATE INDEX revs_current ON revs(doc_id, current); " +
            "    CREATE INDEX revs_parent ON revs(parent); " +
            "    CREATE TABLE localdocs ( " +
            "        docid TEXT UNIQUE NOT NULL, " +
            "        revid TEXT NOT NULL, " +
            "        json BLOB); " +
            "    CREATE INDEX localdocs_by_docid ON localdocs(docid); " +
            "    CREATE TABLE views ( " +
            "        view_id INTEGER PRIMARY KEY, " +
            "        name TEXT UNIQUE NOT NULL," +
            "        version TEXT, " +
            "        lastsequence INTEGER DEFAULT 0); " +
            "    CREATE INDEX views_by_name ON views(name); " +
            "    CREATE TABLE maps ( " +
            "        view_id INTEGER NOT NULL REFERENCES views(view_id) ON DELETE CASCADE, " +
            "        sequence INTEGER NOT NULL REFERENCES revs(sequence) ON DELETE CASCADE, " +
            "        key TEXT NOT NULL COLLATE JSON, " +
            "        value TEXT); " +
            "    CREATE INDEX maps_keys on maps(view_id, key COLLATE JSON); " +
            "    CREATE TABLE attachments ( " +
            "        sequence INTEGER NOT NULL REFERENCES revs(sequence) ON DELETE CASCADE, " +
            "        filename TEXT NOT NULL, " +
            "        key BLOB NOT NULL, " +
            "        type TEXT, " +
            "        length INTEGER NOT NULL, " +
            "        revpos INTEGER DEFAULT 0); " +
            "    CREATE INDEX attachments_by_sequence on attachments(sequence, filename); " +
            "    CREATE TABLE replicators ( " +
            "        remote TEXT NOT NULL, " +
            "        push BOOLEAN, " +
            "        last_sequence TEXT, " +
            "        UNIQUE (remote, push)); " +
            "    PRAGMA user_version = 3";             // at the end, update user_version


    /**
     * The database manager that owns this database.
     */
    public CBLManager getManager() {
        return manager;
    }

    public URL getInternalURL() {
        // TODO: implement this
        throw new RuntimeException("Not implemented");
    }


    /**
     * Constructor
     *
     * @param path
     * @param manager
     */
    public CBLDatabase(String path, CBLManager manager) {
        assert(path.startsWith("/")); //path must be absolute
        this.path = path;
        this.name = FileDirUtils.getDatabaseNameFromPath(path);
        this.manager = manager;
        this.changeListeners = new ArrayList<CBLDatabaseChangedFunction>();
        this.docCache = new LruCache<String, CBLDocument>(MAX_DOC_CACHE_SIZE);
    }

    /**
     * Instantiates a CBLDocument object with the given ID.
     * Doesn't touch the on-disk sqliteDb; a document with that ID doesn't
     * even need to exist yet. CBLDocuments are cached, so there will
     * never be more than one instance (in this sqliteDb) at a time with
     * the same documentID.
     *
     * NOTE: the caching described above is not implemented yet
     *
     * @param documentId
     * @return
     */
    public CBLDocument getDocument(String documentId) {

        if (documentId == null || documentId.length() == 0) {
            return null;
        }
        CBLDocument doc = docCache.get(documentId);
        if (doc == null) {
            doc = new CBLDocument(this, documentId);
            if (doc == null) {
                return null;
            }
            docCache.put(documentId, doc);
        }
        return doc;
    }

    public CBLDocument getExistingDocument(String documentId) {
        if (documentId == null || documentId.length() == 0) {
            return null;
        }
        CBLRevisionInternal revisionInternal = getDocumentWithIDAndRev(documentId, null, EnumSet.noneOf(CBLDatabase.TDContentOptions.class));
        if (revisionInternal == null) {
            return null;
        }
        return getDocument(documentId);
    }

    /**
     * Creates a new CBLDocument object with no properties and a new (random) UUID.
     * The document will be saved to the database when you call -putProperties: on it.
     */
    public CBLDocument createDocument() {
        return getDocument(CBLMisc.TDCreateUUID());
    }

    /**
     * Returns the already-instantiated cached CBLDocument with the given ID, or nil if none is yet cached.
     */
    public CBLDocument getCachedDocument(String documentID) {
        return docCache.get(documentID);
    }

    /**
     * Empties the cache of recently used CBLDocument objects.
     * API calls will now instantiate and return new instances.
     */
    public void clearDocumentCache() {
        docCache.evictAll();
    }

    void removeDocumentFromCache(CBLDocument document) {
        docCache.remove(document.getId());
    }

    public String toString() {
        return this.getClass().getName() + "[" + path + "]";
    }

    public boolean exists() {
        return new File(path).exists();
    }



    public String getAttachmentStorePath() {
        String attachmentStorePath = path;
        int lastDotPosition = attachmentStorePath.lastIndexOf('.');
        if( lastDotPosition > 0 ) {
            attachmentStorePath = attachmentStorePath.substring(0, lastDotPosition);
        }
        attachmentStorePath = attachmentStorePath + File.separator + "attachments";
        return attachmentStorePath;
    }

    public static CBLDatabase createEmptyDBAtPath(String path, CBLManager manager) {
        if(!FileDirUtils.removeItemIfExists(path)) {
            return null;
        }
        CBLDatabase result = new CBLDatabase(path, manager);
        File af = new File(result.getAttachmentStorePath());
        //recursively delete attachments path
        if(!FileDirUtils.deleteRecursive(af)) {
            return null;
        }
        if(!result.open()) {
            return null;
        }
        return result;
    }

    public boolean initialize(String statements) {
        try {
            for (String statement : statements.split(";")) {
                sqliteDb.execSQL(statement);
            }
        } catch (SQLException e) {
            close();
            return false;
        }
        return true;
    }

    public boolean open() {
        if(open) {
            return true;
        }

        try {
            sqliteDb = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.CREATE_IF_NECESSARY);
            TDCollateJSON.registerCustomCollators(sqliteDb);
        }
        catch(SQLiteException e) {
            Log.e(CBLDatabase.TAG, "Error opening", e);
            return false;
        }

        // Stuff we need to initialize every time the sqliteDb opens:
        if(!initialize("PRAGMA foreign_keys = ON;")) {
            Log.e(CBLDatabase.TAG, "Error turning on foreign keys");
            return false;
        }

        // Check the user_version number we last stored in the sqliteDb:
        int dbVersion = sqliteDb.getVersion();

        // Incompatible version changes increment the hundreds' place:
        if(dbVersion >= 100) {
            Log.w(CBLDatabase.TAG, "CBLDatabase: Database version (" + dbVersion + ") is newer than I know how to work with");
            sqliteDb.close();
            return false;
        }

        if(dbVersion < 1) {
            // First-time initialization:
            // (Note: Declaring revs.sequence as AUTOINCREMENT means the values will always be
            // monotonically increasing, never reused. See <http://www.sqlite.org/autoinc.html>)
            if(!initialize(SCHEMA)) {
                sqliteDb.close();
                return false;
            }
            dbVersion = 3;
        }

        if (dbVersion < 2) {
            // Version 2: added attachments.revpos
            String upgradeSql = "ALTER TABLE attachments ADD COLUMN revpos INTEGER DEFAULT 0; " +
                                "PRAGMA user_version = 2";
            if(!initialize(upgradeSql)) {
                sqliteDb.close();
                return false;
            }
            dbVersion = 2;
        }

        if (dbVersion < 3) {
            String upgradeSql = "CREATE TABLE localdocs ( " +
                    "docid TEXT UNIQUE NOT NULL, " +
                    "revid TEXT NOT NULL, " +
                    "json BLOB); " +
                    "CREATE INDEX localdocs_by_docid ON localdocs(docid); " +
                    "PRAGMA user_version = 3";
            if(!initialize(upgradeSql)) {
                sqliteDb.close();
                return false;
            }
            dbVersion = 3;
        }

        if (dbVersion < 4) {
            String upgradeSql = "CREATE TABLE info ( " +
                    "key TEXT PRIMARY KEY, " +
                    "value TEXT); " +
                    "INSERT INTO INFO (key, value) VALUES ('privateUUID', '" + CBLMisc.TDCreateUUID() + "'); " +
                    "INSERT INTO INFO (key, value) VALUES ('publicUUID',  '" + CBLMisc.TDCreateUUID() + "'); " +
                    "PRAGMA user_version = 4";
            if(!initialize(upgradeSql)) {
                sqliteDb.close();
                return false;
            }
        }

        try {
            attachments = new CBLBlobStore(getAttachmentStorePath());
        } catch (IllegalArgumentException e) {
            Log.e(CBLDatabase.TAG, "Could not initialize attachment store", e);
            sqliteDb.close();
            return false;
        }

        open = true;
        return true;
    }

    public boolean close() {
        if(!open) {
            return false;
        }

        if(views != null) {
            for (CBLView view : views.values()) {
                view.databaseClosing();
            }
        }
        views = null;

        if(activeReplicators != null) {
            for(CBLReplicator replicator : activeReplicators) {
                replicator.databaseClosing();
            }
            activeReplicators = null;
        }

        if(sqliteDb != null && sqliteDb.isOpen()) {
            sqliteDb.close();
        }
        open = false;
        transactionLevel = 0;
        return true;
    }

    /**
     * Deletes the database.
     */
    public boolean delete() {
        if(open) {
            if(!close()) {
                return false;
            }
        }
        else if(!exists()) {
            return true;
        }
        File file = new File(path);
        File attachmentsFile = new File(getAttachmentStorePath());

        boolean deleteStatus = file.delete();
        //recursively delete attachments path
        boolean deleteAttachmentStatus = FileDirUtils.deleteRecursive(attachmentsFile);
        return deleteStatus && deleteAttachmentStatus;
    }

    public String getPath() {
        return path;
    }

    /**
     * The database's name.
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // Leave this package protected, so it can only be used
    // CBLView uses this accessor
    public SQLiteDatabase getSqliteDb() {
        return sqliteDb;
    }

    public CBLBlobStore getAttachments() {
        return attachments;
    }

    public CBLBlobStoreWriter getAttachmentWriter() {
        return new CBLBlobStoreWriter(getAttachments());
    }

    public long totalDataSize() {
        File f = new File(path);
        long size = f.length() + attachments.totalDataSize();
        return size;
    }



    /**
     * Begins a sqliteDb transaction. Transactions can nest.
     * Every beginTransaction() must be balanced by a later endTransaction()
     */
    public boolean beginTransaction() {
        try {
            sqliteDb.beginTransaction();
            ++transactionLevel;
            //Log.v(TAG, "Begin transaction (level " + Integer.toString(transactionLevel) + ")...");
        } catch (SQLException e) {
            return false;
        }
        return true;
    }

    /**
     * Commits or aborts (rolls back) a transaction.
     *
     * @param commit If true, commits; if false, aborts and rolls back, undoing all changes made since the matching -beginTransaction call, *including* any committed nested transactions.
     */
    public boolean endTransaction(boolean commit) {
        assert(transactionLevel > 0);

        if(commit) {
            //Log.v(TAG, "Committing transaction (level " + Integer.toString(transactionLevel) + ")...");
            sqliteDb.setTransactionSuccessful();
            sqliteDb.endTransaction();
        }
        else {
            Log.v(TAG, "CANCEL transaction (level " + Integer.toString(transactionLevel) + ")...");
            try {
                sqliteDb.endTransaction();
            } catch (SQLException e) {
                return false;
            }
        }

        --transactionLevel;
        return true;
    }

    /**
     * Runs the block within a transaction. If the block returns NO, the transaction is rolled back.
     * Use this when performing bulk write operations like multiple inserts/updates;
     * it saves the overhead of multiple SQLite commits, greatly improving performance.
     *
     * Does not commit the transaction if the code throws an Exception.
     *
     * TODO: the iOS version has a retry loop, so there should be one here too
     *
     * @param databaseFunction
     */
    public void runInTransaction(CBLDatabaseFunction databaseFunction) {

        boolean shouldCommit = true;

        beginTransaction();
        try {
            shouldCommit = databaseFunction.performFunction();
        } catch (Exception e) {
            shouldCommit = false;
            Log.e(CBLDatabase.TAG, e.toString(), e);
            throw new RuntimeException(e);
        } finally {
            endTransaction(shouldCommit);
        }

    }

    /**
     * Compacts the database file by purging non-current revisions, deleting unused attachment files,
     * and running a SQLite "VACUUM" command.
     */
    public CBLStatus compact() {
        // Can't delete any rows because that would lose revision tree history.
        // But we can remove the JSON of non-current revisions, which is most of the space.
        try {
            Log.v(CBLDatabase.TAG, "Deleting JSON of old revisions...");
            ContentValues args = new ContentValues();
            args.put("json", (String)null);
            sqliteDb.update("revs", args, "current=0", null);
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error compacting", e);
            return new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR);
        }

        Log.v(CBLDatabase.TAG, "Deleting old attachments...");
        CBLStatus result = garbageCollectAttachments();

        Log.v(CBLDatabase.TAG, "Vacuuming SQLite sqliteDb...");
        try {
            sqliteDb.execSQL("VACUUM");
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error vacuuming sqliteDb", e);
            return new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR);
        }

        return result;
    }

    public String privateUUID() {
        String result = null;
        Cursor cursor = null;
        try {
            cursor = sqliteDb.rawQuery("SELECT value FROM info WHERE key='privateUUID'", null);
            if(cursor.moveToFirst()) {
                result = cursor.getString(0);
            }
        } catch(SQLException e) {
            Log.e(TAG, "Error querying privateUUID", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public String publicUUID() {
        String result = null;
        Cursor cursor = null;
        try {
            cursor = sqliteDb.rawQuery("SELECT value FROM info WHERE key='publicUUID'", null);
            if(cursor.moveToFirst()) {
                result = cursor.getString(0);
            }
        } catch(SQLException e) {
            Log.e(TAG, "Error querying privateUUID", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    /** GETTING DOCUMENTS: **/

    /**
     * The number of documents in the database.
     */
    public int getDocumentCount() {
        String sql = "SELECT COUNT(DISTINCT doc_id) FROM revs WHERE current=1 AND deleted=0";
        Cursor cursor = null;
        int result = 0;
        try {
            cursor = sqliteDb.rawQuery(sql, null);
            if(cursor.moveToFirst()) {
                result = cursor.getInt(0);
            }
        } catch(SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting document count", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    /**
     * The latest sequence number used.  Every new revision is assigned a new sequence number,
     * so this property increases monotonically as changes are made to the database. It can be
     * used to check whether the database has changed between two points in time.
     */
    public long getLastSequenceNumber() {
        String sql = "SELECT MAX(sequence) FROM revs";
        Cursor cursor = null;
        long result = 0;
        try {
            cursor = sqliteDb.rawQuery(sql, null);
            if(cursor.moveToFirst()) {
                result = cursor.getLong(0);
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting last sequence", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    /** Splices the contents of an NSDictionary into JSON data (that already represents a dict), without parsing the JSON. */
    public  byte[] appendDictToJSON(byte[] json, Map<String,Object> dict) {
        if(dict.size() == 0) {
            return json;
        }

        byte[] extraJSON = null;
        try {
            extraJSON = CBLManager.getObjectMapper().writeValueAsBytes(dict);
        } catch (Exception e) {
            Log.e(CBLDatabase.TAG, "Error convert extra JSON to bytes", e);
            return null;
        }

        int jsonLength = json.length;
        int extraLength = extraJSON.length;
        if(jsonLength == 2) { // Original JSON was empty
            return extraJSON;
        }
        byte[] newJson = new byte[jsonLength + extraLength - 1];
        System.arraycopy(json, 0, newJson, 0, jsonLength - 1);  // Copy json w/o trailing '}'
        newJson[jsonLength - 1] = ',';  // Add a ','
        System.arraycopy(extraJSON, 1, newJson, jsonLength, extraLength - 1);
        return newJson;
    }

    /** Inserts the _id, _rev and _attachments properties into the JSON data and stores it in rev.
    Rev must already have its revID and sequence properties set. */
    public Map<String,Object> extraPropertiesForRevision(CBLRevisionInternal rev, EnumSet<TDContentOptions> contentOptions) {

        String docId = rev.getDocId();
        String revId = rev.getRevId();
        long sequenceNumber = rev.getSequence();
        assert(revId != null);
        assert(sequenceNumber > 0);

        // Get attachment metadata, and optionally the contents:
        Map<String, Object> attachmentsDict = getAttachmentsDictForSequenceWithContent(sequenceNumber, contentOptions);

        // Get more optional stuff to put in the properties:
        //OPT: This probably ends up making redundant SQL queries if multiple options are enabled.
        Long localSeq = null;
        if(contentOptions.contains(TDContentOptions.TDIncludeLocalSeq)) {
            localSeq = sequenceNumber;
        }

        Map<String,Object> revHistory = null;
        if(contentOptions.contains(TDContentOptions.TDIncludeRevs)) {
            revHistory = getRevisionHistoryDict(rev);
        }

        List<Object> revsInfo = null;
        if(contentOptions.contains(TDContentOptions.TDIncludeRevsInfo)) {
            revsInfo = new ArrayList<Object>();
            List<CBLRevisionInternal> revHistoryFull = getRevisionHistory(rev);
            for (CBLRevisionInternal historicalRev : revHistoryFull) {
                Map<String,Object> revHistoryItem = new HashMap<String,Object>();
                String status = "available";
                if(historicalRev.isDeleted()) {
                    status = "deleted";
                }
                // TODO: Detect missing revisions, set status="missing"
                revHistoryItem.put("rev", historicalRev.getRevId());
                revHistoryItem.put("status", status);
                revsInfo.add(revHistoryItem);
            }
        }

        List<String> conflicts = null;
        if(contentOptions.contains(TDContentOptions.TDIncludeConflicts)) {
            CBLRevisionList revs = getAllRevisionsOfDocumentID(docId, true);
            if(revs.size() > 1) {
                conflicts = new ArrayList<String>();
                for (CBLRevisionInternal historicalRev : revs) {
                    if(!historicalRev.equals(rev)) {
                        conflicts.add(historicalRev.getRevId());
                    }
                }
            }
        }

        Map<String,Object> result = new HashMap<String,Object>();
        result.put("_id", docId);
        result.put("_rev", revId);
        if(rev.isDeleted()) {
            result.put("_deleted", true);
        }
        if(attachmentsDict != null) {
            result.put("_attachments", attachmentsDict);
        }
        if(localSeq != null) {
            result.put("_local_seq", localSeq);
        }
        if(revHistory != null) {
            result.put("_revisions", revHistory);
        }
        if(revsInfo != null) {
            result.put("_revs_info", revsInfo);
        }
        if(conflicts != null) {
            result.put("_conflicts", conflicts);
        }

        return result;
    }

    /** Inserts the _id, _rev and _attachments properties into the JSON data and stores it in rev.
    Rev must already have its revID and sequence properties set. */
    public void expandStoredJSONIntoRevisionWithAttachments(byte[] json, CBLRevisionInternal rev, EnumSet<TDContentOptions> contentOptions) {
        Map<String,Object> extra = extraPropertiesForRevision(rev, contentOptions);
        if(json != null) {
            rev.setJson(appendDictToJSON(json, extra));
        }
        else {
            rev.setProperties(extra);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> documentPropertiesFromJSON(byte[] json, String docId, String revId, boolean deleted, long sequence, EnumSet<TDContentOptions> contentOptions) {

        CBLRevisionInternal rev = new CBLRevisionInternal(docId, revId, deleted, this);
        rev.setSequence(sequence);
        Map<String, Object> extra = extraPropertiesForRevision(rev, contentOptions);
        if (json == null) {
            return extra;
        }

        Map<String, Object> docProperties = null;
        try {
            docProperties = CBLManager.getObjectMapper().readValue(json, Map.class);
            docProperties.putAll(extra);
            return docProperties;
        } catch (Exception e) {
            Log.e(CBLDatabase.TAG, "Error serializing properties to JSON", e);
        }

        return docProperties;
    }

    public CBLRevisionInternal getDocumentWithIDAndRev(String id, String rev, EnumSet<TDContentOptions> contentOptions) {
        CBLRevisionInternal result = null;
        String sql;

        Cursor cursor = null;
        try {
            cursor = null;
            String cols = "revid, deleted, sequence";
            if(!contentOptions.contains(TDContentOptions.TDNoBody)) {
                cols += ", json";
            }
            if(rev != null) {
                sql = "SELECT " + cols + " FROM revs, docs WHERE docs.docid=? AND revs.doc_id=docs.doc_id AND revid=? LIMIT 1";
                String[] args = {id, rev};
                cursor = sqliteDb.rawQuery(sql, args);
            }
            else {
                sql = "SELECT " + cols + " FROM revs, docs WHERE docs.docid=? AND revs.doc_id=docs.doc_id and current=1 and deleted=0 ORDER BY revid DESC LIMIT 1";
                String[] args = {id};
                cursor = sqliteDb.rawQuery(sql, args);
            }

            if(cursor.moveToFirst()) {
                if(rev == null) {
                    rev = cursor.getString(0);
                }
                boolean deleted = (cursor.getInt(1) > 0);
                result = new CBLRevisionInternal(id, rev, deleted, this);
                result.setSequence(cursor.getLong(2));
                if(!contentOptions.equals(EnumSet.of(TDContentOptions.TDNoBody))) {
                    byte[] json = null;
                    if(!contentOptions.contains(TDContentOptions.TDNoBody)) {
                        json = cursor.getBlob(3);
                    }
                    expandStoredJSONIntoRevisionWithAttachments(json, result, contentOptions);
                }
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting document with id and rev", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public boolean existsDocumentWithIDAndRev(String docId, String revId) {
        return getDocumentWithIDAndRev(docId, revId, EnumSet.of(TDContentOptions.TDNoBody)) != null;
    }

    public void loadRevisionBody(CBLRevisionInternal rev, EnumSet<TDContentOptions> contentOptions) throws CBLiteException {
        if(rev.getBody() != null) {
            return;
        }
        assert((rev.getDocId() != null) && (rev.getRevId() != null));

        Cursor cursor = null;
        CBLStatus result = new CBLStatus(CBLStatus.NOT_FOUND);
        try {
            String sql = "SELECT sequence, json FROM revs, docs WHERE revid=? AND docs.docid=? AND revs.doc_id=docs.doc_id LIMIT 1";
            String[] args = { rev.getRevId(), rev.getDocId()};
            cursor = sqliteDb.rawQuery(sql, args);
            if(cursor.moveToFirst()) {
                result.setCode(CBLStatus.OK);
                rev.setSequence(cursor.getLong(0));
                expandStoredJSONIntoRevisionWithAttachments(cursor.getBlob(1), rev, contentOptions);
            }
        } catch(SQLException e) {
            Log.e(CBLDatabase.TAG, "Error loading revision body", e);
            throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
    }

    public long getDocNumericID(String docId) {
        Cursor cursor = null;
        String[] args = { docId };

        long result = -1;
        try {
            cursor = sqliteDb.rawQuery("SELECT doc_id FROM docs WHERE docid=?", args);

            if(cursor.moveToFirst()) {
                result = cursor.getLong(0);
            }
            else {
                result = 0;
            }
        } catch (Exception e) {
            Log.e(CBLDatabase.TAG, "Error getting doc numeric id", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    /** HISTORY: **/

    /**
     * Returns all the known revisions (or all current/conflicting revisions) of a document.
     */
    public CBLRevisionList getAllRevisionsOfDocumentID(String docId, long docNumericID, boolean onlyCurrent) {

        String sql = null;
        if(onlyCurrent) {
            sql = "SELECT sequence, revid, deleted FROM revs " +
                    "WHERE doc_id=? AND current ORDER BY sequence DESC";
        }
        else {
            sql = "SELECT sequence, revid, deleted FROM revs " +
                    "WHERE doc_id=? ORDER BY sequence DESC";
        }

        String[] args = { Long.toString(docNumericID) };
        Cursor cursor = null;

        cursor = sqliteDb.rawQuery(sql, args);

        CBLRevisionList result;
        try {
            cursor.moveToFirst();
            result = new CBLRevisionList();
            while(!cursor.isAfterLast()) {
                CBLRevisionInternal rev = new CBLRevisionInternal(docId, cursor.getString(1), (cursor.getInt(2) > 0), this);
                rev.setSequence(cursor.getLong(0));
                result.add(rev);
                cursor.moveToNext();
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting all revisions of document", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    public CBLRevisionList getAllRevisionsOfDocumentID(String docId, boolean onlyCurrent) {
        long docNumericId = getDocNumericID(docId);
        if(docNumericId < 0) {
            return null;
        }
        else if(docNumericId == 0) {
            return new CBLRevisionList();
        }
        else {
            return getAllRevisionsOfDocumentID(docId, docNumericId, onlyCurrent);
        }
    }

    public List<String> getConflictingRevisionIDsOfDocID(String docID) {
        long docIdNumeric = getDocNumericID(docID);
        if(docIdNumeric < 0) {
            return null;
        }

        List<String> result = new ArrayList<String>();
        Cursor cursor = null;
        try {
            String[] args = { Long.toString(docIdNumeric) };
            cursor = sqliteDb.rawQuery("SELECT revid FROM revs WHERE doc_id=? AND current " +
                                           "ORDER BY revid DESC OFFSET 1", args);
            cursor.moveToFirst();
            while(!cursor.isAfterLast()) {
                result.add(cursor.getString(0));
                cursor.moveToNext();
            }

        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting all revisions of document", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    public String findCommonAncestorOf(CBLRevisionInternal rev, List<String> revIDs) {
        String result = null;

    	if (revIDs.size() == 0)
    		return null;
    	String docId = rev.getDocId();
    	long docNumericID = getDocNumericID(docId);
    	if (docNumericID <= 0)
    		return null;
    	String quotedRevIds = joinQuoted(revIDs);
    	String sql = "SELECT revid FROM revs " +
    			"WHERE doc_id=? and revid in (" + quotedRevIds + ") and revid <= ? " +
    			"ORDER BY revid DESC LIMIT 1";
    	String[] args = { Long.toString(docNumericID) };

    	Cursor cursor = null;
    	try {
    		cursor = sqliteDb.rawQuery(sql, args);
    		cursor.moveToFirst();
            if(!cursor.isAfterLast()) {
                result = cursor.getString(0);
    		}

    	} catch (SQLException e) {
    		Log.e(CBLDatabase.TAG, "Error getting all revisions of document", e);
    	} finally {
    		if(cursor != null) {
    			cursor.close();
    		}
    	}

    	return result;
    }

    /**
     * Returns an array of TDRevs in reverse chronological order, starting with the given revision.
     */
    public List<CBLRevisionInternal> getRevisionHistory(CBLRevisionInternal rev) {
        String docId = rev.getDocId();
        String revId = rev.getRevId();
        assert((docId != null) && (revId != null));

        long docNumericId = getDocNumericID(docId);
        if(docNumericId < 0) {
            return null;
        }
        else if(docNumericId == 0) {
            return new ArrayList<CBLRevisionInternal>();
        }

        String sql = "SELECT sequence, parent, revid, deleted FROM revs " +
                    "WHERE doc_id=? ORDER BY sequence DESC";
        String[] args = { Long.toString(docNumericId) };
        Cursor cursor = null;

        List<CBLRevisionInternal> result;
        try {
            cursor = sqliteDb.rawQuery(sql, args);

            cursor.moveToFirst();
            long lastSequence = 0;
            result = new ArrayList<CBLRevisionInternal>();
            while(!cursor.isAfterLast()) {
                long sequence = cursor.getLong(0);
                boolean matches = false;
                if(lastSequence == 0) {
                    matches = revId.equals(cursor.getString(2));
                }
                else {
                    matches = (sequence == lastSequence);
                }
                if(matches) {
                    revId = cursor.getString(2);
                    boolean deleted = (cursor.getInt(3) > 0);
                    CBLRevisionInternal aRev = new CBLRevisionInternal(docId, revId, deleted, this);
                    aRev.setSequence(cursor.getLong(0));
                    result.add(aRev);
                    lastSequence = cursor.getLong(1);
                    if(lastSequence == 0) {
                        break;
                    }
                }
                cursor.moveToNext();
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting revision history", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    // Splits a revision ID into its generation number and opaque suffix string
    public static int parseRevIDNumber(String rev) {
        int result = -1;
        int dashPos = rev.indexOf("-");
        if(dashPos >= 0) {
            try {
                result = Integer.parseInt(rev.substring(0, dashPos));
            } catch (NumberFormatException e) {
                // ignore, let it return -1
            }
        }
        return result;
    }

    // Splits a revision ID into its generation number and opaque suffix string
    public static String parseRevIDSuffix(String rev) {
        String result = null;
        int dashPos = rev.indexOf("-");
        if(dashPos >= 0) {
            result = rev.substring(dashPos + 1);
        }
        return result;
    }

    public static Map<String,Object> makeRevisionHistoryDict(List<CBLRevisionInternal> history) {
        if(history == null) {
            return null;
        }

        // Try to extract descending numeric prefixes:
        List<String> suffixes = new ArrayList<String>();
        int start = -1;
        int lastRevNo = -1;
        for (CBLRevisionInternal rev : history) {
            int revNo = parseRevIDNumber(rev.getRevId());
            String suffix = parseRevIDSuffix(rev.getRevId());
            if(revNo > 0 && suffix.length() > 0) {
                if(start < 0) {
                    start = revNo;
                }
                else if(revNo != lastRevNo - 1) {
                    start = -1;
                    break;
                }
                lastRevNo = revNo;
                suffixes.add(suffix);
            }
            else {
                start = -1;
                break;
            }
        }

        Map<String,Object> result = new HashMap<String,Object>();
        if(start == -1) {
            // we failed to build sequence, just stuff all the revs in list
            suffixes = new ArrayList<String>();
            for (CBLRevisionInternal rev : history) {
                suffixes.add(rev.getRevId());
            }
        }
        else {
            result.put("start", start);
        }
        result.put("ids", suffixes);

        return result;
    }

    /**
     * Returns the revision history as a _revisions dictionary, as returned by the REST API's ?revs=true option.
     */
    public Map<String,Object> getRevisionHistoryDict(CBLRevisionInternal rev) {
        return makeRevisionHistoryDict(getRevisionHistory(rev));
    }

    public CBLRevisionList changesSince(long lastSeq, CBLChangesOptions options, CBLFilterBlock filter) {
        // http://wiki.apache.org/couchdb/HTTP_database_API#Changes
        if(options == null) {
            options = new CBLChangesOptions();
        }

        boolean includeDocs = options.isIncludeDocs() || (filter != null);
        String additionalSelectColumns =  "";
        if(includeDocs) {
            additionalSelectColumns = ", json";
        }

        String sql = "SELECT sequence, revs.doc_id, docid, revid, deleted" + additionalSelectColumns + " FROM revs, docs "
                        + "WHERE sequence > ? AND current=1 "
                        + "AND revs.doc_id = docs.doc_id "
                        + "ORDER BY revs.doc_id, revid DESC";
        String[] args = {Long.toString(lastSeq)};
        Cursor cursor = null;
        CBLRevisionList changes = null;

        try {
            cursor = sqliteDb.rawQuery(sql, args);
            cursor.moveToFirst();
            changes = new CBLRevisionList();
            long lastDocId = 0;
            while(!cursor.isAfterLast()) {
                if(!options.isIncludeConflicts()) {
                    // Only count the first rev for a given doc (the rest will be losing conflicts):
                    long docNumericId = cursor.getLong(1);
                    if(docNumericId == lastDocId) {
                        cursor.moveToNext();
                        continue;
                    }
                    lastDocId = docNumericId;
                }

                CBLRevisionInternal rev = new CBLRevisionInternal(cursor.getString(2), cursor.getString(3), (cursor.getInt(4) > 0), this);
                rev.setSequence(cursor.getLong(0));
                if(includeDocs) {
                    expandStoredJSONIntoRevisionWithAttachments(cursor.getBlob(5), rev, options.getContentOptions());
                }
                if((filter == null) || (filter.filter(rev))) {
                    changes.add(rev);
                }
                cursor.moveToNext();
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error looking for changes", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        if(options.isSortBySequence()) {
            changes.sortBySequence();
        }
        changes.limit(options.getLimit());
        return changes;
    }

    /**
     * Define or clear a named filter function.
     *
     * Filters are used by push replications to choose which documents to send.
     */
    public void defineFilter(String filterName, CBLFilterBlock filter) {
        if(filters == null) {
            filters = new HashMap<String,CBLFilterBlock>();
        }
        if (filter != null) {
            filters.put(filterName, filter);
        }
        else {
            filters.remove(filterName);
        }
    }

    public String getDesignDocFunction(String fnName, String key, List<String>outLanguageList) {
        String[] path = fnName.split("/");
        if (path.length != 2) {
            return null;
        }
        String docId = String.format("_design/%s", path[0]);
        CBLRevisionInternal rev = getDocumentWithIDAndRev(docId, null, EnumSet.noneOf(TDContentOptions.class));
        if (rev == null) {
            return null;
        }

        String outLanguage = (String) rev.getPropertyForKey("language");
        if (outLanguage != null) {
            outLanguageList.add(outLanguage);
        } else {
            outLanguageList.add("javascript");
        }
        Map<String, Object> container = (Map<String, Object>) rev.getPropertyForKey(key);
        return (String) container.get(path[1]);
    }

    /**
     * Returns the existing filter function (block) registered with the given name.
     * Note that filters are not persistent -- you have to re-register them on every launch.
     */
    public CBLFilterBlock getFilter(String filterName) {
        CBLFilterBlock result = null;
        if(filters != null) {
            result = filters.get(filterName);
        }
        if (result == null) {
            CBLFilterCompiler filterCompiler = getFilterCompiler();
            if (filterCompiler == null) {
                return null;
            }

            List<String> outLanguageList = new ArrayList<String>();
            String sourceCode = getDesignDocFunction(filterName, "filters", outLanguageList);
            if (sourceCode == null) {
                return null;
            }
            String language = outLanguageList.get(0);
            CBLFilterBlock filter = filterCompiler.compileFilterFunction(sourceCode, language);
            if (filter == null) {
                Log.w(CBLDatabase.TAG, String.format("Filter %s failed to compile", filterName));
                return null;
            }
            defineFilter(filterName, filter);
            return filter;
        }
        return result;
    }

    /** VIEWS: **/

    public CBLView registerView(CBLView view) {
        if(view == null) {
            return null;
        }
        if(views == null) {
            views = new HashMap<String,CBLView>();
        }
        views.put(view.getName(), view);
        return view;
    }

    /**
     * Returns a CBLView object for the view with the given name.
     * (This succeeds even if the view doesn't already exist, but the view won't be added to
     * the database until the CBLView is assigned a map function.)
     */
    public CBLView getView(String name) {
        CBLView view = null;
        if(views != null) {
            view = views.get(name);
        }
        if(view != null) {
            return view;
        }
        return registerView(new CBLView(this, name));
    }



    public List<CBLQueryRow> queryViewNamed(String viewName, CBLQueryOptions options, List<Long> outLastSequence) throws CBLiteException {

        long before = System.currentTimeMillis();
        long lastSequence = 0;
        List<CBLQueryRow> rows = null;

        if (viewName != null && viewName.length() > 0) {
            final CBLView view = getView(viewName);
            if (view == null) {
                throw new CBLiteException(new CBLStatus(CBLStatus.NOT_FOUND));
            }
            lastSequence = view.getLastSequenceIndexed();
            if (options.getStale() == CBLQuery.CBLStaleness.CBLStaleNever || lastSequence <= 0) {
                view.updateIndex();
                lastSequence = view.getLastSequenceIndexed();
            } else if (options.getStale() == CBLQuery.CBLStaleness.CBLStaleUpdateAfter && lastSequence < getLastSequenceNumber()) {

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            view.updateIndex();
                        } catch (CBLiteException e) {
                            Log.e(CBLDatabase.TAG, "Error updating view index on background thread", e);
                        }
                    }
                }).start();

            }
            rows = view.queryWithOptions(options);


        } else {
            // nil view means query _all_docs
            // note: this is a little kludgy, but we have to pull out the "rows" field from the
            // result dictionary because that's what we want.  should be refactored, but
            // it's a little tricky, so postponing.
            Map<String,Object> allDocsResult = getAllDocs(options);
            rows = (List<CBLQueryRow>) allDocsResult.get("rows");
            lastSequence = getLastSequenceNumber();
        }
        outLastSequence.add(lastSequence);

        long delta = System.currentTimeMillis() - before;
        Log.d(CBLDatabase.TAG, String.format("Query view %s completed in %d milliseconds", viewName, delta));

        return rows;

    }




    CBLView makeAnonymousView() {
        for (int i=0; true; ++i) {
            String name = String.format("anon%d", i);
            CBLView existing = getExistingView(name);
            if (existing == null) {
                // this name has not been used yet, so let's use it
                return getView(name);
            }
        }
    }


    /**
     * Returns the existing CBLView with the given name, or nil if none.
     */
    public CBLView getExistingView(String name) {
        CBLView view = null;
        if(views != null) {
            view = views.get(name);
        }
        if(view != null) {
            return view;
        }
        view = new CBLView(this, name);
        if(view.getViewId() == 0) {
            return null;
        }

        return registerView(view);
    }

    public List<CBLView> getAllViews() {
        Cursor cursor = null;
        List<CBLView> result = null;

        try {
            cursor = sqliteDb.rawQuery("SELECT name FROM views", null);
            cursor.moveToFirst();
            result = new ArrayList<CBLView>();
            while(!cursor.isAfterLast()) {
                result.add(getView(cursor.getString(0)));
                cursor.moveToNext();
            }
        } catch (Exception e) {
            Log.e(CBLDatabase.TAG, "Error getting all views", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    public CBLStatus deleteViewNamed(String name) {
        CBLStatus result = new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR);
        try {
            String[] whereArgs = { name };
            int rowsAffected = sqliteDb.delete("views", "name=?", whereArgs);
            if(rowsAffected > 0) {
                result.setCode(CBLStatus.OK);
            }
            else {
                result.setCode(CBLStatus.NOT_FOUND);
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error deleting view", e);
        }
        return result;
    }


    public Map<String,Object> getAllDocs(CBLQueryOptions options) throws CBLiteException {

        Map<String, Object> result = new HashMap<String, Object>();
        List<CBLQueryRow> rows = new ArrayList<CBLQueryRow>();
        if(options == null) {
            options = new CBLQueryOptions();
        }

        long updateSeq = 0;
        if(options.isUpdateSeq()) {
            updateSeq = getLastSequenceNumber();  // TODO: needs to be atomic with the following SELECT
        }

        StringBuffer sql = new StringBuffer("SELECT revs.doc_id, docid, revid, sequence");
        if (options.isIncludeDocs()) {
            sql.append(", json");
        }
        if (options.isIncludeDeletedDocs()) {
            sql.append(", deleted");
        }
        sql.append(" FROM revs, docs WHERE");
        if (options.getKeys() != null) {
            if (options.getKeys().size() == 0) {
                return result;
            }
            String commaSeperatedIds = joinQuotedObjects(options.getKeys());
            sql.append(String.format(" revs.doc_id IN (SELECT doc_id FROM docs WHERE docid IN (%s)) AND", commaSeperatedIds));
        }
        sql.append(" docs.doc_id = revs.doc_id AND current=1");
        if (!options.isIncludeDeletedDocs()) {
            sql.append(" AND deleted=0");
        }
        List<String> args = new ArrayList<String>();
        Object minKey = options.getStartKey();
        Object maxKey = options.getEndKey();
        boolean inclusiveMin = true;
        boolean inclusiveMax = options.isInclusiveEnd();
        if (options.isDescending()) {
            minKey = maxKey;
            maxKey = options.getStartKey();
            inclusiveMin = inclusiveMax;
            inclusiveMax = true;
        }
        if (minKey != null) {
            assert(minKey instanceof String);
            sql.append((inclusiveMin ? " AND docid >= ?" :  " AND docid > ?"));
            args.add((String)minKey);
        }
        if (maxKey != null) {
            assert(maxKey instanceof String);
            sql.append((inclusiveMax ? " AND docid <= ?" :  " AND docid < ?"));
            args.add((String)maxKey);
        }

        sql.append(
                String.format(
                        " ORDER BY docid %s, %s revid DESC LIMIT ? OFFSET ?",
                        (options.isDescending() ? "DESC" : "ASC"),
                        (options.isIncludeDeletedDocs() ? "deleted ASC," : "")
                )
        );

        args.add(Integer.toString(options.getLimit()));
        args.add(Integer.toString(options.getSkip()));

        Cursor cursor = null;
        long lastDocID = 0;
        Map<String, CBLQueryRow> docs = new HashMap<String, CBLQueryRow>();


        try {
            cursor = sqliteDb.rawQuery(sql.toString(), args.toArray(new String[args.size()]));

            cursor.moveToFirst();
            while(!cursor.isAfterLast()) {

                long docNumericID = cursor.getLong(0);
                if(docNumericID == lastDocID) {
                    cursor.moveToNext();
                    continue;
                }
                lastDocID = docNumericID;

                String docId = cursor.getString(1);
                String revId = cursor.getString(2);
                long sequenceNumber = cursor.getLong(3);
                boolean deleted = options.isIncludeDeletedDocs() && cursor.getInt(cursor.getColumnIndex("deleted"))>0;
                Map<String, Object> docContents = null;
                if (options.isIncludeDocs()) {
                    byte[] json = cursor.getBlob(4);
                    docContents = documentPropertiesFromJSON(json, docId, revId, deleted, sequenceNumber, options.getContentOptions());
                }
                Map<String, Object> value = new HashMap<String, Object>();
                value.put("rev", revId);
                if (options.isIncludeDeletedDocs()){
                    value.put("deleted", (deleted ? true : null));
                }
                CBLQueryRow change = new CBLQueryRow(docId, sequenceNumber, docId, value, docContents);
                change.setDatabase(this);
                if (options.getKeys() != null) {
                    docs.put(docId, change);
                } else {
                    rows.add(change);
                }

                cursor.moveToNext();

            }

            if (options.getKeys() != null) {
                for (Object docIdObject : options.getKeys()) {
                    if (docIdObject instanceof String) {
                        String docId = (String) docIdObject;
                        CBLQueryRow change = docs.get(docId);
                        if (change == null) {
                            Map<String, Object> value = new HashMap<String, Object>();
                            long docNumericID = getDocNumericID(docId);
                            if (docNumericID > 0) {
                                boolean deleted;
                                List<Boolean> outIsDeleted = new ArrayList<Boolean>();
                                List<Boolean> outIsConflict = new ArrayList<Boolean>();
                                String revId = winningRevIDOfDoc(docNumericID, outIsDeleted, outIsConflict);
                                if (outIsDeleted.size() > 0) {
                                    deleted = true;
                                }
                                if (revId != null) {
                                    value.put("rev", revId);
                                    value.put("deleted", true);
                                }
                            }
                            change = new CBLQueryRow((value != null ? docId : null), 0, docId, value, null);
                            change.setDatabase(this);
                        }
                        rows.add(change);
                    }
                }

            }


        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting all docs", e);
            throw new CBLiteException("Error getting all docs", e, new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR));
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        int totalRows = cursor.getCount();  //??? Is this true, or does it ignore limit/offset?
        result.put("rows", rows);
        result.put("total_rows", totalRows);
        result.put("offset", options.getSkip());
        if(updateSeq != 0) {
            result.put("update_seq", updateSeq);
        }

        return result;
    }


    /**
     * Returns the rev ID of the 'winning' revision of this document, and whether it's deleted.
     */
    String winningRevIDOfDoc(long docNumericId, List<Boolean> outIsDeleted, List<Boolean> outIsConflict) throws CBLiteException {

        Cursor cursor = null;
        String sql = "SELECT revid, deleted FROM revs" +
                " WHERE doc_id=? and current=1" +
                " ORDER BY deleted asc, revid desc LIMIT 2";

        String[] args = { Long.toString(docNumericId) };
        String revId = null;

        try {
            cursor = sqliteDb.rawQuery(sql, args);

            cursor.moveToFirst();
            if (!cursor.isAfterLast()) {
                revId = cursor.getString(0);
                boolean deleted = cursor.getInt(1)>0;
                if (deleted) {
                    outIsDeleted.add(true);
                }
                // The document is in conflict if there are two+ result rows that are not deletions.
                boolean hasNextResult = cursor.moveToNext();
                boolean isNextDeleted = cursor.getInt(1)>0;
                boolean isInConflict = !deleted && hasNextResult && isNextDeleted;
                if (isInConflict) {
                    outIsConflict.add(true);
                }
            }

        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error", e);
            throw new CBLiteException("Error", e, new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR));
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return revId;
    }

    public void addChangeListener(CBLDatabaseChangedFunction listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(CBLDatabaseChangedFunction listener) {
        changeListeners.remove(listener);
    }

    /*************************************************************************************************/
    /*** CBLDatabase+Attachments                                                                    ***/
    /*************************************************************************************************/

    void insertAttachmentForSequence(CBLAttachmentInternal attachment, long sequence) throws CBLiteException {
        insertAttachmentForSequenceWithNameAndType(sequence, attachment.getName(), attachment.getContentType(), attachment.getRevpos(), attachment.getBlobKey());
    }

    public void insertAttachmentForSequenceWithNameAndType(InputStream contentStream, long sequence, String name, String contentType, int revpos) throws CBLiteException {
        assert(sequence > 0);
        assert(name != null);

        CBLBlobKey key = new CBLBlobKey();
        if(!attachments.storeBlobStream(contentStream, key)) {
            throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
        }
        insertAttachmentForSequenceWithNameAndType(sequence, name, contentType, revpos, key);
    }

    public void insertAttachmentForSequenceWithNameAndType(long sequence, String name, String contentType, int revpos, CBLBlobKey key) throws CBLiteException {
        try {
            ContentValues args = new ContentValues();
            args.put("sequence", sequence);
            args.put("filename", name);
            args.put("key", key.getBytes());
            args.put("type", contentType);
            args.put("length", attachments.getSizeOfBlob(key));
            args.put("revpos", revpos);
            sqliteDb.insert("attachments", null, args);
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error inserting attachment", e);
            throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
        }
    }

    void installAttachment(CBLAttachmentInternal attachment, Map<String, Object> attachInfo) throws CBLiteException {
        String digest = (String) attachInfo.get("digest");
        if (digest == null) {
            throw new CBLiteException(CBLStatus.BAD_ATTACHMENT);
        }
        if (pendingAttachmentsByDigest != null && pendingAttachmentsByDigest.containsKey(digest)) {
            CBLBlobStoreWriter writer = pendingAttachmentsByDigest.get(digest);
            try {
                CBLBlobStoreWriter blobStoreWriter = (CBLBlobStoreWriter) writer;
                blobStoreWriter.install();
                attachment.setBlobKey(blobStoreWriter.getBlobKey());
                attachment.setLength(blobStoreWriter.getLength());
            } catch (Exception e) {
                throw new CBLiteException(e, CBLStatus.STATUS_ATTACHMENT_ERROR);
            }
        }
    }

    private Map<String, CBLBlobStoreWriter> getPendingAttachmentsByDigest() {
        if (pendingAttachmentsByDigest == null) {
            pendingAttachmentsByDigest = new HashMap<String, CBLBlobStoreWriter>();
        }
        return pendingAttachmentsByDigest;
    }


    public void copyAttachmentNamedFromSequenceToSequence(String name, long fromSeq, long toSeq) throws CBLiteException {
        assert(name != null);
        assert(toSeq > 0);
        if(fromSeq < 0) {
            throw new CBLiteException(CBLStatus.NOT_FOUND);
        }

        Cursor cursor = null;

        String[] args = { Long.toString(toSeq), name, Long.toString(fromSeq), name };
        try {
            sqliteDb.execSQL("INSERT INTO attachments (sequence, filename, key, type, length, revpos) " +
                    "SELECT ?, ?, key, type, length, revpos FROM attachments " +
                    "WHERE sequence=? AND filename=?", args);
            cursor = sqliteDb.rawQuery("SELECT changes()", null);
            cursor.moveToFirst();
            int rowsUpdated = cursor.getInt(0);
            if(rowsUpdated == 0) {
                // Oops. This means a glitch in our attachment-management or pull code,
                // or else a bug in the upstream server.
                Log.w(CBLDatabase.TAG, "Can't find inherited attachment " + name + " from seq# " + Long.toString(fromSeq) + " to copy to " + Long.toString(toSeq));
                throw new CBLiteException(CBLStatus.NOT_FOUND);
            }
            else {
                return;
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error copying attachment", e);
            throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Returns the content and MIME type of an attachment
     */
    public CBLAttachment getAttachmentForSequence(long sequence, String filename) throws CBLiteException {
        assert(sequence > 0);
        assert(filename != null);

        Cursor cursor = null;

        String[] args = { Long.toString(sequence), filename };
        try {
            cursor = sqliteDb.rawQuery("SELECT key, type FROM attachments WHERE sequence=? AND filename=?", args);

            if(!cursor.moveToFirst()) {
                throw new CBLiteException(CBLStatus.NOT_FOUND);
            }

            byte[] keyData = cursor.getBlob(0);
            //TODO add checks on key here? (ios version)
            CBLBlobKey key = new CBLBlobKey(keyData);
            InputStream contentStream = attachments.blobStreamForKey(key);
            if(contentStream == null) {
                Log.e(CBLDatabase.TAG, "Failed to load attachment");
                throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
            }
            else {
                CBLAttachment result = new CBLAttachment(contentStream, cursor.getString(1));
                return result;
            }

        } catch (SQLException e) {
            throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

    }

    /**
     * Returns the location of an attachment's file in the blob store.
     */
    String getAttachmentPathForSequence(long sequence, String filename) throws CBLiteException {

        assert(sequence > 0);
        assert(filename != null);
        Cursor cursor = null;
        String filePath = null;

        String args[] = { Long.toString(sequence), filename };
        try {
            cursor = sqliteDb.rawQuery("SELECT key, type, encoding FROM attachments WHERE sequence=? AND filename=?", args);

            if(!cursor.moveToFirst()) {
                throw new CBLiteException(CBLStatus.NOT_FOUND);
            }

            byte[] keyData = cursor.getBlob(0);
            CBLBlobKey key = new CBLBlobKey(keyData);
            filePath = getAttachments().pathForKey(key);
            return filePath;

        } catch (SQLException e) {
            throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Constructs an "_attachments" dictionary for a revision, to be inserted in its JSON body.
     */
    public Map<String,Object> getAttachmentsDictForSequenceWithContent(long sequence, EnumSet<TDContentOptions> contentOptions) {
        assert(sequence > 0);

        Cursor cursor = null;

        String args[] = { Long.toString(sequence) };
        try {
            cursor = sqliteDb.rawQuery("SELECT filename, key, type, length, revpos FROM attachments WHERE sequence=?", args);

            if(!cursor.moveToFirst()) {
                return null;
            }

            Map<String, Object> result = new HashMap<String, Object>();

            while(!cursor.isAfterLast()) {

                boolean dataSuppressed = false;
                int length = cursor.getInt(3);

                byte[] keyData = cursor.getBlob(1);
                CBLBlobKey key = new CBLBlobKey(keyData);
                String digestString = "sha1-" + Base64.encodeBytes(keyData);
                String dataBase64 = null;
                if(contentOptions.contains(TDContentOptions.TDIncludeAttachments)) {
                    if (contentOptions.contains(TDContentOptions.TDBigAttachmentsFollow) &&
                            length >= CBLDatabase.kBigAttachmentLength) {
                        dataSuppressed = true;
                        byte[] data = attachments.blobForKey(key);
                        if(data != null) {
                            dataBase64 = Base64.encodeBytes(data);  // <-- very expensive
                        }
                    }
                    else {
                        byte[] data = attachments.blobForKey(key);

                        if(data != null) {
                            dataBase64 = Base64.encodeBytes(data);  // <-- very expensive
                        }
                        else {
                            Log.w(CBLDatabase.TAG, "Error loading attachment");
                        }

                    }

                }

                Map<String, Object> attachment = new HashMap<String, Object>();



                if(dataBase64 == null || dataSuppressed == true) {
                    attachment.put("stub", true);
                }

                if(dataBase64 != null) {
                    attachment.put("data", dataBase64);
                }

                if (dataSuppressed == true) {
                    attachment.put("follows", true);
                }

                attachment.put("digest", digestString);
                String contentType = cursor.getString(2);
                attachment.put("content_type", contentType);
                attachment.put("length", length);
                attachment.put("revpos", cursor.getInt(4));

                String filename = cursor.getString(0);
                result.put(filename, attachment);

                cursor.moveToNext();
            }

            return result;

        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting attachments for sequence", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Modifies a CBLRevisionInternal's body by changing all attachments with revpos < minRevPos into stubs.
     *
     * @param rev
     * @param minRevPos
     */
    public void stubOutAttachmentsIn(CBLRevisionInternal rev, int minRevPos)
    {
        if (minRevPos <= 1) {
            return;
        }
        Map<String, Object> properties = (Map<String,Object>)rev.getProperties();
        Map<String, Object> attachments = null;
        if(properties != null) {
            attachments = (Map<String,Object>)properties.get("_attachments");
        }
        Map<String, Object> editedProperties = null;
        Map<String, Object> editedAttachments = null;
        for (String name : attachments.keySet()) {
            Map<String,Object> attachment = (Map<String,Object>)attachments.get(name);
            int revPos = (Integer) attachment.get("revpos");
            Object stub = attachment.get("stub");
            if (revPos > 0 && revPos < minRevPos && (stub == null)) {
                // Strip this attachment's body. First make its dictionary mutable:
                if (editedProperties == null) {
                    editedProperties = new HashMap<String,Object>(properties);
                    editedAttachments = new HashMap<String,Object>(attachments);
                    editedProperties.put("_attachments", editedAttachments);
                }
                // ...then remove the 'data' and 'follows' key:
                Map<String,Object> editedAttachment = new HashMap<String,Object>(attachment);
                editedAttachment.remove("data");
                editedAttachment.remove("follows");
                editedAttachment.put("stub", true);
                editedAttachments.put(name,editedAttachment);
                Log.d(CBLDatabase.TAG, "Stubbed out attachment" + rev + " " + name + ": revpos" + revPos + " " + minRevPos);
            }
        }
        if (editedProperties != null)
            rev.setProperties(editedProperties);
    }

    void stubOutAttachmentsInRevision(Map<String, CBLAttachmentInternal> attachments, CBLRevisionInternal rev) {

        Map<String, Object> properties = rev.getProperties();
        Map<String, Object> attachmentsFromProps =  (Map<String, Object>) properties.get("_attachments");
        if (attachmentsFromProps != null) {
            for (String attachmentKey : attachmentsFromProps.keySet()) {
                Map<String, Object> attachmentFromProps = (Map<String, Object>) attachmentsFromProps.get(attachmentKey);
                if (attachmentFromProps.get("follows") != null || attachmentFromProps.get("data") != null) {
                    attachmentFromProps.remove("follows");
                    attachmentFromProps.remove("data");
                    attachmentFromProps.put("stub", true);
                    if (attachmentFromProps.get("revpos") == null) {
                        attachmentFromProps.put("revpos",rev.getGeneration());
                    }
                    CBLAttachmentInternal attachmentObject = attachments.get(attachmentKey);
                    if (attachmentObject != null) {
                        attachmentFromProps.put("length", attachmentObject.getLength());
                        attachmentFromProps.put("digest", attachmentObject.getBlobKey().base64Digest());
                    }
                    attachmentFromProps.put(attachmentKey, attachmentFromProps);
                }
            }
        }

    }


    /**
     * Given a newly-added revision, adds the necessary attachment rows to the sqliteDb and
     * stores inline attachments into the blob store.
     */
    void processAttachmentsForRevision(Map<String, CBLAttachmentInternal> attachments, CBLRevisionInternal rev, long parentSequence) throws CBLiteException {

        assert(rev != null);
        long newSequence = rev.getSequence();
        assert(newSequence > parentSequence);
        int generation = rev.getGeneration();
        assert(generation > 0);

        // If there are no attachments in the new rev, there's nothing to do:
        Map<String,Object> revAttachments = null;
        Map<String,Object> properties = (Map<String,Object>)rev.getProperties();
        if(properties != null) {
            revAttachments = (Map<String,Object>)properties.get("_attachments");
        }
        if(revAttachments == null || revAttachments.size() == 0 || rev.isDeleted()) {
            return;
        }

        for (String name : revAttachments.keySet()) {
            CBLAttachmentInternal attachment = attachments.get(name);
            if (attachment != null) {
                // Determine the revpos, i.e. generation # this was added in. Usually this is
                // implicit, but a rev being pulled in replication will have it set already.
                if (attachment.getRevpos() == 0) {
                    attachment.setRevpos(generation);
                }
                else if (attachment.getRevpos() > generation) {
                    Log.w(CBLDatabase.TAG, String.format("Attachment %s %s has unexpected revpos %s, setting to %s", rev, name, attachment.getRevpos(), generation));
                    attachment.setRevpos(generation);
                }
                // Finally insert the attachment:
                insertAttachmentForSequence(attachment, newSequence);

            }
            else {
                // It's just a stub, so copy the previous revision's attachment entry:
                //? Should I enforce that the type and digest (if any) match?
                copyAttachmentNamedFromSequenceToSequence(name, parentSequence, newSequence);
            }

        }

    }


    /**
     * Updates or deletes an attachment, creating a new document revision in the process.
     * Used by the PUT / DELETE methods called on attachment URLs.
     */
    public CBLRevisionInternal updateAttachment(String filename, InputStream contentStream, String contentType, String docID, String oldRevID) throws CBLiteException {

        boolean isSuccessful = false;

        if(filename == null || filename.length() == 0 || (contentStream != null && contentType == null) || (oldRevID != null && docID == null) || (contentStream != null && docID == null)) {
            throw new CBLiteException(CBLStatus.BAD_REQUEST);
        }

        beginTransaction();
        try {
            CBLRevisionInternal oldRev = new CBLRevisionInternal(docID, oldRevID, false, this);
            if(oldRevID != null) {

                // Load existing revision if this is a replacement:
                try {
                    loadRevisionBody(oldRev, EnumSet.noneOf(TDContentOptions.class));
                } catch (CBLiteException e) {
                    if (e.getCBLStatus().getCode() == CBLStatus.NOT_FOUND && existsDocumentWithIDAndRev(docID, null) ) {
                        throw new CBLiteException(CBLStatus.CONFLICT);
                    }
                }

                Map<String,Object> attachments = (Map<String, Object>) oldRev.getProperties().get("_attachments");
                if(contentStream == null && attachments != null && !attachments.containsKey(filename)) {
                    throw new CBLiteException(CBLStatus.NOT_FOUND);
                }
                // Remove the _attachments stubs so putRevision: doesn't copy the rows for me
                // OPT: Would be better if I could tell loadRevisionBody: not to add it
                if(attachments != null) {
                    Map<String,Object> properties = new HashMap<String,Object>(oldRev.getProperties());
                    properties.remove("_attachments");
                    oldRev.setBody(new CBLBody(properties));
                }
            } else {
                // If this creates a new doc, it needs a body:
                oldRev.setBody(new CBLBody(new HashMap<String,Object>()));
            }

            // Create a new revision:
            CBLStatus putStatus = new CBLStatus();
            CBLRevisionInternal newRev = putRevision(oldRev, oldRevID, false, putStatus);
            if(newRev == null) {
                return null;
            }

            if(oldRevID != null) {
                // Copy all attachment rows _except_ for the one being updated:
                String[] args = { Long.toString(newRev.getSequence()), Long.toString(oldRev.getSequence()), filename };
                sqliteDb.execSQL("INSERT INTO attachments "
                        + "(sequence, filename, key, type, length, revpos) "
                        + "SELECT ?, filename, key, type, length, revpos FROM attachments "
                        + "WHERE sequence=? AND filename != ?", args);
            }

            if(contentStream != null) {
                // If not deleting, add a new attachment entry:
                insertAttachmentForSequenceWithNameAndType(contentStream, newRev.getSequence(),
                        filename, contentType, newRev.getGeneration());

            }

            isSuccessful = true;
            return newRev;

        } catch(SQLException e) {
            Log.e(TAG, "Error updating attachment", e);
            throw new CBLiteException(new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR));
        } finally {
            endTransaction(isSuccessful);
        }

    }


    public void rememberAttachmentWritersForDigests(Map<String, CBLBlobStoreWriter> blobsByDigest) {

        getPendingAttachmentsByDigest().putAll(blobsByDigest);
    }

    void rememberAttachmentWriter(CBLBlobStoreWriter writer) {
        getPendingAttachmentsByDigest().put(writer.mD5DigestString(), writer);
    }


     /**
      * Deletes obsolete attachments from the sqliteDb and blob store.
      */
    public CBLStatus garbageCollectAttachments() {
        // First delete attachment rows for already-cleared revisions:
        // OPT: Could start after last sequence# we GC'd up to

        try {
            sqliteDb.execSQL("DELETE FROM attachments WHERE sequence IN " +
                    "(SELECT sequence from revs WHERE json IS null)");
        }
        catch(SQLException e) {
            Log.e(CBLDatabase.TAG, "Error deleting attachments", e);
        }

        // Now collect all remaining attachment IDs and tell the store to delete all but these:
        Cursor cursor = null;
        try {
            cursor = sqliteDb.rawQuery("SELECT DISTINCT key FROM attachments", null);

            cursor.moveToFirst();
            List<CBLBlobKey> allKeys = new ArrayList<CBLBlobKey>();
            while(!cursor.isAfterLast()) {
                CBLBlobKey key = new CBLBlobKey(cursor.getBlob(0));
                allKeys.add(key);
                cursor.moveToNext();
            }

            int numDeleted = attachments.deleteBlobsExceptWithKeys(allKeys);
            if(numDeleted < 0) {
                return new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR);
            }

            Log.v(CBLDatabase.TAG, "Deleted " + numDeleted + " attachments");

            return new CBLStatus(CBLStatus.OK);
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error finding attachment keys in use", e);
            return new CBLStatus(CBLStatus.INTERNAL_SERVER_ERROR);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
    }

    /*************************************************************************************************/
    /*** CBLDatabase+Insertion                                                                      ***/
    /*************************************************************************************************/

    /** DOCUMENT & REV IDS: **/

    public static boolean isValidDocumentId(String id) {
        // http://wiki.apache.org/couchdb/HTTP_Document_API#Documents
        if(id == null || id.length() == 0) {
            return false;
        }
        if(id.charAt(0) == '_') {
            return  (id.startsWith("_design/"));
        }
        return true;
        // "_local/*" is not a valid document ID. Local docs have their own API and shouldn't get here.
    }

    public static String generateDocumentId() {
        return CBLMisc.TDCreateUUID();
    }

    public String generateNextRevisionID(String revisionId) {
        // Revision IDs have a generation count, a hyphen, and a UUID.
        int generation = 0;
        if(revisionId != null) {
            generation = CBLRevisionInternal.generationFromRevID(revisionId);
            if(generation == 0) {
                return null;
            }
        }
        String digest = CBLMisc.TDCreateUUID();  // TODO: Generate canonical digest of body
        return Integer.toString(generation + 1) + "-" + digest;
    }

    public long insertDocumentID(String docId) {
        long rowId = -1;
        try {
            ContentValues args = new ContentValues();
            args.put("docid", docId);
            rowId = sqliteDb.insert("docs", null, args);
        } catch (Exception e) {
            Log.e(CBLDatabase.TAG, "Error inserting document id", e);
        }
        return rowId;
    }

    public long getOrInsertDocNumericID(String docId) {
        long docNumericId = getDocNumericID(docId);
        if(docNumericId == 0) {
            docNumericId = insertDocumentID(docId);
        }
        return docNumericId;
    }

    /**
     * Parses the _revisions dict from a document into an array of revision ID strings
     */
    public static List<String> parseCouchDBRevisionHistory(Map<String,Object> docProperties) {
        Map<String,Object> revisions = (Map<String,Object>)docProperties.get("_revisions");
        if(revisions == null) {
            return null;
        }
        List<String> revIDs = (List<String>)revisions.get("ids");
        Integer start = (Integer)revisions.get("start");
        if(start != null) {
            for(int i=0; i < revIDs.size(); i++) {
                String revID = revIDs.get(i);
                revIDs.set(i, Integer.toString(start--) + "-" + revID);
            }
        }
        return revIDs;
    }

    /** INSERTION: **/

    public byte[] encodeDocumentJSON(CBLRevisionInternal rev) {

        Map<String,Object> origProps = rev.getProperties();
        if(origProps == null) {
            return null;
        }

        // Don't allow any "_"-prefixed keys. Known ones we'll ignore, unknown ones are an error.
        Map<String,Object> properties = new HashMap<String,Object>(origProps.size());
        for (String key : origProps.keySet()) {
            if(key.startsWith("_")) {
                if(!KNOWN_SPECIAL_KEYS.contains(key)) {
                    Log.e(TAG, "CBLDatabase: Invalid top-level key '" + key + "' in document to be inserted");
                    return null;
                }
            } else {
                properties.put(key, origProps.get(key));
            }
        }

        byte[] json = null;
        try {
            json = CBLManager.getObjectMapper().writeValueAsBytes(properties);
        } catch (Exception e) {
            Log.e(CBLDatabase.TAG, "Error serializing " + rev + " to JSON", e);
        }
        return json;
    }

    public void notifyChange(CBLRevisionInternal rev, URL source) {
        Map<String,Object> changeNotification = new HashMap<String, Object>();
        changeNotification.put("rev", rev);
        changeNotification.put("seq", rev.getSequence());
        if(source != null) {
            changeNotification.put("source", source);
        }

        // Notify the corresponding instantiated CBLDocument object (if any):
        CBLDocument cachedDocument = getCachedDocument(rev.getDocId());
        if (cachedDocument != null) {
            cachedDocument.revisionAdded(changeNotification);
        }

        notifyChangeListeners(changeNotification);
    }

    private void notifyChangeListeners(Map<String,Object> changeNotification) {
        for (CBLDatabaseChangedFunction changeListener : changeListeners) {
            changeListener.onDatabaseChanged(this, changeNotification);
        }
    }

    public long insertRevision(CBLRevisionInternal rev, long docNumericID, long parentSequence, boolean current, byte[] data) {
        long rowId = 0;
        try {
            ContentValues args = new ContentValues();
            args.put("doc_id", docNumericID);
            args.put("revid", rev.getRevId());
            if(parentSequence != 0) {
                args.put("parent", parentSequence);
            }
            args.put("current", current);
            args.put("deleted", rev.isDeleted());
            args.put("json", data);
            rowId = sqliteDb.insert("revs", null, args);
            rev.setSequence(rowId);
        } catch (Exception e) {
            Log.e(CBLDatabase.TAG, "Error inserting revision", e);
        }
        return rowId;
    }

    // TODO: move this to internal API
    public CBLRevisionInternal putRevision(CBLRevisionInternal rev, String prevRevId, CBLStatus resultStatus) throws CBLiteException  {
        return putRevision(rev, prevRevId, false, resultStatus);
    }

    public CBLRevisionInternal putRevision(CBLRevisionInternal rev, String prevRevId,  boolean allowConflict) throws CBLiteException  {
        CBLStatus ignoredStatus = new CBLStatus();
        return putRevision(rev, prevRevId, allowConflict, ignoredStatus);
    }


    /**
     * Stores a new (or initial) revision of a document.
     *
     * This is what's invoked by a PUT or POST. As with those, the previous revision ID must be supplied when necessary and the call will fail if it doesn't match.
     *
     * @param rev The revision to add. If the docID is null, a new UUID will be assigned. Its revID must be null. It must have a JSON body.
     * @param prevRevId The ID of the revision to replace (same as the "?rev=" parameter to a PUT), or null if this is a new document.
     * @param allowConflict If false, an error status 409 will be returned if the insertion would create a conflict, i.e. if the previous revision already has a child.
     * @param resultStatus On return, an HTTP status code indicating success or failure.
     * @return A new CBLRevisionInternal with the docID, revID and sequence filled in (but no body).
     */
    @SuppressWarnings("unchecked")
    public CBLRevisionInternal putRevision(CBLRevisionInternal rev, String prevRevId, boolean allowConflict, CBLStatus resultStatus) throws CBLiteException {
        // prevRevId is the rev ID being replaced, or nil if an insert
        String docId = rev.getDocId();
        boolean deleted = rev.isDeleted();
        if((rev == null) || ((prevRevId != null) && (docId == null)) || (deleted && (docId == null))
                || ((docId != null) && !isValidDocumentId(docId))) {
            throw new CBLiteException(CBLStatus.BAD_REQUEST);
        }

        beginTransaction();
        Cursor cursor = null;

        //// PART I: In which are performed lookups and validations prior to the insert...

        long docNumericID = (docId != null) ? getDocNumericID(docId) : 0;
        long parentSequence = 0;
        try {
            if(prevRevId != null) {
                // Replacing: make sure given prevRevID is current & find its sequence number:
                if(docNumericID <= 0) {
                    throw new CBLiteException(CBLStatus.NOT_FOUND);
                }

                String[] args = {Long.toString(docNumericID), prevRevId};
                String additionalWhereClause = "";
                if(!allowConflict) {
                    additionalWhereClause = "AND current=1";
                }

                cursor = sqliteDb.rawQuery("SELECT sequence FROM revs WHERE doc_id=? AND revid=? " + additionalWhereClause + " LIMIT 1", args);

                if(cursor.moveToFirst()) {
                    parentSequence = cursor.getLong(0);
                }

                if(parentSequence == 0) {
                    // Not found: either a 404 or a 409, depending on whether there is any current revision
                    if(!allowConflict && existsDocumentWithIDAndRev(docId, null)) {
                        throw new CBLiteException(CBLStatus.CONFLICT);
                    }
                    else {
                        throw new CBLiteException(CBLStatus.NOT_FOUND);
                    }
                }

                if(validations != null && validations.size() > 0) {
                    // Fetch the previous revision and validate the new one against it:
                    CBLRevisionInternal prevRev = new CBLRevisionInternal(docId, prevRevId, false, this);
                    validateRevision(rev, prevRev);
                }

                // Make replaced rev non-current:
                ContentValues updateContent = new ContentValues();
                updateContent.put("current", 0);
                sqliteDb.update("revs", updateContent, "sequence=" + parentSequence, null);
            }
            else {
                // Inserting first revision.
                if(deleted && (docId != null)) {
                    // Didn't specify a revision to delete: 404 or a 409, depending
                    if(existsDocumentWithIDAndRev(docId, null)) {
                        throw new CBLiteException(CBLStatus.CONFLICT);
                    }
                    else {
                        throw new CBLiteException(CBLStatus.NOT_FOUND);
                    }
                }

                // Validate:
                validateRevision(rev, null);

                if(docId != null) {
                    // Inserting first revision, with docID given (PUT):
                    if(docNumericID <= 0) {
                        // Doc doesn't exist at all; create it:
                        docNumericID = insertDocumentID(docId);
                        if(docNumericID <= 0) {
                            return null;
                        }
                    } else {
                        // Doc exists; check whether current winning revision is deleted:
                        String[] args = { Long.toString(docNumericID) };
                        cursor = sqliteDb.rawQuery("SELECT sequence, deleted FROM revs WHERE doc_id=? and current=1 ORDER BY revid DESC LIMIT 1", args);

                        if(cursor.moveToFirst()) {
                            boolean wasAlreadyDeleted = (cursor.getInt(1) > 0);
                            if(wasAlreadyDeleted) {
                                // Make the deleted revision no longer current:
                                ContentValues updateContent = new ContentValues();
                                updateContent.put("current", 0);
                                sqliteDb.update("revs", updateContent, "sequence=" + cursor.getLong(0), null);
                            }
                            else if (!allowConflict) {
                                String msg = String.format("docId (%s) already exists, current not " +
                                        "deleted, so conflict.  Did you forget to pass in a previous " +
                                        "revision ID in the properties being saved?", docId);
                                throw new CBLiteException(msg, CBLStatus.CONFLICT);
                            }
                        }
                    }
                }
                else {
                    // Inserting first revision, with no docID given (POST): generate a unique docID:
                    docId = CBLDatabase.generateDocumentId();
                    docNumericID = insertDocumentID(docId);
                    if(docNumericID <= 0) {
                        return null;
                    }
                }
            }

            //// PART II: In which insertion occurs...

            // Get the attachments:
            Map<String, CBLAttachmentInternal> attachments = getAttachmentsFromRevision(rev);

            // Bump the revID and update the JSON:
            String newRevId = generateNextRevisionID(prevRevId);
            byte[] data = null;
            if(!rev.isDeleted()) {
                data = encodeDocumentJSON(rev);
                if(data == null) {
                    // bad or missing json
                    throw new CBLiteException(CBLStatus.BAD_REQUEST);
                }
            }

            rev = rev.copyWithDocID(docId, newRevId);
            stubOutAttachmentsInRevision(attachments, rev);

            // Now insert the rev itself:
            long newSequence = insertRevision(rev, docNumericID, parentSequence, true, data);
            if(newSequence == 0) {
                return null;
            }

            // Store any attachments:
            if(attachments != null) {
                processAttachmentsForRevision(attachments, rev, parentSequence);
            }

            // Success!
            if(deleted) {
                resultStatus.setCode(CBLStatus.OK);
            }
            else {
                resultStatus.setCode(CBLStatus.CREATED);
            }

        } catch (SQLException e1) {
            Log.e(CBLDatabase.TAG, "Error putting revision", e1);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
            endTransaction(resultStatus.isSuccessful());
        }

        //// EPILOGUE: A change notification is sent...
        notifyChange(rev, null);
        return rev;
    }

    /**
     * Given a revision, read its _attachments dictionary (if any), convert each attachment to a
     * CBLAttachmentInternal object, and return a dictionary mapping names->CBL_Attachments.
     */
    Map<String, CBLAttachmentInternal> getAttachmentsFromRevision(CBLRevisionInternal rev) throws CBLiteException {

        Map<String, Object> revAttachments = (Map<String, Object>) rev.getPropertyForKey("_attachments");
        if (revAttachments == null || revAttachments.size() == 0 || rev.isDeleted()) {
            return new HashMap<String, CBLAttachmentInternal>();
        }

        Map<String, CBLAttachmentInternal> attachments = new HashMap<String, CBLAttachmentInternal>();
        for (String name : revAttachments.keySet()) {
            Map<String, Object> attachInfo = (Map<String, Object>) revAttachments.get(name);
            String contentType = (String) attachInfo.get("content_type");
            CBLAttachmentInternal attachment = new CBLAttachmentInternal(name, contentType);
            String newContentBase64 = (String) attachInfo.get("data");
            if (newContentBase64 != null) {
                // If there's inline attachment data, decode and store it:
                byte[] newContents;
                try {
                    newContents = Base64.decode(newContentBase64);
                } catch (IOException e) {
                    throw new CBLiteException(e, CBLStatus.BAD_ENCODING);
                }
                attachment.setLength(newContents.length);
                CBLBlobKey outBlobKey = new CBLBlobKey();
                boolean storedBlob = getAttachments().storeBlob(newContents, outBlobKey);
                attachment.setBlobKey(outBlobKey);
                if (!storedBlob) {
                    throw new CBLiteException(CBLStatus.STATUS_ATTACHMENT_ERROR);
                }
            }
            else if (((Boolean)attachInfo.get("follows")).booleanValue() == true) {
                // "follows" means the uploader provided the attachment in a separate MIME part.
                // This means it's already been registered in _pendingAttachmentsByDigest;
                // I just need to look it up by its "digest" property and install it into the store:
                installAttachment(attachment, attachInfo);

            }
            else {
                // This item is just a stub; validate and skip it
                if (((Boolean)attachInfo.get("stub")).booleanValue() == false) {
                    throw new CBLiteException("Expected this attachment to be a stub", CBLStatus.BAD_ATTACHMENT);
                }
                int revPos = ((Integer)attachInfo.get("revpos")).intValue();
                if (revPos <= 0) {
                    throw new CBLiteException("Invalid revpos: " + revPos, CBLStatus.BAD_ATTACHMENT);
                }
                continue;
            }

            // Handle encoded attachment:
            String encodingStr = (String) attachInfo.get("encoding");
            if (encodingStr != null && encodingStr.length() > 0) {
                if (encodingStr.equalsIgnoreCase("gzip")) {
                    attachment.setEncoding(CBLAttachmentInternal.CBLAttachmentEncoding.CBLAttachmentEncodingGZIP);
                }
                else {
                    throw new CBLiteException("Unnkown encoding: " + encodingStr, CBLStatus.BAD_ENCODING);
                }
                attachment.setEncodedLength(attachment.getLength());
                attachment.setLength((Long)attachInfo.get("length"));
            }
            if (attachInfo.containsKey("revpos")) {
                attachment.setRevpos((Integer)attachInfo.get("revpos"));
            }
            else {
                attachment.setRevpos(1);
            }
            attachments.put(name, attachment);
        }

        return attachments;

    }

    /**
     * Inserts an already-existing revision replicated from a remote sqliteDb.
     *
     * It must already have a revision ID. This may create a conflict! The revision's history must be given; ancestor revision IDs that don't already exist locally will create phantom revisions with no content.
     */
    public void forceInsert(CBLRevisionInternal rev, List<String> revHistory, URL source) throws CBLiteException {

        String docId = rev.getDocId();
        String revId = rev.getRevId();
        if(!isValidDocumentId(docId) || (revId == null)) {
            throw new CBLiteException(CBLStatus.BAD_REQUEST);
        }

        int historyCount = 0;
        if (revHistory != null) {
            historyCount = revHistory.size();
        }
        if(historyCount == 0) {
            revHistory = new ArrayList<String>();
            revHistory.add(revId);
            historyCount = 1;
        } else if(!revHistory.get(0).equals(rev.getRevId())) {
            throw new CBLiteException(CBLStatus.BAD_REQUEST);
        }

        boolean success = false;
        beginTransaction();
        try {
            // First look up all locally-known revisions of this document:
            long docNumericID = getOrInsertDocNumericID(docId);
            CBLRevisionList localRevs = getAllRevisionsOfDocumentID(docId, docNumericID, false);
            if(localRevs == null) {
                throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
            }

            // Walk through the remote history in chronological order, matching each revision ID to
            // a local revision. When the list diverges, start creating blank local revisions to fill
            // in the local history:
            long sequence = 0;
            long localParentSequence = 0;
            for(int i = revHistory.size() - 1; i >= 0; --i) {
                revId = revHistory.get(i);
                CBLRevisionInternal localRev = localRevs.revWithDocIdAndRevId(docId, revId);
                if(localRev != null) {
                    // This revision is known locally. Remember its sequence as the parent of the next one:
                    sequence = localRev.getSequence();
                    assert(sequence > 0);
                    localParentSequence = sequence;
                }
                else {
                    // This revision isn't known, so add it:
                    CBLRevisionInternal newRev;
                    byte[] data = null;
                    boolean current = false;
                    if(i == 0) {
                        // Hey, this is the leaf revision we're inserting:
                       newRev = rev;
                       if(!rev.isDeleted()) {
                           data = encodeDocumentJSON(rev);
                           if(data == null) {
                               throw new CBLiteException(CBLStatus.BAD_REQUEST);
                           }
                       }
                       current = true;
                    }
                    else {
                        // It's an intermediate parent, so insert a stub:
                        newRev = new CBLRevisionInternal(docId, revId, false, this);
                    }

                    // Insert it:
                    sequence = insertRevision(newRev, docNumericID, sequence, current, data);

                    if(sequence <= 0) {
                        throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
                    }

                    if(i == 0) {
                        // Write any changed attachments for the new revision. As the parent sequence use
                        // the latest local revision (this is to copy attachments from):
                        Map<String, CBLAttachmentInternal> attachments = getAttachmentsFromRevision(rev);
                        if (attachments != null) {
                            processAttachmentsForRevision(attachments, rev, localParentSequence);
                            stubOutAttachmentsInRevision(attachments, rev);
                        }
                    }

                }
            }

            // Mark the latest local rev as no longer current:
            if(localParentSequence > 0 && localParentSequence != sequence) {
                ContentValues args = new ContentValues();
                args.put("current", 0);
                String[] whereArgs = { Long.toString(localParentSequence) };
                try {
                    sqliteDb.update("revs", args, "sequence=?", whereArgs);
                } catch (SQLException e) {
                    throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
                }
            }

            success = true;
        } catch(SQLException e) {
            endTransaction(success);
            throw new CBLiteException(CBLStatus.INTERNAL_SERVER_ERROR);
        } finally {
            endTransaction(success);
        }

        // Notify and return:
        notifyChange(rev, source);
    }

    /** VALIDATION **/

    /**
     * Defines or clears a named document validation function.
     * Before any change to the database, all registered validation functions are called and given a
     * chance to reject it. (This includes incoming changes from a pull replication.)
     */
    public void defineValidation(String name, CBLValidationBlock validationBlock) {
        if(validations == null) {
            validations = new HashMap<String, CBLValidationBlock>();
        }
        if (validationBlock != null) {
            validations.put(name, validationBlock);
        }
        else {
            validations.remove(name);
        }
    }

    /**
     * Returns the existing document validation function (block) registered with the given name.
     * Note that validations are not persistent -- you have to re-register them on every launch.
     */
    public CBLValidationBlock getValidation(String name) {
        CBLValidationBlock result = null;
        if(validations != null) {
            result = validations.get(name);
        }
        return result;
    }

    public void validateRevision(CBLRevisionInternal newRev, CBLRevisionInternal oldRev) throws CBLiteException {
        if(validations == null || validations.size() == 0) {
            return;
        }
        TDValidationContextImpl context = new TDValidationContextImpl(this, oldRev);
        for (String validationName : validations.keySet()) {
            CBLValidationBlock validation = getValidation(validationName);
            if(!validation.validate(newRev, context)) {
                throw new CBLiteException(context.getErrorType().getCode());
            }
        }
    }

    /*************************************************************************************************/
    /*** CBLDatabase+Replication                                                                    ***/
    /*************************************************************************************************/

    //TODO implement missing replication methods

    /**
     * Get all the replicators associated with this database.
     *
     * @return
     */
    public List<CBLReplicator> getAllReplications() {
        return activeReplicators;
    }

    /**
     * Creates a replication that will 'push' to a database at the given URL, or returns an existing
     * such replication if there already is one.
     *
     * @param remote
     * @return
     */
    public CBLReplicator push(URL remote) {
        return getActiveReplicator(remote, true);
    }

    /**
     * Creates a replication that will 'pull' from a database at the given URL, or returns an existing
     * such replication if there already is one.
     *
     * @param remote
     * @return
     */
    public CBLReplicator pull(URL remote) {
        return getActiveReplicator(remote, false);
    }

    /**
     * Creates a pair of replications to both pull and push to database at the given URL, or
     * returns existing replications if there are any.
     *
     * @param remote
     * @param exclusively - this param is ignored!  TODO: fix this
     * @return An array whose first element is the "pull" replication and second is the "push".
     */
    public List<CBLReplicator> replicate(URL remote, boolean exclusively) {
        CBLReplicator pull;
        CBLReplicator push;
        if (remote != null) {
            pull = pull(remote);
            push = push(remote);
            ArrayList<CBLReplicator> result = new ArrayList<CBLReplicator>();
            result.add(pull);
            result.add(push);
            return result;
        }
        return null;
    }

    public CBLReplicator getActiveReplicator(URL remote, boolean push) {
        if(activeReplicators != null) {
            for (CBLReplicator replicator : activeReplicators) {
                if(replicator.getRemote().equals(remote) && replicator.isPush() == push  && replicator.isRunning()) {
                    return replicator;
                }
            }
        }
        return null;
    }

    public CBLReplicator getReplicator(URL remote, boolean push, boolean continuous, ScheduledExecutorService workExecutor) {
        CBLReplicator replicator = getReplicator(remote, null, push, continuous, workExecutor);

    	return replicator;
    }
    
    public CBLReplicator getReplicator(String sessionId) {
    	if(activeReplicators != null) {
            for (CBLReplicator replicator : activeReplicators) {
                if(replicator.getSessionID().equals(sessionId)) {
                    return replicator;
                }
            }
        }
        return null;
    }



    public CBLReplicator getReplicator(URL remote, HttpClientFactory httpClientFactory, boolean push, boolean continuous, ScheduledExecutorService workExecutor) {
        CBLReplicator result = getActiveReplicator(remote, push);
        if(result != null) {
            return result;
        }
        result = push ? new CBLPusher(this, remote, continuous, httpClientFactory, workExecutor) : new CBLPuller(this, remote, continuous, httpClientFactory, workExecutor);

        if(activeReplicators == null) {
            activeReplicators = new ArrayList<CBLReplicator>();
        }
        activeReplicators.add(result);
        return result;
    }

    public String lastSequenceWithRemoteURL(URL url, boolean push) {
        Cursor cursor = null;
        String result = null;
        try {
            String[] args = { url.toExternalForm(), Integer.toString(push ? 1 : 0) };
            cursor = sqliteDb.rawQuery("SELECT last_sequence FROM replicators WHERE remote=? AND push=?", args);
            if(cursor.moveToFirst()) {
                result = cursor.getString(0);
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting last sequence", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public boolean setLastSequence(String lastSequence, URL url, boolean push) {
        ContentValues values = new ContentValues();
        values.put("remote", url.toExternalForm());
        values.put("push", push);
        values.put("last_sequence", lastSequence);
        long newId = sqliteDb.insertWithOnConflict("replicators", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return (newId == -1);
    }

    public static String quote(String string) {
        return string.replace("'", "''");
    }

    public static String joinQuotedObjects(List<Object> objects) {
        List<String> strings = new ArrayList<String>();
        for (Object object : objects) {
            strings.add(object != null ? object.toString() : null);
        }
        return joinQuoted(strings);
    }

    public static String joinQuoted(List<String> strings) {
        if(strings.size() == 0) {
            return "";
        }

        String result = "'";
        boolean first = true;
        for (String string : strings) {
            if(first) {
                first = false;
            }
            else {
                result = result + "','";
            }
            result = result + quote(string);
        }
        result = result + "'";

        return result;
    }

    public boolean findMissingRevisions(CBLRevisionList touchRevs) {
        if(touchRevs.size() == 0) {
            return true;
        }

        String quotedDocIds = joinQuoted(touchRevs.getAllDocIds());
        String quotedRevIds = joinQuoted(touchRevs.getAllRevIds());

        String sql = "SELECT docid, revid FROM revs, docs " +
                      "WHERE docid IN (" +
                      quotedDocIds +
                      ") AND revid in (" +
                      quotedRevIds + ")" +
                      " AND revs.doc_id == docs.doc_id";

        Cursor cursor = null;
        try {
            cursor = sqliteDb.rawQuery(sql, null);
            cursor.moveToFirst();
            while(!cursor.isAfterLast()) {
                CBLRevisionInternal rev = touchRevs.revWithDocIdAndRevId(cursor.getString(0), cursor.getString(1));

                if(rev != null) {
                    touchRevs.remove(rev);
                }

                cursor.moveToNext();
            }
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error finding missing revisions", e);
            return false;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
        return true;
    }

    /*************************************************************************************************/
    /*** CBLDatabase+LocalDocs                                                                      ***/
    /*************************************************************************************************/

    /**
     * Returns the contents of the local document with the given ID, or nil if none exists.
     */
    public Map<String, Object> getLocalDocument(String documentId) {
        return getLocalDocument(makeLocalDocumentId(documentId), null).getProperties();
    }

    static String makeLocalDocumentId(String documentId) {
        return String.format("_local/%s", documentId);
    }

    /**
     * Sets the contents of the local document with the given ID. Unlike CouchDB, no revision-ID
     * checking is done; the put always succeeds. If the properties dictionary is nil, the document
     * will be deleted.
     */
    public boolean putLocalDocument(Map<String, Object> properties, String id) throws CBLiteException {
        // TODO: there was some code in the iOS implementation equivalent that I did not know if needed
        CBLRevisionInternal prevRev = getLocalDocument(id, null);
        if (prevRev == null && properties == null) {
            return false;
        }
        return putLocalRevision(prevRev, prevRev.getRevId()) != null;
    }


    public CBLRevisionInternal putLocalRevision(CBLRevisionInternal revision, String prevRevID) throws CBLiteException  {
        String docID = revision.getDocId();
        if(!docID.startsWith("_local/")) {
            throw new CBLiteException(CBLStatus.BAD_REQUEST);
        }

        if(!revision.isDeleted()) {
            // PUT:
            byte[] json = encodeDocumentJSON(revision);
            String newRevID;
            if(prevRevID != null) {
                int generation = CBLRevisionInternal.generationFromRevID(prevRevID);
                if(generation == 0) {
                    throw new CBLiteException(CBLStatus.BAD_REQUEST);
                }
                newRevID = Integer.toString(++generation) + "-local";
                ContentValues values = new ContentValues();
                values.put("revid", newRevID);
                values.put("json", json);
                String[] whereArgs = { docID, prevRevID };
                try {
                    int rowsUpdated = sqliteDb.update("localdocs", values, "docid=? AND revid=?", whereArgs);
                    if(rowsUpdated == 0) {
                        throw new CBLiteException(CBLStatus.CONFLICT);
                    }
                } catch (SQLException e) {
                    throw new CBLiteException(e, CBLStatus.INTERNAL_SERVER_ERROR);
                }
            } else {
                newRevID = "1-local";
                ContentValues values = new ContentValues();
                values.put("docid", docID);
                values.put("revid", newRevID);
                values.put("json", json);
                try {
                    sqliteDb.insertWithOnConflict("localdocs", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                } catch (SQLException e) {
                    throw new CBLiteException(e, CBLStatus.INTERNAL_SERVER_ERROR);
                }
            }
            return revision.copyWithDocID(docID, newRevID);
        }
        else {
            // DELETE:
            deleteLocalDocument(docID, prevRevID);
            return revision;
        }
    }

    /**
     * Deletes the local document with the given ID.
     */
    public boolean deleteLocalDocument(String id) throws CBLiteException {
        CBLRevisionInternal prevRev = getLocalDocument(id, null);
        if (prevRev == null) {
            return false;
        }
        deleteLocalDocument(id, prevRev.getRevId());
        return true;
    }

    /**
     * Returns a query that matches all documents in the database.
     * This is like querying an imaginary view that emits every document's ID as a key.
     */
    public CBLQuery queryAllDocuments() {
        return new CBLQuery(this, (CBLView)null);
    }

    /**
     * Creates a one-shot query with the given map function. This is equivalent to creating an
     * anonymous CBLView and then deleting it immediately after querying it. It may be useful during
     * development, but in general this is inefficient if this map will be used more than once,
     * because the entire view has to be regenerated from scratch every time.
     */
    public CBLQuery slowQuery(CBLMapFunction map) {
        return new CBLQuery(this, map);
    }

    /**
     * Purges specific revisions, which deletes them completely from the local database _without_ adding a "tombstone" revision. It's as though they were never there.
     * This operation is described here: http://wiki.apache.org/couchdb/Purge_Documents
     * @param docsToRevs  A dictionary mapping document IDs to arrays of revision IDs.
     * @resultOn success will point to an NSDictionary with the same form as docsToRev, containing the doc/revision IDs that were actually removed.
     */
    Map<String, Object> purgeRevisions(final Map<String, List<String>> docsToRevs) {

        final Map<String, Object> result = new HashMap<String, Object>();
        runInTransaction(new CBLDatabaseFunction() {
            @Override
            public boolean performFunction() {
                for (String docID : docsToRevs.keySet()) {
                    long docNumericID = getDocNumericID(docID);
                    if (docNumericID == -1) {
                        continue; // no such document, skip it
                    }
                    List<String> revsPurged = null;
                    List<String> revIDs = (List<String>) docsToRevs.get(docID);
                    if (revIDs == null) {
                        return false;
                    } else if (revIDs.size() == 0) {
                        revsPurged = new ArrayList<String>();
                    } else if (revIDs.contains("*")) {
                        // Delete all revisions if magic "*" revision ID is given:
                        try {
                            String[] args = {Long.toString(docNumericID)};
                            sqliteDb.execSQL("DELETE FROM revs WHERE doc_id=?", args);
                        } catch (SQLException e) {
                            Log.e(CBLDatabase.TAG, "Error deleting revisions", e);
                            return false;
                        }
                        revsPurged.add("*");
                    } else {
                        // Iterate over all the revisions of the doc, in reverse sequence order.
                        // Keep track of all the sequences to delete, i.e. the given revs and ancestors,
                        // but not any non-given leaf revs or their ancestors.
                        Cursor cursor = null;

                        try {
                            String[] args = {Long.toString(docNumericID)};
                            String queryString = "SELECT revid, sequence, parent FROM revs WHERE doc_id=? ORDER BY sequence DESC";
                            cursor = sqliteDb.rawQuery(queryString, args);
                            if (!cursor.moveToFirst()) {
                                Log.w(CBLDatabase.TAG, "No results for query: " + queryString);
                                return false;
                            }

                            Set<Long> seqsToPurge = new HashSet<Long>();
                            Set<Long> seqsToKeep = new HashSet<Long>();
                            Set<String> revsToPurge = new HashSet<String>();
                            while (!cursor.isAfterLast()) {

                                String revID = cursor.getString(0);
                                long sequence = cursor.getLong(1);
                                long parent = cursor.getLong(2);
                                if (seqsToPurge.contains(sequence) || revIDs.contains(revID) && !seqsToKeep.contains(sequence)) {
                                    // Purge it and maybe its parent:
                                    seqsToPurge.add(sequence);
                                    revsToPurge.add(revID);
                                    if (parent > 0) {
                                        seqsToPurge.add(parent);
                                    }
                                } else {
                                    // Keep it and its parent:
                                    seqsToPurge.remove(sequence);
                                    revsToPurge.remove(revID);
                                    seqsToKeep.add(parent);
                                }

                                cursor.moveToNext();
                            }

                            seqsToPurge.removeAll(seqsToKeep);
                            Log.i(CBLDatabase.TAG, String.format("Purging doc '%s' revs (%s); asked for (%s)", docID, revsToPurge, revIDs));
                            if (seqsToPurge.size() > 0) {
                                // Now delete the sequences to be purged.
                                String seqsToPurgeList = TextUtils.join(",", seqsToPurge);
                                String sql = String.format("DELETE FROM revs WHERE sequence in (%s)", seqsToPurgeList);
                                try {
                                    sqliteDb.execSQL(sql);
                                } catch (SQLException e) {
                                    Log.e(CBLDatabase.TAG, "Error deleting revisions via: " + sql, e);
                                    return false;
                                }
                            }
                            revsPurged.addAll(revsToPurge);

                        } catch (SQLException e) {
                            Log.e(CBLDatabase.TAG, "Error getting revisions", e);
                            return false;
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }

                    }

                    result.put(docID, revsPurged);

                }

                return true;
            }
        });

        return result;

    }

    /**
     * Returns the currently registered filter compiler (nil by default).
     */
    public static CBLFilterCompiler getFilterCompiler() {
        return filterCompiler;
    }

    /**
     * Registers an object that can compile source code into executable filter blocks.
     */
    public static void setFilterCompiler(CBLFilterCompiler filterCompiler) {
        CBLDatabase.filterCompiler = filterCompiler;
    }

    protected boolean replaceUUIDs() {
        String query = "UPDATE INFO SET value='"+ CBLMisc.TDCreateUUID()+"' where key = 'privateUUID';";
        try {
            sqliteDb.execSQL(query);
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error updating UUIDs", e);
            return false;
        }
        query = "UPDATE INFO SET value='"+CBLMisc.TDCreateUUID()+"' where key = 'publicUUID';";
        try {
            sqliteDb.execSQL(query);
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error updating UUIDs", e);
            return false;
        }
        return true;
    }

    public CBLRevisionInternal getLocalDocument(String docID, String revID) {

        CBLRevisionInternal result = null;
        Cursor cursor = null;
        try {
            String[] args = { docID };
            cursor = sqliteDb.rawQuery("SELECT revid, json FROM localdocs WHERE docid=?", args);
            if(cursor.moveToFirst()) {
                String gotRevID = cursor.getString(0);
                if(revID != null && (!revID.equals(gotRevID))) {
                    return null;
                }
                byte[] json = cursor.getBlob(1);
                Map<String,Object> properties = null;
                try {
                    properties = CBLManager.getObjectMapper().readValue(json, Map.class);
                    properties.put("_id", docID);
                    properties.put("_rev", gotRevID);
                    result = new CBLRevisionInternal(docID, gotRevID, false, this);
                    result.setProperties(properties);
                } catch (Exception e) {
                    Log.w(CBLDatabase.TAG, "Error parsing local doc JSON", e);
                    return null;
                }

            }
            return result;
        } catch (SQLException e) {
            Log.e(CBLDatabase.TAG, "Error getting local document", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

    }

    public void deleteLocalDocument(String docID, String revID) throws CBLiteException {
        if(docID == null) {
            throw new CBLiteException(CBLStatus.BAD_REQUEST);
        }
        if(revID == null) {
            // Didn't specify a revision to delete: 404 or a 409, depending
            if (getLocalDocument(docID, null) != null) {
                throw new CBLiteException(CBLStatus.CONFLICT);
            }
            else {
                throw new CBLiteException(CBLStatus.NOT_FOUND);
            }
        }
        String[] whereArgs = { docID, revID };
        try {
            int rowsDeleted = sqliteDb.delete("localdocs", "docid=? AND revid=?", whereArgs);
            if(rowsDeleted == 0) {
                if (getLocalDocument(docID, null) != null) {
                    throw new CBLiteException(CBLStatus.CONFLICT);
                }
                else {
                    throw new CBLiteException(CBLStatus.NOT_FOUND);
                }
            }
        } catch (SQLException e) {
            throw new CBLiteException(e, CBLStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

class TDValidationContextImpl implements CBLValidationContext {

    private CBLDatabase database;
    private CBLRevisionInternal currentRevision;
    private CBLStatus errorType;
    private String errorMessage;

    public TDValidationContextImpl(CBLDatabase database, CBLRevisionInternal currentRevision) {
        this.database = database;
        this.currentRevision = currentRevision;
        this.errorType = new CBLStatus(CBLStatus.FORBIDDEN);
        this.errorMessage = "invalid document";
    }

    @Override
    public CBLRevisionInternal getCurrentRevision() throws CBLiteException {
        if(currentRevision != null) {
            database.loadRevisionBody(currentRevision, EnumSet.noneOf(TDContentOptions.class));
        }
        return currentRevision;
    }

    @Override
    public CBLStatus getErrorType() {
        return errorType;
    }

    @Override
    public void setErrorType(CBLStatus status) {
        this.errorType = status;
    }

    @Override
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public void setErrorMessage(String message) {
        this.errorMessage = message;
    }

}
