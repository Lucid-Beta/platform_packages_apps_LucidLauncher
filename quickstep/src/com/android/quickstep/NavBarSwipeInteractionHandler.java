/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.quickstep;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.RectEvaluator;
import android.annotation.TargetApi;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.support.annotation.UiThread;
import android.view.View;
import android.view.ViewTreeObserver.OnPreDrawListener;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Hotseat;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.states.InternalStateHandler;
import com.android.launcher3.uioverrides.RecentsViewStateController;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.views.AllAppsScrim;
import com.android.systemui.shared.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.Task.TaskKey;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.WindowManagerWrapper;

@TargetApi(Build.VERSION_CODES.O)
public class NavBarSwipeInteractionHandler extends InternalStateHandler {

    private static final int STATE_LAUNCHER_READY = 1 << 0;
    private static final int STATE_ACTIVITY_MULTIPLIER_COMPLETE = 1 << 4;
    private static final int STATE_SCALED_SNAPSHOT_RECENTS = 1 << 5;
    private static final int STATE_SCALED_SNAPSHOT_APP = 1 << 6;

    private static final long RECENTS_VIEW_VISIBILITY_DURATION = 150;
    private static final long MAX_SWIPE_DURATION = 200;
    private static final long MIN_SWIPE_DURATION = 80;
    private static final int QUICK_SWITCH_SNAP_DURATION = 120;

    // Ideal velocity for a smooth transition
    private static final float PIXEL_PER_MS = 2f;

    private static final float MIN_PROGRESS_FOR_OVERVIEW = 0.5f;

    private final Rect mStableInsets = new Rect();
    private final Rect mSourceRect = new Rect();
    private final Rect mTargetRect = new Rect();
    private final Rect mCurrentRect = new Rect();
    private final RectEvaluator mRectEvaluator = new RectEvaluator(mCurrentRect);

    // Shift in the range of [0, 1].
    // 0 => preview snapShot is completely visible, and hotseat is completely translated down
    // 1 => preview snapShot is completely aligned with the recents view and hotseat is completely
    // visible.
    private final AnimatedFloat mCurrentShift = new AnimatedFloat(this::updateFinalShift);

    // Activity multiplier in the range of [0, 1]. When the activity becomes visible, this is
    // animated to 1, so allow for a smooth transition.
    private final AnimatedFloat mActivityMultiplier = new AnimatedFloat(this::updateFinalShift);

    private final Task mRunningTask;
    private final Context mContext;

    private final MultiStateCallback mStateCallback;

    private Launcher mLauncher;
    private SnapshotDragView mDragView;
    private RecentsView mRecentsView;
    private RecentsViewStateController mStateController;
    private QuickScrubController mQuickScrubController;
    private Hotseat mHotseat;
    private AllAppsScrim mAllAppsScrim;

    private boolean mLauncherReady;
    private boolean mTouchEndHandled;
    private float mCurrentDisplacement;
    private @TouchInteractionService.InteractionType int mInteractionType;
    private boolean mStartedQuickScrubFromHome;

    private Bitmap mTaskSnapshot;

    NavBarSwipeInteractionHandler(RunningTaskInfo runningTaskInfo, Context context,
            @TouchInteractionService.InteractionType int interactionType) {
        // TODO: We need a better way for this
        TaskKey taskKey = new TaskKey(runningTaskInfo.id, 0, null, UserHandle.myUserId(), 0);
        mRunningTask = new Task(taskKey, null, null, "", "", Color.BLACK, Color.BLACK,
                true, false, false, false, null, 0, null, false);

        mContext = context;
        mInteractionType = interactionType;
        WindowManagerWrapper.getInstance().getStableInsets(mStableInsets);

        DeviceProfile dp = LauncherAppState.getIDP(mContext).getDeviceProfile(mContext);
        // TODO: If in multi window mode, dp = dp.getMultiWindowProfile()
        dp = dp.copy(mContext);
        // TODO: Use different insets for multi-window mode
        dp.updateInsets(mStableInsets);
        RecentsView.getPageRect(dp, mContext, mTargetRect);
        mSourceRect.set(0, 0, dp.widthPx - mStableInsets.left - mStableInsets.right,
                dp.heightPx - mStableInsets.top - mStableInsets.bottom);

        // Build the state callback
        mStateCallback = new MultiStateCallback();
        mStateCallback.addCallback(STATE_LAUNCHER_READY, this::onLauncherReady);
        mStateCallback.addCallback(STATE_SCALED_SNAPSHOT_APP, this::resumeLastTask);
        mStateCallback.addCallback(
                STATE_SCALED_SNAPSHOT_RECENTS | STATE_ACTIVITY_MULTIPLIER_COMPLETE,
                this::onAnimationToLauncherComplete);
        mStateCallback.addCallback(STATE_LAUNCHER_READY | STATE_SCALED_SNAPSHOT_APP,
                this::cleanupLauncher);
    }

