/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.arch.background.workmanager.impl.background.firebase;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.arch.background.workmanager.impl.Scheduler;
import android.arch.background.workmanager.impl.logger.Logger;
import android.arch.background.workmanager.impl.model.WorkSpec;
import android.arch.background.workmanager.impl.utils.IdGenerator;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RestrictTo;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.HashSet;
import java.util.Set;

/**
 * A class that schedules work using {@link FirebaseJobDispatcher}.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class FirebaseJobScheduler implements Scheduler {

    private static final String TAG = "FirebaseJobScheduler";

    private Context mAppContext;
    private FirebaseJobDispatcher mDispatcher;
    private FirebaseJobConverter mJobConverter;
    private IdGenerator mIdGenerator;
    private AlarmManager mAlarmManager;
    private Set<String> mCancelledIds;

    public FirebaseJobScheduler(Context context) {
        mAppContext = context.getApplicationContext();
        boolean isPlayServicesAvailable = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(mAppContext) == ConnectionResult.SUCCESS;
        if (!isPlayServicesAvailable) {
            throw new IllegalStateException("Google Play Services not available");
        }
        mDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(mAppContext));
        mJobConverter = new FirebaseJobConverter(mDispatcher);
        mCancelledIds = new HashSet<>();
    }

    @Override
    public void schedule(WorkSpec... workSpecs) {
        for (WorkSpec workSpec : workSpecs) {
            if (workSpec.calculateNextRunTime() > System.currentTimeMillis()) {
                scheduleLater(workSpec);
            } else {
                scheduleNow(workSpec);
            }
        }
    }

    @Override
    public void cancel(@NonNull String workSpecId) {
        mCancelledIds.add(workSpecId);
        mDispatcher.cancel(workSpecId);
    }

    @Override
    public boolean isCancelled(@NonNull String workSpecId) {
        return mCancelledIds.contains(workSpecId);
    }

    void scheduleNow(WorkSpec workSpec) {
        Job job = mJobConverter.convert(workSpec);
        Logger.debug(TAG, "Scheduling work now, ID: %s", workSpec.getId());
        int result = mDispatcher.schedule(job);
        if (result != FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS) {
            Logger.error(TAG, "Schedule failed. Result = %s", result);
        }
    }

    private void scheduleLater(WorkSpec workSpec) {
        if (mAlarmManager == null) {
            mAlarmManager = (AlarmManager) mAppContext.getSystemService(Context.ALARM_SERVICE);
        }
        if (mIdGenerator == null) {
            mIdGenerator = new IdGenerator(mAppContext);
        }
        Logger.debug(TAG, "Scheduling work later, ID: %s", workSpec.getId());
        PendingIntent pendingIntent = createScheduleLaterPendingIntent(workSpec);

        // This wakes up the device at exactly the next run time.
        // A wakeup is necessary because the device could be sleeping when this alarm is fired.
        long triggerAtMillis = workSpec.calculateNextRunTime();
        if (Build.VERSION.SDK_INT >= 19) {
            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    private PendingIntent createScheduleLaterPendingIntent(WorkSpec workSpec) {
        Intent intent = new Intent(mAppContext, FirebaseDelayedJobAlarmReceiver.class);
        intent.putExtra(FirebaseDelayedJobAlarmReceiver.WORKSPEC_ID_KEY, workSpec.getId());
        int requestCode = mIdGenerator.nextFirebaseAlarmId();
        return PendingIntent.getBroadcast(mAppContext, requestCode, intent, 0);
    }
}
