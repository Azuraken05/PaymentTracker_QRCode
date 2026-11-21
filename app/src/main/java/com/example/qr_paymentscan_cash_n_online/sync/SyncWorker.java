package com.example.qr_paymentscan_cash_n_online.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class SyncWorker extends Worker {

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean success = FirebaseSyncManager.performBlockingCloudSync(getApplicationContext());
        return success ? Result.success() : Result.retry();
    }
}
