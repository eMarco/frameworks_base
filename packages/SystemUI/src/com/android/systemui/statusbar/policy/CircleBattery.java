/*
 * Copyright (C) 2012 Sven Dawitz for the CyanogenMod Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import android.graphics.DashPathEffect;

import com.android.internal.R;

/***
 * Note about CircleBattery Implementation:
 *
 * Unfortunately, we cannot use BatteryController or DockBatteryController here,
 * since communication between controller and this view is not possible without
 * huge changes. As a result, this Class is doing everything by itself,
 * monitoring battery level and battery settings.
 */

public class CircleBattery extends ImageView {
    private Handler mHandler;
    private Context mContext;
    private BatteryReceiver mBatteryReceiver = null;

    // state variables
    private boolean mAttached;      // whether or not attached to a window
    private boolean mActivated;     // whether or not activated due to system settings
    private boolean mPercentage;    // whether or not to show percentage number
    private boolean mIsCharging;    // whether or not device is currently charging
    private int     mLevel;         // current battery level
    private int     mAnimLevel;     // current level of charging animation

    private int     mCircleSize;    // draw size of circle. read rather complicated from
                                    // another status bar icon, so it fits the icon size
                                    // no matter the dps and resolution
    private RectF   mCircleRect;    // contains the precalculated rect used in drawArc(), derived from mCircleSize
    private Float   mPercentX;      // precalculated x position for drawText() to appear centered
    private Float   mPercentY;      // precalculated y position for drawText() to appear vertical-centered

    // quiet a lot of paint variables. helps to move cpu-usage from actual drawing to initialization
    private Paint   mPaintFont;
    private Paint   mPaintGray;
    private Paint   mPaintSystem;
    private Paint   mPaintRed;
    private Paint   mPaintAnim;
    private int batteryStyle;

    // runnable to invalidate view via mHandler.postDelayed() call
    private final Runnable mInvalidate = new Runnable() {
        public void run() {
            if(mActivated && mAttached) {
                invalidate();
            }
        }
    };

