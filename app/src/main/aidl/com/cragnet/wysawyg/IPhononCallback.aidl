package com.cragnet.wysawyg;
import android.os.ParcelFileDescriptor;
interface IPhononCallback {
    void onResult(long requestId, in ParcelFileDescriptor utf8, String error);
}
