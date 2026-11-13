package android.hardware.display;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IVirtualDisplayCallback extends IInterface {
    abstract class Stub extends Binder implements IVirtualDisplayCallback {
        public static IVirtualDisplayCallback asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onPaused() {}

        @Override
        public void onResumed() {}

        @Override
        public void onStopped() {}
    }

    void onPaused();
    void onResumed();
    void onStopped();
}
