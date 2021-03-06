/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.somo.face.bezierlerpface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;

import com.github.adnansm.timelytextview.TimelyView;
import com.nineoldandroids.animation.ValueAnimator;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

//import android.support.v7.graphics.Palette;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 */
public class McFaceService extends CanvasWatchFaceService {

    public static class Dial {
        public final int id;
        public final int[] supportedTypes;
        public final String name;
        public final int iconId;

        public Dial(int id, int[] supportedTypes, String name, int iconId) {
            this.id = id;
            this.supportedTypes = supportedTypes;
            this.name = name;
            this.iconId = iconId;
        }
    }

    private static class NumberView {
        private final TimelyView timelyView;
        private final int width;
        private final int height;
        private Integer number = null;

        public NumberView(Context context, int width, int height, int colour, float strokePx) {
            timelyView = new TimelyView(context);

            Paint textPaint = new Paint();
            textPaint.setAntiAlias(true);
            textPaint.setColor(colour);
            textPaint.setStrokeWidth(strokePx);
            textPaint.setStyle(Paint.Style.STROKE);
            try {
                Field paintField = TimelyView.class.getDeclaredField("mPaint");
                paintField.setAccessible(true);//Very important, this allows the setting to work.
                paintField.set(timelyView, textPaint);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            // Measure exactly for now, adjust this if you want wrap content
            int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
            timelyView.measure(widthSpec, heightSpec);

            this.width = timelyView.getMeasuredWidth();
            this.height = timelyView.getMeasuredHeight();
            timelyView.layout(0, 0, this.width, this.height);
        }

        public void draw(final CanvasWatchFaceService.Engine engine, int number, Canvas canvas, int centerX, int centerY) {
            if (this.number == null || this.number != number) {
                int start = this.number == null ? 0 : this.number;
                int end = number;
                this.number = number;
                com.nineoldandroids.animation.ObjectAnimator anim = timelyView.animate(start, end);
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        engine.invalidate();
                    }
                });
                anim.start();
                engine.invalidate();
            }

            /**/

