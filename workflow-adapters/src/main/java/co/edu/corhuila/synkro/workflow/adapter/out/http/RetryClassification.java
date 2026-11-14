package co.edu.corhuila.synkro.workflow.adapter.out.http;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.Set;

/**
 * ADR-015 retry categories, kept pure so classification is testable without a network.
 * Approved: 429, 500, 502, 503, 504 and transport I/O or request timeouts that mean the exchange did not complete.
 * Not approved: 408 and every other status; TLS, DNS/address/configuration, protocol, size and unclassified I/O failures.
 */
final class RetryClassification {
    private static final Set<Integer> TRANSIENT_STATUSES=Set.of(429,500,502,503,504);
    private RetryClassification(){}
    static boolean transientStatus(int status){return TRANSIENT_STATUSES.contains(status);}
    /** Allow-list only: an arbitrary IOException is not evidence of a transport fault. */
    static boolean transientIo(Throwable failure) {
        var visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        while(failure!=null && visited.add(failure)) {
            if(failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException
                || failure instanceof EOFException || failure instanceof ClosedChannelException
                || (failure instanceof SocketException && !(failure instanceof java.net.BindException)
                    && !(failure instanceof java.net.NoRouteToHostException) && !(failure instanceof java.net.PortUnreachableException)))return true;
            // HttpClient wraps EOF/connection errors in plain IOException; arbitrary subclasses and programming failures stay local.
            if(failure.getClass()!=java.io.IOException.class)return false;
            failure=failure.getCause();
        }
        return false;
    }
}