    // observes changes in system battery settings and enables/disables view accordingly
    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_BATTERY_ICON), false, this);
            onChange(true);
        }

        @Override
        public void onChange(boolean selfChange) {
            batteryStyle = (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_ICON, 0));

            mActivated = (batteryStyle == BatteryController.BATTERY_STYLE_CIRCLE
                            || batteryStyle == BatteryController.BATTERY_STYLE_CIRCLE_PERCENT
                            || batteryStyle == BatteryController.BATTERY_STYLE_DOTTED_CIRCLE_PERCENT);
            mPercentage = (batteryStyle == BatteryController.BATTERY_STYLE_CIRCLE_PERCENT
                            || batteryStyle == BatteryController.BATTERY_STYLE_DOTTED_CIRCLE_PERCENT);

            setVisibility(mActivated ? View.VISIBLE : View.GONE);
            if (mBatteryReceiver != null) {
                mBatteryReceiver.updateRegistration();
            }

            if (mActivated && mAttached) {
                invalidate();
            }
        }
    }

    // keeps track of current battery level and charger-plugged-state
    class BatteryReceiver extends BroadcastReceiver {
        private boolean mIsRegistered = false;

        public BatteryReceiver(Context context) {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mIsCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

                if (mActivated && mAttached) {
                    invalidate();
                }
            }
        }

        private void registerSelf() {
            if (!mIsRegistered) {
                mIsRegistered = true;

                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                mContext.registerReceiver(mBatteryReceiver, filter);
            }
        }

        private void unregisterSelf() {
            if (mIsRegistered) {
                mIsRegistered = false;
                mContext.unregisterReceiver(this);
            }
        }

        private void updateRegistration() {
            if (mActivated && mAttached) {
                registerSelf();
            } else {
                unregisterSelf();
            }
        }
    }

    /***
     * Start of CircleBattery implementation
     */
    public CircleBattery(Context context) {
        this(context, null);
    }

    public CircleBattery(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CircleBattery(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mContext = context;
        mHandler = new Handler();
        batteryStyle = (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_ICON, 0));
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        mBatteryReceiver = new BatteryReceiver(mContext);
        
        // initialize and setup all paint variables
        // stroke width is later set in initSizeBasedStuff()
        Resources res = getResources();

        mPaintFont = new Paint();
        mPaintFont.setAntiAlias(true);
        mPaintFont.setDither(true);
        mPaintFont.setStyle(Paint.Style.STROKE);

        mPaintGray = new Paint(mPaintFont);
        mPaintSystem = new Paint(mPaintFont);
        mPaintRed = new Paint(mPaintFont);
        mPaintAnim = new Paint(mPaintFont);

        mPaintFont.setColor(res.getColor(R.color.holo_blue_dark));
        mPaintSystem.setColor(res.getColor(R.color.holo_blue_dark));
        // could not find the darker definition anywhere in resources
        // do not want to use static 0x404040 color value. would break theming.
        mPaintGray.setColor(res.getColor(R.color.darker_gray));
        mPaintRed.setColor(res.getColor(R.color.holo_red_light));
        mPaintAnim.setColor(res.getColor(R.color.holo_blue_dark));

        // font needs some extra settings
        mPaintFont.setTextAlign(Align.CENTER);
        mPaintFont.setFakeBoldText(true);

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mBatteryReceiver.updateRegistration();
            mHandler.postDelayed(mInvalidate, 250);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
            mBatteryReceiver.updateRegistration();
            mCircleRect = null; // makes sure, size based variables get
                                // recalculated on next attach
            mCircleSize = 0;    // makes sure, mCircleSize is reread from icons on
                                // next attach
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mCircleSize == 0) {
            initSizeMeasureIconHeight();
        }
        setMeasuredDimension(mCircleSize + getPaddingLeft(), mCircleSize);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mCircleRect == null) {
            initSizeBasedStuff();
        }

        updateChargeAnim();

        Paint usePaint = mPaintSystem;
        // turn red at 14% - same level android battery warning appears
        if (mLevel <= 14) {
            usePaint = mPaintRed;
        }
        usePaint.setAntiAlias(true);
        if (batteryStyle == BatteryController.BATTERY_STYLE_DOTTED_CIRCLE_PERCENT)
        {// change usePaint from solid to dashed
            usePaint.setPathEffect(new DashPathEffect(new float[]{3,2},0));
        }
        else usePaint.setPathEffect(null);

        mPaintAnim.setColor(usePaint.getColor());

        // pad circle percentage to 100% once it reaches 97%
        // for one, the circle looks odd with a too small gap,
        // for another, some phones never reach 100% due to hardware design
        int padLevel = mLevel;
        if(mLevel>=97) {
            padLevel=100;
        }

        // draw thin gray ring first
        canvas.drawArc(mCircleRect, 270, 360, false, mPaintGray);
        // if charging, draw thin animated colored ring next
        if (mIsCharging){
            canvas.drawArc(mCircleRect, 270, 3.6f * mAnimLevel, false, mPaintAnim);
        }
        // draw thin colored ring-level last
        canvas.drawArc(mCircleRect, 270, 3.6f * padLevel, false, usePaint);
        // if chosen by options, draw percentage text in the middle
        // always skip percentage when 100, so layout doesnt break
        if (mLevel < 100 && mPercentage){
            mPaintFont.setColor(usePaint.getColor());
            canvas.drawText(Integer.toString(mLevel), mPercentX, mPercentY, mPaintFont);
        }
    }

    /***
     * updates the animation counter
     * cares for timed callbacks to continue animation cycles
     * uses mInvalidate for delayed invalidate() callbacks
     */
    private void updateChargeAnim() {
        if (!mIsCharging) {
            if (mAnimLevel != -1) {
                mAnimLevel = -1;
                mHandler.removeCallbacks(mInvalidate);
            }
            return;
        }

        if (mAnimLevel > 100 || mAnimLevel < 0) {
            mAnimLevel = mLevel;
        } else {
            mAnimLevel += 6;
        }

        mHandler.removeCallbacks(mInvalidate);
        mHandler.postDelayed(mInvalidate, 750);
    }

    /***
     * initializes all size dependent variables
     * sets stroke width and text size of all involved paints
     * YES! i think the method name is appropriate
     */
    private void initSizeBasedStuff() {
        if (mCircleSize == 0) {
            initSizeMeasureIconHeight();
        }

        mPaintFont.setTextSize(mCircleSize / 2f);

        float strokeWidth = mCircleSize / 7f;
        mPaintRed.setStrokeWidth(strokeWidth);
        mPaintSystem.setStrokeWidth(strokeWidth);
        mPaintGray.setStrokeWidth(strokeWidth / 3.5f);
        mPaintAnim.setStrokeWidth(strokeWidth / 3.5f);
        // calculate rectangle for drawArc calls
        int pLeft = getPaddingLeft();
        mCircleRect = new RectF(pLeft + strokeWidth / 2.0f, 0 + strokeWidth / 2.0f, mCircleSize
                - strokeWidth / 2.0f + pLeft, mCircleSize - strokeWidth / 2.0f);

        // calculate Y position for text
        Rect bounds = new Rect();
        mPaintFont.getTextBounds("99", 0, "99".length(), bounds);
        mPercentX = mCircleSize / 2.0f + getPaddingLeft();
        // the +1 at end of formular balances out rounding issues. works out on all resolutions
        mPercentY = mCircleSize / 2.0f + (bounds.bottom - bounds.top) / 2.0f - strokeWidth / 2.0f + 1;

        // force new measurement for wrap-content xml tag
        onMeasure(0, 0);
    }

    /***
     * we need to measure the size of the circle battery by checking another
     * resource. unfortunately, those resources have transparent/empty borders
     * so we have to count the used pixel manually and deduct the size from
     * it. quiet complicated, but the only way to fit properly into the
     * statusbar for all resolutions
     */
    private void initSizeMeasureIconHeight() {
        final Bitmap measure = BitmapFactory.decodeResource(getResources(),
                com.android.systemui.R.drawable.stat_sys_wifi_signal_4_fully);
        final int x = measure.getWidth() / 2;
        mCircleSize = measure.getHeight();
        /*
        mCircleSize = 0;
        for (int y = 0; y < measure.getHeight(); y++) {
            int alpha = Color.alpha(measure.getPixel(x, y));
            if (alpha > 5) {
                mCircleSize++;
            }
        }*/
    }
}
