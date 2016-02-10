package com.couchbase.lite.util;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Status;
import com.couchbase.lite.internal.InterfaceAudience;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpResponseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Utils {

    public static int DEFAULT_TIME_TO_WAIT_4_SHUTDOWN = 20;
    public static int DEFAULT_TIME_TO_WAIT_4_SHUTDOWNNOW = 20;

    /**
     * Like equals, but works even if either/both are null
     *
     * @param obj1 object1 being compared
     * @param obj2 object2 being compared
     * @return true if both are non-null and obj1.equals(obj2), or true if both are null.
     * otherwise return false.
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
    public static boolean isPermanentError(Throwable throwable) {
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

    public static boolean isPermanentError(int code) {
        // 406 - in Test cases, server return 406 because of CouchDB API
        //       http://docs.couchdb.org/en/latest/api/database/bulk-api.html
        //       GET /{db}/_all_docs or POST /{db}/_all_docs
        return (code >= 400 && code <= 405) || (code >= 407 && code <= 499);
    }

    /**
     * in CBLMisc.m
     * BOOL CBLMayBeTransientError( NSError* error )
     */
    public static boolean isTransientError(Throwable throwable) {
        if (throwable instanceof CouchbaseLiteException) {
            CouchbaseLiteException e = (CouchbaseLiteException) throwable;
            return isTransientError(e.getCBLStatus().getCode());
        } else if (throwable instanceof HttpResponseException) {
            HttpResponseException e = (HttpResponseException) throwable;
            return isTransientError(e.getStatusCode());
        }
        // connection and socket timeouts => transient error
        else if (throwable instanceof java.net.SocketTimeoutException){
            return true;
        } else {
            return false;
        }
    }

    public static boolean isTransientError(StatusLine status) {
        // TODO: in ios implementation, it considers others errors
        //if ($equal(domain, NSURLErrorDomain)) {
        //    return code == NSURLErrorTimedOut
        //            || code == NSURLErrorCannotConnectToHost
        //            || code == NSURLErrorNetworkConnectionLost;

        return isTransientError(status.getStatusCode());
    }

    public static boolean isTransientError(int statusCode) {
        if (statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return true;
        }
        return false;
    }

    public static boolean isDocumentError(Throwable throwable) {

        if (throwable instanceof CouchbaseLiteException) {
            CouchbaseLiteException e = (CouchbaseLiteException) throwable;
            return isDocumentError(e.getCBLStatus().getCode());
        } else if (throwable instanceof HttpResponseException) {
            HttpResponseException e = (HttpResponseException) throwable;
            return isDocumentError(e.getStatusCode());
        } else {
            return false;
        }
    }

    public static boolean isDocumentError(int statusCode) {
        return (statusCode == Status.NOT_FOUND || statusCode == Status.FORBIDDEN || statusCode == Status.GONE) ? true : false;
    }

    /**
     * cribbed from http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java
     */
    final protected static char[] hexArray = "0123456789abcdef".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
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

    // check if contentEncoding is gzip
    public static boolean isGzip(HttpEntity entity) {
        return isGzip(entity.getContentEncoding());
    }

    // check if contentEncoding is gzip
    public static boolean isGzip(Header contentEncoding) {
        return contentEncoding != null && isGzip(contentEncoding.getValue());
    }

    // check if contentEncoding is gzip
    public static boolean isGzip(String contentEncoding) {
        return contentEncoding != null && contentEncoding.contains("gzip");
    }

    // to gzip
    public static byte[] compressByGzip(byte[] sourceBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            GZIPOutputStream gzip = new GZIPOutputStream(out);
            try {
                gzip.write(sourceBytes);
            } catch (IOException ex) {
                return null;
            } finally {
                try {
                    gzip.close();
                } catch (IOException ex) {
                }
            }
        }catch (IOException e){
            return null;
        } finally {
            try {
                out.close();
            } catch (IOException ex) {
            }
        }
        return out.toByteArray();
    }

    // from gzip
    public static int CHUNK_SIZE = 8192; // 1024 * 8

    public static byte[] decompressByGzip(byte[] sourceBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[CHUNK_SIZE];
            ByteArrayInputStream in = new ByteArrayInputStream(sourceBytes);
            try {
                GZIPInputStream gzip = new GZIPInputStream(in);
                try {
                    int len = 0;
                    while ((len = gzip.read(buffer, 0, CHUNK_SIZE)) != -1) {
                        out.write(buffer, 0, len);
                    }
                } finally {
                    try {
                        gzip.close();
                    } catch (IOException e) {
                    }
                }
            } catch (IOException ex) {
                Log.w(Log.TAG, "Failed to decompress gzipped data: " + ex.getMessage());
                return null;
            } finally {
                try {
                    in.close();
                } catch (IOException ex) {
                }
            }
        }finally {
            try {
                out.close();
            } catch (IOException ex) {
            }
        }
        return out.toByteArray();
    }

    public static Map<String, String> headersToMap(Header[] headers) {
        Map<String, String> map = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].getName(), headers[i].getValue());
        }
        return map;
    }

    /**
     * The following method shuts down an ExecutorService in two phases,
     * first by calling shutdown to reject incoming tasks, and then calling shutdownNow,
     * if necessary, to cancel any lingering tasks:
     * http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/ExecutorService.html
     *
     * @param timeToWait4ShutDown - Seconds
     * @param timeToWait4ShutDownNow - Seconds
     */
    public static void shutdownAndAwaitTermination(ExecutorService pool,
                                                   long timeToWait4ShutDown,
                                                   long timeToWait4ShutDownNow) {
        synchronized (pool) {
            // Disable new tasks from being submitted
            pool.shutdown();
        }
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(timeToWait4ShutDown, TimeUnit.SECONDS)) {
                synchronized (pool) {
                    pool.shutdownNow(); // Cancel currently executing tasks
                }
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(timeToWait4ShutDownNow, TimeUnit.SECONDS)) {
                    Log.e(Log.TAG_DATABASE, "Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            synchronized (pool) {
                pool.shutdownNow();
            }
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        shutdownAndAwaitTermination(pool,
                DEFAULT_TIME_TO_WAIT_4_SHUTDOWN,
                DEFAULT_TIME_TO_WAIT_4_SHUTDOWNNOW);
    }
}