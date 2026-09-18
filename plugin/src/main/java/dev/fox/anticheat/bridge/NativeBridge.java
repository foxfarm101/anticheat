/**
 * NativeBridge.java is the Java-side interface to the native C++ detection engine.
 */

package dev.fox.anticheat.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class NativeBridge implements AutoCloseable{
    private final Thread owner = Thread.currentThread();
    private long handle;

    // Load the compiled native library into the JVM running Spigot.
    public static void load(File library) throws IOException{
        System.load(library.getCanonicalPath());
    }

    // Create an engine and retain its checked native handle, not a raw pointer.
    public NativeBridge(String configuration){
        handle = nCreate(configuration);

        if(handle == 0)
            throw new IllegalStateException("Native engine was not created");
    }

    private void checkThread(){
        if(Thread.currentThread() != owner)
            throw new IllegalStateException("Native engine requires its owning thread");
    }

    // Send one serialized observation and return Findings encoded as JSON strings.
    public String[] submit(ByteBuffer event){
        checkThread();

        if(handle == 0)
            throw new IllegalStateException("Native engine is closed");

        if(event == null || !event.isDirect() || event.position() != 0)
            throw new IllegalArgumentException("Expected flipped direct event buffer");

        return nSubmit(
            handle,
            event,
            event.remaining()
        );
    }

    // Destroy this engine once; repeated close calls are harmless.
    @Override
    public void close(){
        checkThread();

        if(handle != 0){
            nDestroy(handle);
            handle = 0;
        }
    }

    // Implemented by bridge/jni.cpp; JNI generates their C++ declarations.
    private static native long nCreate(String configuration);
    private static native void nDestroy(long handle);
    private static native String[] nSubmit(long handle, ByteBuffer event, int size);
}