    private void onLauncherReady() {
        mLauncherReady = true;
        executeFrameUpdate();

        long duration = Math.min(MAX_SWIPE_DURATION,
                Math.max((long) (-mCurrentDisplacement / PIXEL_PER_MS), MIN_SWIPE_DURATION));
        if (mCurrentShift.getCurrentAnimation() != null) {
            ObjectAnimator anim = mCurrentShift.getCurrentAnimation();
            long theirDuration = anim.getDuration() - anim.getCurrentPlayTime();

            // TODO: Find a better heuristic
            duration = (duration + theirDuration) / 2;
        }
        ObjectAnimator anim = mActivityMultiplier.animateToValue(1)
                .setDuration(duration);
        anim.addListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationSuccess(Animator animator) {
                mStateCallback.setState(STATE_ACTIVITY_MULTIPLIER_COMPLETE);
            }
        });
        anim.start();
    }

    public void setTaskSnapshot(Bitmap taskSnapshot) {
        mTaskSnapshot = taskSnapshot;
    }

    @Override
    public void onLauncherResume() {
        TraceHelper.partitionSection("TouchInt", "Launcher On resume");
        mDragView.getViewTreeObserver().addOnPreDrawListener(new OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mDragView.getViewTreeObserver().removeOnPreDrawListener(this);
                mStateCallback.setState(STATE_LAUNCHER_READY);
                TraceHelper.partitionSection("TouchInt", "Launcher drawn");
                return true;
            }
        });
    }

    @Override
    protected void init(Launcher launcher, boolean alreadyOnHome) {
        mLauncher = launcher;
        mRecentsView = launcher.getOverviewPanel();
        mRecentsView.showTask(mRunningTask);
        mStateController = mRecentsView.getStateController();
        mHotseat = mLauncher.getHotseat();
        mAllAppsScrim = mLauncher.findViewById(R.id.all_apps_scrim);

        AbstractFloatingView.closeAllOpenViews(mLauncher, alreadyOnHome);
        mLauncher.getStateManager().goToState(LauncherState.OVERVIEW, alreadyOnHome);

        mDragView = new SnapshotDragView(mLauncher, mTaskSnapshot);
        mLauncher.getDragLayer().addView(mDragView);
        mDragView.setPivotX(0);
        mDragView.setPivotY(0);

        boolean interactionIsQuick
                = mInteractionType == TouchInteractionService.INTERACTION_QUICK_SCRUB
                || mInteractionType == TouchInteractionService.INTERACTION_QUICK_SWITCH;
        mStartedQuickScrubFromHome = alreadyOnHome && interactionIsQuick;
        if (interactionIsQuick) {
            mQuickScrubController = mRecentsView.getQuickScrubController();
            mQuickScrubController.onQuickScrubStart(mStartedQuickScrubFromHome);
            animateToProgress(1f, MAX_SWIPE_DURATION);
            if (mStartedQuickScrubFromHome) {
                mDragView.setVisibility(View.INVISIBLE);
            }
        }

        // Optimization
        if (!mLauncher.getDeviceProfile().isVerticalBarLayout()) {
            // All-apps search box is visible in vertical bar layout.
            mLauncher.getAppsView().setVisibility(View.GONE);
        }
        TraceHelper.partitionSection("TouchInt", "Launcher on new intent");
    }

    @UiThread
    public void updateDisplacement(float displacement) {
        mCurrentDisplacement = displacement;
        executeFrameUpdate();
    }

    private void executeFrameUpdate() {
        if (mLauncherReady) {
            final float displacement = -mCurrentDisplacement;
            int hotseatSize = getHotseatSize();
            float translation = Utilities.boundToRange(displacement, 0, hotseatSize);
            float shift = hotseatSize == 0 ? 0 : translation / hotseatSize;
            mCurrentShift.updateValue(shift);
        }
    }

    @UiThread
    private void updateFinalShift() {
        if (!mLauncherReady || mStartedQuickScrubFromHome) {
            return;
        }

        float shift = mCurrentShift.value * mActivityMultiplier.value;
        int hotseatSize = getHotseatSize();

        float hotseatTranslation = (1 - shift) * hotseatSize;
        mHotseat.setTranslationY(hotseatTranslation);
        mAllAppsScrim.setTranslationY(hotseatTranslation);

        mRectEvaluator.evaluate(shift, mSourceRect, mTargetRect);

        float scale = (float) mCurrentRect.width() / mSourceRect.width();
        mDragView.setTranslationX(mCurrentRect.left - mStableInsets.left * scale * shift);
        mDragView.setTranslationY(mCurrentRect.top - mStableInsets.top * scale * shift);
        mDragView.setScaleX(scale);
        mDragView.setScaleY(scale);
        //  TODO: mDragView.getViewBounds().setClipLeft((int) (mStableInsets.left * shift));
        mDragView.getViewBounds().setClipTop((int) (mStableInsets.top * shift));
        // TODO: mDragView.getViewBounds().setClipRight((int) (mStableInsets.right * shift));
        mDragView.getViewBounds().setClipBottom((int) (mStableInsets.bottom * shift));
    }

    private int getHotseatSize() {
        return mLauncher.getDeviceProfile().isVerticalBarLayout()
                ? mHotseat.getWidth() : mHotseat.getHeight();
    }

    @UiThread
    public void endTouch(float endVelocity) {
        if (mTouchEndHandled) {
            return;
        }
        mTouchEndHandled = true;

        Resources res = mContext.getResources();
        float flingThreshold = res.getDimension(R.dimen.quickstep_fling_threshold_velocity);
        boolean isFling = Math.abs(endVelocity) > flingThreshold;

        long duration = MAX_SWIPE_DURATION;
        final float endShift;
        if (!isFling) {
            endShift = mCurrentShift.value >= MIN_PROGRESS_FOR_OVERVIEW ? 1 : 0;
        } else {
            endShift = endVelocity < 0 ? 1 : 0;
            float minFlingVelocity = res.getDimension(R.dimen.quickstep_fling_min_velocity);
            if (Math.abs(endVelocity) > minFlingVelocity && mLauncherReady) {
                float distanceToTravel = (endShift - mCurrentShift.value) * getHotseatSize();

                // we want the page's snap velocity to approximately match the velocity at
                // which the user flings, so we scale the duration by a value near to the
                // derivative of the scroll interpolator at zero, ie. 5.
                duration = 5 * Math.round(1000 * Math.abs(distanceToTravel / endVelocity));
            }
        }

        animateToProgress(endShift, duration);
    }

    /** Animates to the given progress, where 0 is the current app and 1 is overview. */
    private void animateToProgress(float progress, long duration) {
        ObjectAnimator anim = mCurrentShift.animateToValue(progress).setDuration(duration);
        anim.setInterpolator(Interpolators.SCROLL);
        anim.addListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationSuccess(Animator animator) {
                mStateCallback.setState((Float.compare(mCurrentShift.value, 0) == 0)
                        ? STATE_SCALED_SNAPSHOT_APP : STATE_SCALED_SNAPSHOT_RECENTS);
            }
        });
        anim.start();
    }

    @UiThread
    private void resumeLastTask() {
        // TODO: We need a better way for this
        TaskKey key = mRunningTask.key;
        RecentsTaskLoadPlan loadPlan = RecentsModel.getInstance(mContext).getLastLoadPlan();
        if (loadPlan != null) {
            Task task = loadPlan.getTaskStack().findTaskWithId(key.id);
            if (task != null) {
                key = task.key;
            }
        }

        ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
        ActivityManagerWrapper.getInstance().startActivityFromRecentsAsync(key, opts, null, null);
    }

    private void cleanupLauncher() {
        // TODO: These should be done as part of ActivityOptions#OnAnimationStarted
        mHotseat.setTranslationY(0);
        mAllAppsScrim.setTranslationY(0);
        mLauncher.setOnResumeCallback(() -> mDragView.close(false));
    }

    private void onAnimationToLauncherComplete() {
        mDragView.close(false);
        View currentRecentsPage = mRecentsView.getPageAt(mRecentsView.getCurrentPage());
        if (currentRecentsPage instanceof TaskView) {
            ((TaskView) currentRecentsPage).animateIconToScale(1f);
        }
        if (mInteractionType == TouchInteractionService.INTERACTION_QUICK_SWITCH) {
            for (int i = mRecentsView.getFirstTaskIndex(); i < mRecentsView.getPageCount(); i++) {
                TaskView taskView = (TaskView) mRecentsView.getPageAt(i);
                // TODO: Match the keys directly
                if (taskView.getTask().key.id != mRunningTask.key.id) {
                    mRecentsView.snapToPage(i, QUICK_SWITCH_SNAP_DURATION);
                    taskView.postDelayed(() -> {taskView.launchTask(true);},
                            QUICK_SWITCH_SNAP_DURATION);
                    break;
                }
            }
        } else if (mInteractionType == TouchInteractionService.INTERACTION_QUICK_SCRUB) {
            if (mQuickScrubController != null) {
                mQuickScrubController.snapToPageForCurrentQuickScrubSection();
            }
        }
    }

    public void onQuickScrubEnd() {
        if (mQuickScrubController != null) {
            mQuickScrubController.onQuickScrubEnd();
        }
    }

    public void onQuickScrubProgress(float progress) {
        if (mQuickScrubController != null) {
            mQuickScrubController.onQuickScrubProgress(progress);
        }
    }
}
