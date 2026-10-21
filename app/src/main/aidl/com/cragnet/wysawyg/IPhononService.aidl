package com.cragnet.wysawyg;
import android.os.ParcelFileDescriptor;
import com.cragnet.wysawyg.IPhononCallback;
interface IPhononService {
    void transcribe(long requestId, in ParcelFileDescriptor wav, IPhononCallback callback);
}
