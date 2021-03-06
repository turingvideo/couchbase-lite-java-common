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

package com.couchbase.lite.internal.core;

import android.support.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.support.Log;


// Class has package protected static factory methods
@SuppressWarnings("PMD.ClassWithOnlyPrivateConstructorsShouldBeFinal")
public class C4DocumentObserver extends C4NativePeer {
    //-------------------------------------------------------------------------
    // Static Variables
    //-------------------------------------------------------------------------

    // Long: handle of C4DatabaseObserver native address
    // C4DocumentObserver: Java class holds handle
    private static final Map<Long, C4DocumentObserver> REVERSE_LOOKUP_TABLE
        = Collections.synchronizedMap(new HashMap<>());

    //-------------------------------------------------------------------------
    // Static Factory Methods
    //-------------------------------------------------------------------------

    static C4DocumentObserver newObserver(long db, String docID, C4DocumentObserverListener listener, Object context) {
        final C4DocumentObserver observer = new C4DocumentObserver(db, docID, listener, context);
        REVERSE_LOOKUP_TABLE.put(observer.getPeer(), observer);
        return observer;
    }

    //-------------------------------------------------------------------------
    // JNI callback methods
    //-------------------------------------------------------------------------

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    static void callback(long handle, @Nullable String docID, long sequence) {
        Log.d(
            LogDomain.DATABASE,
            "C4DocumentObserver.callback @" + Long.toHexString(handle) + " (" + sequence + "): " + docID);

        final C4DocumentObserver obs = REVERSE_LOOKUP_TABLE.get(handle);
        if (obs == null) { return; }

        final C4DocumentObserverListener listener = obs.listener;
        if (listener == null) { return; }

        listener.callback(obs, docID, sequence, obs.context);
    }


    //-------------------------------------------------------------------------
    // Member Variables
    //-------------------------------------------------------------------------

    private final C4DocumentObserverListener listener;
    private final Object context;

    //-------------------------------------------------------------------------
    // Constructor
    //-------------------------------------------------------------------------

    private C4DocumentObserver(long db, String docID, C4DocumentObserverListener listener, Object context) {
        super(create(db, docID));
        this.listener = listener;
        this.context = context;
    }

    //-------------------------------------------------------------------------
    // public methods
    //-------------------------------------------------------------------------

    public void close() {
        final long handle = getPeer();
        if (handle == 0L) { return; }
        REVERSE_LOOKUP_TABLE.remove(handle);
    }

    public void free() {
        final long handle = getPeerAndClear();
        if (handle == 0L) { return; }

        REVERSE_LOOKUP_TABLE.remove(handle);

        free(handle);
    }

    // Note: the reference in the REVERSE_LOOKUP_TABLE must already be gone, or we wouldn't be here...
    @SuppressWarnings("NoFinalizer")
    @Override
    protected void finalize() throws Throwable {
        try {
            final long handle = getPeerAndClear();
            if (handle == 0) { return; }

            free(handle);
        }
        finally {
            super.finalize();
        }
    }

    //-------------------------------------------------------------------------
    // native methods
    //-------------------------------------------------------------------------

    private static native long create(long db, String docID);

    /**
     * Free C4DocumentObserver* instance
     *
     * @param c4observer (C4DocumentObserver*)
     */
    private static native void free(long c4observer);
}
