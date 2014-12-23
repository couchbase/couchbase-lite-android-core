package com.couchbase.lite.util;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Status;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.storage.Cursor;
import com.couchbase.lite.storage.SQLException;
import com.couchbase.lite.storage.SQLiteStorageEngine;

import org.apache.http.StatusLine;
import org.apache.http.client.HttpResponseException;

public class Utils {

    /**
     * Like equals, but works even if either/both are null
     *
     * @param obj1 object1 being compared
     * @param obj2 object2 being compared
     * @return true if both are non-null and obj1.equals(obj2), or true if both are null.
     *         otherwise return false.
     */
    public static boolean isEqual(Object obj1, Object obj2) {
        if (obj1 != null) {
            return (obj2 != null) && obj1.equals(obj2);
        } else {
            return obj2 == null;
        }
    }

    /**
     * in CBLMisc.m
     * BOOL CBLIsPermanentError( NSError* error )
     */
    public static boolean isPermanentError(Throwable throwable){
        if (throwable instanceof CouchbaseLiteException) {
            CouchbaseLiteException e = (CouchbaseLiteException) throwable;
            return isPermanentError(e.getCBLStatus().getCode());
        } else if (throwable instanceof HttpResponseException) {
            HttpResponseException e = (HttpResponseException) throwable;
            return isPermanentError(e.getStatusCode());
        } else {
            return false;
        }
    }
    /**
     * in CBLMisc.m
     * BOOL CBLIsPermanentError( NSError* error )
     */
    public static boolean isPermanentError(int code){
        // TODO: make sure if 406 is acceptable error
        // 406 - in Test cases, server return 406 because of CouchDB API
        //       http://docs.couchdb.org/en/latest/api/database/bulk-api.html
        //       GET /{db}/_all_docs or POST /{db}/_all_docs
        return (code >= 400 && code <= 405) || (code >= 407 && code <= 499);
    }


    public static boolean isTransientError(Throwable throwable) {

        if (throwable instanceof CouchbaseLiteException) {
            CouchbaseLiteException e = (CouchbaseLiteException) throwable;
            return isTransientError(e.getCBLStatus().getCode());
        } else if (throwable instanceof HttpResponseException) {
            HttpResponseException e = (HttpResponseException) throwable;
            return isTransientError(e.getStatusCode());
        } else {
            return false;
        }

    }


    public static boolean isTransientError(StatusLine status) {

        // TODO: in ios implementation, it considers others errors
        /*
            if ($equal(domain, NSURLErrorDomain)) {
        return code == NSURLErrorTimedOut || code == NSURLErrorCannotConnectToHost
                                          || code == NSURLErrorNetworkConnectionLost;
         */

        return isTransientError(status.getStatusCode());

    }

    public static boolean isTransientError(int statusCode) {

        if (statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return true;
        }
        return false;

    }

    public static byte[] byteArrayResultForQuery(SQLiteStorageEngine database, String query, String[] args) throws SQLException {
        byte[] result = null;
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(query, args);
            if (cursor.moveToNext()) {
                result = cursor.getBlob(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    /** cribbed from http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java */
    final protected static char[] hexArray = "0123456789abcdef".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static void assertNotNull(Object o, String errMsg) {
        if (o == null) {
            throw new IllegalArgumentException(errMsg);
        }
    }

    @InterfaceAudience.Private
    public static boolean is404(Throwable e) {
        if (e instanceof HttpResponseException) {
            return ((HttpResponseException) e).getStatusCode() == 404;
        }
        return false;
    }

    @InterfaceAudience.Private
    public static int getStatusFromError(Throwable t) {
        if (t instanceof CouchbaseLiteException) {
            CouchbaseLiteException couchbaseLiteException = (CouchbaseLiteException) t;
            return couchbaseLiteException.getCBLStatus().getCode();
        } else if (t instanceof HttpResponseException) {
            HttpResponseException responseException = (HttpResponseException) t;
            return responseException.getStatusCode();
        }
        return Status.UNKNOWN;
    }

    public static String shortenString(String orig, int maxLength) {
        if (orig == null || orig.length() <= maxLength) {
            return orig;
        }
        return orig.substring(0, maxLength);
    }

}