            //Translate the canvas so the view is drawn at the proper coordinates
            canvas.save();
            canvas.translate(centerX - (width / 2), centerY - (height / 2));
            timelyView.draw(canvas);
            canvas.restore();
        }
    }

    private final static String TAG = McFaceService.class.getSimpleName();

    /*
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    public static final Dial[] COMPLICATION_DIALS = {
            new Dial(0, new int[]{ComplicationData.TYPE_SHORT_TEXT}, "Primary", R.drawable.complications_primary_dial),
            new Dial(1, new int[]{ComplicationData.TYPE_SHORT_TEXT}, "Secondary", R.drawable.complications_secondary_dial)
    };

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<McFaceService.Engine> mWeakReference;

        public EngineHandler(McFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            McFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private static final float HOUR_STROKE_WIDTH = 5f;
        private static final float MINUTE_STROKE_WIDTH = 3f;
        private static final float SECOND_TICK_STROKE_WIDTH = 2f;

        private static final float CENTER_GAP_AND_CIRCLE_RADIUS = 4f;

        private static final int SHADOW_RADIUS = 6;
        private final Rect mPeekCardBounds = new Rect();
        /* Handler to update the time once a second in interactive mode. */
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private boolean mRegisteredTimeZoneReceiver = false;
        private boolean mMuteMode;
        private float mCenterX;
        private float mCenterY;
        private float mSecondHandLength;
        private float sMinuteHandLength;
        private float sHourHandLength;
        /* Colors for all hands (hour, minute, seconds, ticks) based on photo loaded. */
        private int mWatchHandColor;
        private int mWatchHandHighlightColor;
        private int mWatchHandShadowColor;
        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mSecondPaint;
        private Paint mTickAndCirclePaint;
        private Paint mBackgroundPaint;
        private Paint mPinkRingLumpPaint;
        private Paint xferPaint;
        private Bitmap mBackgroundBitmap;
        private Bitmap mGrayBackgroundBitmap;
        private Bitmap mGradientBitmap;
        private Bitmap mCosmosBitmap;
        private Bitmap mBokehBitmap;
        private boolean mAmbient;
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;

        private NumberView handMinute10s;
        private NumberView handMinute1s;
        private NumberView handSeconds10s;
        private NumberView handSeconds1s;
        private NumberView handHours10s;
        private NumberView handHours1s;

        private String complicationText0;
        private String complicationText1;
        private RectF complication0;
        private RectF complication1;


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(McFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            int[] ids = new int[COMPLICATION_DIALS.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = COMPLICATION_DIALS[i].id;
            }
            setActiveComplications(ids);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.BLACK);
            mPinkRingLumpPaint = new Paint();
            mPinkRingLumpPaint.setAntiAlias(true);
            mPinkRingLumpPaint.setColor(0xFFC53C91);
            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.pink_ring);
            mGradientBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.gradient);
            mCosmosBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.cosmos);
            mBokehBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bokeh);

            /* Set defaults for colors */
            mWatchHandColor = Color.WHITE;
            mWatchHandHighlightColor = Color.BLUE;
            mWatchHandShadowColor = Color.BLACK;

            mHourPaint = new Paint();
            mHourPaint.setColor(mWatchHandColor);
            mHourPaint.setStrokeWidth(HOUR_STROKE_WIDTH);
            mHourPaint.setAntiAlias(true);
            mHourPaint.setStrokeCap(Paint.Cap.ROUND);
            mHourPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mMinutePaint = new Paint();
            mMinutePaint.setColor(mWatchHandColor);
            mMinutePaint.setStrokeWidth(MINUTE_STROKE_WIDTH);
            mMinutePaint.setAntiAlias(true);
            mMinutePaint.setStrokeCap(Paint.Cap.ROUND);
            mMinutePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mSecondPaint = new Paint();
            mSecondPaint.setColor(mWatchHandHighlightColor);
            mSecondPaint.setStrokeWidth(SECOND_TICK_STROKE_WIDTH);
            mSecondPaint.setAntiAlias(true);
            mSecondPaint.setStrokeCap(Paint.Cap.ROUND);
            mSecondPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mTickAndCirclePaint = new Paint();
            mTickAndCirclePaint.setColor(mWatchHandColor);
            mTickAndCirclePaint.setStrokeWidth(SECOND_TICK_STROKE_WIDTH);
            mTickAndCirclePaint.setAntiAlias(true);
            mTickAndCirclePaint.setStyle(Paint.Style.STROKE);
            mTickAndCirclePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
            mTickAndCirclePaint.setTextSize(20);

            xferPaint = new Paint();
            xferPaint.setColor(0xFF000000);
            xferPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_ATOP));

            /* Extract colors from background image to improve watchface style. */
            /*Palette.from(mBackgroundBitmap).generate(new Palette.PaletteAsyncListener() {
                @Override
                public void onGenerated(Palette palette) {
                    if (palette != null) {
                        mWatchHandHighlightColor = palette.getVibrantColor(Color.BLUE);
                        mWatchHandColor = palette.getLightVibrantColor(Color.WHITE);
                        mWatchHandShadowColor = palette.getDarkMutedColor(Color.BLACK);
                        updateWatchHandStyle();
                    }
                }
            });*/

            mCalendar = Calendar.getInstance();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            mAmbient = inAmbientMode;

            updateWatchHandStyle();

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        private void updateWatchHandStyle() {
            if (mAmbient) {
                mHourPaint.setColor(Color.WHITE);
                mMinutePaint.setColor(Color.WHITE);
                mSecondPaint.setColor(Color.WHITE);
                mTickAndCirclePaint.setColor(Color.WHITE);

                mHourPaint.setAntiAlias(false);
                mMinutePaint.setAntiAlias(false);
                mSecondPaint.setAntiAlias(false);
                mTickAndCirclePaint.setAntiAlias(false);

                mHourPaint.clearShadowLayer();
                mMinutePaint.clearShadowLayer();
                mSecondPaint.clearShadowLayer();
                mTickAndCirclePaint.clearShadowLayer();

            } else {
                mHourPaint.setColor(mWatchHandColor);
                mMinutePaint.setColor(mWatchHandColor);
                mSecondPaint.setColor(mWatchHandHighlightColor);
                mTickAndCirclePaint.setColor(mWatchHandColor);

                mHourPaint.setAntiAlias(true);
                mMinutePaint.setAntiAlias(true);
                mSecondPaint.setAntiAlias(true);
                mTickAndCirclePaint.setAntiAlias(true);

                mHourPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mMinutePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mSecondPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mTickAndCirclePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode;
                mHourPaint.setAlpha(inMuteMode ? 100 : 255);
                mMinutePaint.setAlpha(inMuteMode ? 100 : 255);
                mSecondPaint.setAlpha(inMuteMode ? 80 : 255);
                invalidate();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f;
            mCenterY = height / 2f;

            /*
             * Calculate lengths of different hands based on watch screen size.
             */
            mSecondHandLength = (float) (mCenterX * 0.875);
            sMinuteHandLength = (float) (mCenterX * 0.75);
            sHourHandLength = (float) (mCenterX * 0.5);


            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don't want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren't
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap();
            }

            int charWidth = Math.round((float) width * 0.03f);
            int charHeight = Math.round(charWidth * 1.9f);
            int strokeWidth = Math.round((float) width * 0.005f);
            handMinute10s = new NumberView(getApplicationContext(), charWidth, charHeight, 0xFFCCCCCC, strokeWidth);
            handMinute1s = new NumberView(getApplicationContext(), charWidth, charHeight, 0xFFCCCCCC, strokeWidth);
            handSeconds10s = new NumberView(getApplicationContext(), charWidth, charHeight, 0xFFCCCCCC, strokeWidth);
            handSeconds1s = new NumberView(getApplicationContext(), charWidth, charHeight, 0xFFCCCCCC, strokeWidth);
            handHours10s = new NumberView(getApplicationContext(), charWidth, charHeight, 0xFFFFFFFF, strokeWidth);
            handHours1s = new NumberView(getApplicationContext(), charWidth, charHeight, 0xFFFFFFFF, strokeWidth);


            //Define complication zones
            int complicationSize = (int) (mCenterX / 5);
            //Amount to offset from the centre - diag bottom left / top right. 1/(X *... X=1 would put the circle at the watch edge, 2 half way between centre and edge
            float offsetFraction = (float) (1 / (2.5 * Math.sqrt(2.0)));

            complication0 = new RectF(
                    mCenterX * (1 - offsetFraction) - complicationSize,
                    mCenterY * (1 + offsetFraction) - complicationSize,
                    mCenterX * (1 - offsetFraction) + complicationSize,
                    mCenterY * (1 + offsetFraction) + complicationSize
            );

            complication1 = new RectF(
                    mCenterX * (1 + offsetFraction) - complicationSize,
                    mCenterY * (1 - offsetFraction) - complicationSize,
                    mCenterX * (1 + offsetFraction) + complicationSize,
                    mCenterY * (1 - offsetFraction) + complicationSize
            );

        }

        private void initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                    mBackgroundBitmap.getWidth(),
                    mBackgroundBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mGrayBackgroundBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mBackgroundBitmap, 0, 0, grayPaint);
        }

        /**
         * Captures tap event (and tap type). The {@link WatchFaceService#TAP_TYPE_TAP} case can be
         * used for implementing specific logic to handle the gesture.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    /*Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();*/
                    /*int start = 0;
                    int end = 6;
                    com.nineoldandroids.animation.ObjectAnimator anim = timelyView.animate(start, end);
                    anim.start();
                    anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            invalidate();
                        }
                    });*/

                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float secondsRotation = seconds * 6f;

            final int minutes = mCalendar.get(Calendar.MINUTE);
            final float minutesRotation = (float) minutes * 6f;

            final int hours12 = mCalendar.get(Calendar.HOUR);
            final float hourHandOffset = (float) minutes / 2f;
            final float hoursRotation = ((float) hours12 * 30) + hourHandOffset;

            boolean drawGradient = false;

            Matrix matrixCosmos = new Matrix();
            matrixCosmos.setScale((mCenterX * 2) / mCosmosBitmap.getWidth(), (mCenterY * 2) / mCosmosBitmap.getHeight());

            Matrix matrixPinkRing = new Matrix();
            matrixPinkRing.setScale((mCenterX * 2) / mBackgroundBitmap.getWidth(), (mCenterY * 2) / mBackgroundBitmap.getHeight());


            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK);
            } else if (mAmbient) {
                canvas.drawBitmap(mCosmosBitmap, matrixCosmos, mBackgroundPaint);
                canvas.drawBitmap(mBokehBitmap, matrixCosmos, mBackgroundPaint);
                canvas.drawBitmap(mGrayBackgroundBitmap, matrixCosmos, mBackgroundPaint);
            } else {
                canvas.drawBitmap(mCosmosBitmap, matrixCosmos, mBackgroundPaint);
                canvas.drawBitmap(mBokehBitmap, matrixCosmos, mBackgroundPaint);

                drawGradient = true;
                canvas.saveLayer(null, mBackgroundPaint);

                canvas.drawBitmap(mGradientBitmap, matrixPinkRing, mBackgroundPaint);

                canvas.saveLayer(null, xferPaint);

                canvas.drawBitmap(mBackgroundBitmap, matrixPinkRing, mBackgroundPaint);

            }

            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            /*float innerTickRadius = mCenterX - 10;
            float outerTickRadius = mCenterX;
            for (int tickIndex = 0; tickIndex < 12; tickIndex++) {
                float tickRot = (float) (tickIndex * Math.PI * 2 / 12);
                float innerX = (float) Math.sin(tickRot) * innerTickRadius;
                float innerY = (float) -Math.cos(tickRot) * innerTickRadius;
                float outerX = (float) Math.sin(tickRot) * outerTickRadius;
                float outerY = (float) -Math.cos(tickRot) * outerTickRadius;
                canvas.drawLine(mCenterX + innerX, mCenterY + innerY,
                        mCenterX + outerX, mCenterY + outerY, mTickAndCirclePaint);
            }*/

            /* Draw rectangle behind peek card in ambient mode to improve readability. */
            if (mAmbient) {
                canvas.drawRect(mPeekCardBounds, mBackgroundPaint);
            }

            float circleRadius = mCenterX / 10;

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                canvas.save();
                canvas.rotate(secondsRotation, mCenterX, mCenterY);
                canvas.translate(0, (float) (-mCenterX * 0.9));

                canvas.drawCircle(mCenterX, mCenterY, circleRadius, mPinkRingLumpPaint);

                canvas.rotate(-secondsRotation, mCenterX, mCenterY);
                handSeconds10s.draw(this, (int) seconds / 10, canvas, (int) (mCenterX * 0.97), (int) mCenterY);
                handSeconds1s.draw(this, (int) seconds % 10, canvas, (int) (mCenterX * 1.03), (int) mCenterY);

                canvas.restore();
            }

            canvas.save();
            canvas.rotate(minutesRotation, mCenterX, mCenterY);
            canvas.translate(0, (float) (-mCenterX * 0.9));

            canvas.drawCircle(mCenterX, mCenterY, circleRadius, mPinkRingLumpPaint);

            canvas.rotate(-minutesRotation, mCenterX, mCenterY);
            handMinute10s.draw(this, minutes / 10, canvas, (int) (mCenterX * 0.97), (int) mCenterY);
            handMinute1s.draw(this, minutes % 10, canvas, (int) (mCenterX * 1.03), (int) mCenterY);

            canvas.restore();

            canvas.save();
            canvas.rotate(hoursRotation, mCenterX, mCenterY);
            canvas.translate(0, (float) (-mCenterX * 0.9));

            canvas.drawCircle(mCenterX, mCenterY, circleRadius, mPinkRingLumpPaint);

            canvas.rotate(-hoursRotation, mCenterX, mCenterY);
            handHours10s.draw(this, hours12 / 10, canvas, (int) (mCenterX * 0.97), (int) mCenterY);
            handHours1s.draw(this, hours12 % 10, canvas, (int) (mCenterX * 1.03), (int) mCenterY);

            canvas.restore();



            if (drawGradient) {
                canvas.restore();
                canvas.restore();
            }

            drawComplications(canvas);
        }

        private void drawComplications(Canvas canvas) {
            mTickAndCirclePaint.setTextSize(mCenterX / 10);
            Rect bounds = new Rect();
            if (complicationText0 != null ) {
                canvas.drawOval(complication0, mPinkRingLumpPaint);
                mTickAndCirclePaint.getTextBounds(complicationText0, 0, complicationText0.length(), bounds);
                canvas.drawText(complicationText0, complication0.centerX() - bounds.width() / 2, complication0.centerY() + bounds.height() / 2, mTickAndCirclePaint);
            }
            if (complicationText1 != null ) {
                canvas.drawOval(complication1, mPinkRingLumpPaint);
                mTickAndCirclePaint.getTextBounds(complicationText1, 0, complicationText1.length(), bounds);
                canvas.drawText(complicationText1, complication1.centerX() - bounds.width() / 2, complication1.centerY() + bounds.height() / 2, mTickAndCirclePaint);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mPeekCardBounds.set(rect);
        }

        @Override
        public void onComplicationDataUpdate(int complicationId, ComplicationData data) {
            Log.d(TAG, "onComplicationDataUpdate() id: " + complicationId);
            if (data.getType() == ComplicationData.TYPE_SHORT_TEXT) {
                if (complicationId == 0) {
                    complicationText0 = data.getShortText().getText(getApplicationContext(), Calendar.getInstance().getTimeInMillis()).toString();
                    Log.d(TAG, "onComplicationDataUpdate: " + complicationText0);
                } else {
                    complicationText1 = data.getShortText().getText(getApplicationContext(), Calendar.getInstance().getTimeInMillis()).toString();
                    Log.d(TAG, "onComplicationDataUpdate: " + complicationText1);
                }
            }
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            McFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            McFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should only run in active mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !mAmbient;
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
