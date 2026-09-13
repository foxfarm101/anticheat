package dev.fox.anticheat.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** JNI is only a transport. No Minecraft objects or detection rules live here. */
public final class NativeBridge implements AutoCloseable{
    private final Thread owner = Thread.currentThread();
    private long handle;
    public static void load(File library) throws IOException{
        System.load(library.getCanonicalPath());
    }
    public NativeBridge(String configuration){
        handle = nCreate(configuration);
        if(handle == 0){ throw new IllegalStateException("Native engine was not created"); }
    }
    private void checkThread(){
        if(Thread.currentThread() != owner){
            throw new IllegalStateException("Native engine requires its owning thread");
        }
    }
    public String[] submit(ByteBuffer event){
        checkThread();
        if(handle == 0){ throw new IllegalStateException("Native engine is closed"); }
        if(!event.isDirect() || event.position() != 0){
            throw new IllegalArgumentException("Expected flipped direct event buffer");
        }
        return nSubmit(handle, event, event.remaining());
    }
    @Override
    public void close(){
        checkThread();
        if(handle != 0){
            nDestroy(handle);
            handle = 0;
        }
    }
    private static native long nCreate(String configuration);
    private static native void nDestroy(long handle);
    private static native String[] nSubmit(long handle, ByteBuffer event, int size);
}
