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
package com.android.wallpaper.picker;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.wallpaper.R;
import com.android.wallpaper.asset.Asset;
import com.android.wallpaper.asset.Asset.BitmapReceiver;
import com.android.wallpaper.asset.Asset.DimensionsReceiver;
import com.android.wallpaper.compat.BuildCompat;
import com.android.wallpaper.compat.ButtonDrawableSetterCompat;
import com.android.wallpaper.config.Flags;
import com.android.wallpaper.model.LiveWallpaperInfo;
import com.android.wallpaper.model.WallpaperInfo;
import com.android.wallpaper.module.CurrentWallpaperInfoFactory;
import com.android.wallpaper.module.ExploreIntentChecker;
import com.android.wallpaper.module.Injector;
import com.android.wallpaper.module.InjectorProvider;
import com.android.wallpaper.module.UserEventLogger;
import com.android.wallpaper.module.UserEventLogger.WallpaperSetFailureReason;
import com.android.wallpaper.module.WallpaperPersister;
import com.android.wallpaper.module.WallpaperPersister.Destination;
import com.android.wallpaper.module.WallpaperPersister.SetWallpaperCallback;
import com.android.wallpaper.module.WallpaperPreferences;
import com.android.wallpaper.util.ScreenSizeCalculator;
import com.android.wallpaper.util.ThrowableAnalyzer;
import com.android.wallpaper.util.WallpaperCropUtils;
import com.android.wallpaper.widget.MaterialProgressDrawable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.MemoryCategory;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetBehavior.State;

import java.util.Date;
import java.util.List;

import androidx.annotation.IntDef;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

/**
 * Fragment which displays the UI for previewing an individual wallpaper and its attribution
 * information.
 */
public class PreviewFragment extends Fragment implements
        SetWallpaperDialogFragment.Listener, SetWallpaperErrorDialogFragment.Listener,
        LoadWallpaperErrorDialogFragment.Listener {

    /**
     * User can view wallpaper and attributions in full screen, but "Set wallpaper" button is hidden.
     */
    public static final int MODE_VIEW_ONLY = 0;

    /**
     * User can view wallpaper and attributions in full screen and click "Set wallpaper" to set the
     * wallpaper with pan and crop position to the device.
     */
    public static final int MODE_CROP_AND_SET_WALLPAPER = 1;

    /**
     * Possible preview modes for the fragment.
     */
    @IntDef({
            MODE_VIEW_ONLY,
            MODE_CROP_AND_SET_WALLPAPER})
    public @interface PreviewMode {
    }

    protected static final String ARG_WALLPAPER = "wallpaper";
    protected static final String ARG_PREVIEW_MODE = "preview_mode";
    protected static final String ARG_TESTING_MODE_ENABLED = "testing_mode_enabled";
    private static final String TAG_LOAD_WALLPAPER_ERROR_DIALOG_FRAGMENT =
            "load_wallpaper_error_dialog";
    private static final String TAG_SET_WALLPAPER_DIALOG_FRAGMENT = "set_wallpaper_dialog";
    private static final String TAG_SET_WALLPAPER_ERROR_DIALOG_FRAGMENT =
            "set_wallpaper_error_dialog";
    private static final int UNUSED_REQUEST_CODE = 1;
    private static final float DEFAULT_WALLPAPER_MAX_ZOOM = 8f;
    private static final String TAG = "PreviewFragment";
    private static final String PROGRESS_DIALOG_NO_TITLE = null;
    private static final boolean PROGRESS_DIALOG_INDETERMINATE = true;
    private static final float PAGE_BITMAP_MAX_HEAP_RATIO = 0.25f;
    private static final String KEY_BOTTOM_SHEET_STATE = "key_bottom_sheet_state";

    @PreviewMode
    private int mPreviewMode;

    /**
     * When true, enables a test mode of operation -- in which certain UI features are disabled to
     * allow for UI tests to run correctly. Works around issue in ProgressDialog currently where the
     * dialog constantly keeps the UI thread alive and blocks a test forever.
     */
    private boolean mTestingModeEnabled;

    protected SubsamplingScaleImageView mFullResImageView;
    protected WallpaperInfo mWallpaper;
    private Asset mWallpaperAsset;
    private WallpaperPersister mWallpaperPersister;
    private WallpaperPreferences mPreferences;
    private UserEventLogger mUserEventLogger;
    private LinearLayout mBottomSheet;
    private TextView mAttributionTitle;
    private TextView mAttributionSubtitle1;
    private TextView mAttributionSubtitle2;
    private FrameLayout mAttributionExploreSection;
    private Button mAttributionExploreButton;
    private ImageView mPreviewPaneArrow;
    private int mCurrentScreenOrientation;
    private ProgressDialog mProgressDialog;
    private Point mDefaultCropSurfaceSize;
    private Point mScreenSize;
    private Point mRawWallpaperSize; // Native size of wallpaper image.
    private ImageView mLoadingIndicator;
    private MaterialProgressDrawable mProgressDrawable;
    private ImageView mLowResImageView;

    @SuppressWarnings("RestrictTo")
    @State
    private int mBottomSheetInitialState;

    private Intent mExploreIntent;

    /**
     * Staged error dialog fragments that were unable to be shown when the hosting activity didn't
     * allow committing fragment transactions.
     */
    private SetWallpaperErrorDialogFragment mStagedSetWallpaperErrorDialogFragment;
    private LoadWallpaperErrorDialogFragment mStagedLoadWallpaperErrorDialogFragment;

    /**
     * Creates and returns new instance of {@link PreviewFragment} with the provided wallpaper set as
     * an argument.
     */
    public static PreviewFragment newInstance(
            WallpaperInfo wallpaperInfo, @PreviewMode int mode, boolean testingModeEnabled) {
        Bundle args = new Bundle();
        args.putParcelable(ARG_WALLPAPER, wallpaperInfo);
        args.putInt(ARG_PREVIEW_MODE, mode);
        args.putBoolean(ARG_TESTING_MODE_ENABLED, testingModeEnabled);

        PreviewFragment fragment = new PreviewFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Activity activity = getActivity();
        Context appContext = activity.getApplicationContext();
        Injector injector = InjectorProvider.getInjector();

        mWallpaperPersister = injector.getWallpaperPersister(appContext);
        mPreferences = injector.getPreferences(appContext);
        mUserEventLogger = injector.getUserEventLogger(appContext);
        mWallpaper = getArguments().getParcelable(ARG_WALLPAPER);
        mWallpaperAsset = mWallpaper.getAsset(appContext);
        //noinspection ResourceType
        mPreviewMode = getArguments().getInt(ARG_PREVIEW_MODE);
        mTestingModeEnabled = getArguments().getBoolean(ARG_TESTING_MODE_ENABLED);

        setHasOptionsMenu(true);

        // Allow the layout to draw fullscreen even behind the status bar, so we can set as the status
        // bar color a color that has a custom translucency in the theme.
        Window window = activity.getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        List<String> attributions = mWallpaper.getAttributions(activity);
        if (attributions.size() > 0 && attributions.get(0) != null) {
            activity.setTitle(attributions.get(0));
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_preview, container, false);

        // Set toolbar as the action bar.
        Toolbar toolbar = (Toolbar) view.findViewById(R.id.toolbar);
        ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setDisplayShowTitleEnabled(false);

        // Use updated fancy arrow icon for O+.
        if (BuildCompat.isAtLeastO()) {
            Drawable navigationIcon = getResources().getDrawable(
                    R.drawable.material_ic_arrow_back_black_24);

            // This Drawable's state is shared across the app, so make a copy of it before applying a
            // color tint as not to affect other clients elsewhere in the app.
            navigationIcon = navigationIcon.getConstantState().newDrawable().mutate();
            navigationIcon.setColorFilter(
                    getResources().getColor(R.color.material_white_100), Mode.SRC_IN);
            navigationIcon.setAutoMirrored(true);
            toolbar.setNavigationIcon(navigationIcon);
        }

        ViewCompat.setPaddingRelative(toolbar,
        /* start */ getResources().getDimensionPixelSize(
                        R.dimen.preview_toolbar_up_button_start_padding),
        /* top */ 0,
        /* end */ getResources().getDimensionPixelSize(
                        R.dimen.preview_toolbar_set_wallpaper_button_end_padding),
        /* bottom */ 0);

        mFullResImageView = view.findViewById(R.id.full_res_image);
        mLoadingIndicator = (ImageView) view.findViewById(R.id.loading_indicator);

        mBottomSheet = (LinearLayout) view.findViewById(R.id.bottom_sheet);
        mAttributionTitle = (TextView) view.findViewById(R.id.preview_attribution_pane_title);
        mAttributionSubtitle1 = (TextView) view.findViewById(R.id.preview_attribution_pane_subtitle1);
        mAttributionSubtitle2 = (TextView) view.findViewById(R.id.preview_attribution_pane_subtitle2);
        mAttributionExploreSection = (FrameLayout) view.findViewById(
                R.id.preview_attribution_pane_explore_section);
        mAttributionExploreButton = (Button) view.findViewById(
                R.id.preview_attribution_pane_explore_button);
        mPreviewPaneArrow = (ImageView) view.findViewById(R.id.preview_attribution_pane_arrow);
        mLowResImageView = (ImageView) view.findViewById(R.id.low_res_image);

        mPreviewPaneArrow.setColorFilter(
                getResources().getColor(R.color.preview_pane_arrow_color), Mode.SRC_IN);

        // Trim some memory from Glide to make room for the full-size image in this fragment.
        Glide.get(getActivity()).setMemoryCategory(MemoryCategory.LOW);

        mDefaultCropSurfaceSize = WallpaperCropUtils.getDefaultCropSurfaceSize(
                getResources(), getActivity().getWindowManager().getDefaultDisplay());
        mScreenSize = ScreenSizeCalculator.getInstance().getScreenSize(
                getActivity().getWindowManager().getDefaultDisplay());

        // Load a low-res placeholder image if there's a thumbnail available from the asset that can be
        // shown to the user more quickly than the full-sized image.
        if (mWallpaperAsset.hasLowResDataSource()) {
            mWallpaperAsset.loadLowResDrawable(getActivity(), mLowResImageView, Color.BLACK,
                    new WallpaperPreviewBitmapTransformation(getActivity().getApplicationContext(), isRtl()));
        }

        mWallpaperAsset.decodeRawDimensions(getActivity(), new DimensionsReceiver() {
            @Override
            public void onDimensionsDecoded(Point dimensions) {
                // Don't continue loading the wallpaper if the Fragment is detached.
                Activity activity = getActivity();
                if (activity == null) {
                    return;
                }

                // Return early and show a dialog if dimensions are null (signaling a decoding error).
                if (dimensions == null) {
                    showLoadWallpaperErrorDialog();
                    return;
                }

                mRawWallpaperSize = dimensions;
                ExploreIntentChecker intentChecker =
                        InjectorProvider.getInjector().getExploreIntentChecker(activity);
                String actionUrl = mWallpaper.getActionUrl(activity);
                if (actionUrl != null && !actionUrl.isEmpty()) {
                    Uri exploreUri = Uri.parse(mWallpaper.getActionUrl(activity));

                    intentChecker.fetchValidActionViewIntent(exploreUri, exploreIntent -> {
                        if (getActivity() == null) {
                            return;
                        }

                        mExploreIntent = exploreIntent;
                        initFullResView();
                    });
                } else {
                    initFullResView();
                }
            }
        });

        // Configure loading indicator with a MaterialProgressDrawable.
        mProgressDrawable =
                new MaterialProgressDrawable(getActivity().getApplicationContext(), mLoadingIndicator);
        mProgressDrawable.setAlpha(255);
        mProgressDrawable.setBackgroundColor(getResources().getColor(R.color.material_white_100));
        mProgressDrawable.setColorSchemeColors(getResources().getColor(R.color.accent_color));
        mProgressDrawable.updateSizes(MaterialProgressDrawable.LARGE);
        mLoadingIndicator.setImageDrawable(mProgressDrawable);

        // We don't want to show the spinner every time we load an image if it loads quickly; instead,
        // only start showing the spinner if loading the image has taken longer than half of a second.
        mLoadingIndicator.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mFullResImageView != null && !mFullResImageView.hasImage()
                        && !mTestingModeEnabled) {
                    mLoadingIndicator.setVisibility(View.VISIBLE);
                    mLoadingIndicator.setAlpha(1f);
                    if (mProgressDrawable != null) {
                        mProgressDrawable.start();
                    }
                }
            }
        }, 500);

        mBottomSheetInitialState = (savedInstanceState == null)
                ? BottomSheetBehavior.STATE_EXPANDED
                : savedInstanceState.getInt(KEY_BOTTOM_SHEET_STATE,
                        BottomSheetBehavior.STATE_EXPANDED);
        setUpBottomSheetListeners();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        WallpaperPreferences preferences = InjectorProvider.getInjector().getPreferences(getActivity());
        preferences.setLastAppActiveTimestamp(new Date().getTime());

        // Show the staged 'load wallpaper' or 'set wallpaper' error dialog fragments if there is one
        // that was unable to be shown earlier when this fragment's hosting activity didn't allow
        // committing fragment transactions.
        if (mStagedLoadWallpaperErrorDialogFragment != null) {
            mStagedLoadWallpaperErrorDialogFragment.show(
                    getFragmentManager(), TAG_LOAD_WALLPAPER_ERROR_DIALOG_FRAGMENT);
            mStagedLoadWallpaperErrorDialogFragment = null;
        }
        if (mStagedSetWallpaperErrorDialogFragment != null) {
            mStagedSetWallpaperErrorDialogFragment.show(
                    getFragmentManager(), TAG_SET_WALLPAPER_ERROR_DIALOG_FRAGMENT);
            mStagedSetWallpaperErrorDialogFragment = null;
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.preview_menu, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        MenuItem setWallpaperButton = menu.findItem(R.id.set_wallpaper);

        if (mPreviewMode == MODE_CROP_AND_SET_WALLPAPER && isWallpaperLoaded()) {
            setWallpaperButton.setVisible(true);
        } else {
            setWallpaperButton.setVisible(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.set_wallpaper) {
            if (BuildCompat.isAtLeastN()) {
                requestDestination();
            } else {
                setCurrentWallpaper(WallpaperPersister.DEST_HOME_SCREEN);
            }
            return true;
        } else if (id == android.R.id.home) {
            // The Preview screen has multiple entry points. It could be opened from either
            // the IndividualPreviewActivity, the "My photos" selection (by way of
            // TopLevelPickerActivity), or from a system "crop and set wallpaper" intent.
            // Therefore, handle the Up button as a global Back.
            getActivity().onBackPressed();
            return true;
        }

        return false;
    }

    private void requestDestination() {
        CurrentWallpaperInfoFactory factory = InjectorProvider.getInjector()
                .getCurrentWallpaperFactory(getContext());

        factory.createCurrentWallpaperInfos((homeWallpaper, lockWallpaper, presentationMode) -> {
            SetWallpaperDialogFragment setWallpaperDialog = new SetWallpaperDialogFragment();
            setWallpaperDialog.setTargetFragment(this, UNUSED_REQUEST_CODE);
            if (homeWallpaper instanceof LiveWallpaperInfo && lockWallpaper == null) {
                // if the lock wallpaper is a live wallpaper, we cannot set a home-only static one
                setWallpaperDialog.setHomeOptionAvailable(false);
            }
            setWallpaperDialog.show(getFragmentManager(), TAG_SET_WALLPAPER_DIALOG_FRAGMENT);
        }, true); // Force refresh as the wallpaper may have been set while this fragment was paused
    }

    @Override
    public void onSetHomeScreen() {
        setCurrentWallpaper(WallpaperPersister.DEST_HOME_SCREEN);
    }

    @Override
    public void onSetLockScreen() {
        setCurrentWallpaper(WallpaperPersister.DEST_LOCK_SCREEN);
    }

    @Override
    public void onSetBoth() {
        setCurrentWallpaper(WallpaperPersister.DEST_BOTH);
    }

    @Override
    public void onClickTryAgain(@Destination int wallpaperDestination) {
        setCurrentWallpaper(wallpaperDestination);
    }

    @Override
    public void onClickOk() {
        FragmentActivity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
        }
        if (mProgressDrawable != null) {
            mProgressDrawable.stop();
        }
        mFullResImageView.recycle();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        final BottomSheetBehavior bottomSheetBehavior = BottomSheetBehavior.from(mBottomSheet);
        outState.putInt(KEY_BOTTOM_SHEET_STATE, bottomSheetBehavior.getState());
    }

    private void updatePreviewPaneArrow(int bottomSheetState) {
        if (bottomSheetState == BottomSheetBehavior.STATE_COLLAPSED) {
            mPreviewPaneArrow.setImageResource(R.drawable.material_ic_keyboard_arrow_up_black_24);
            mPreviewPaneArrow.setContentDescription(
                    getResources().getString(R.string.expand_attribution_panel));
        } else if (bottomSheetState == BottomSheetBehavior.STATE_EXPANDED) {
            mPreviewPaneArrow.setImageResource(R.drawable.material_ic_keyboard_arrow_down_black_24);
            mPreviewPaneArrow.setContentDescription(
                    getResources().getString(R.string.collapse_attribution_panel));
        }
        mPreviewPaneArrow.setColorFilter(
                getResources().getColor(R.color.preview_pane_arrow_color), Mode.SRC_IN);
    }

    /**
     * Returns a zoom level that is similar to the actual zoom, but that is exactly 0.5 ** n for some
     * integer n. This is useful for downsampling a bitmap--we want to see the bitmap at full detail,
     * or downsampled to 1 in every 2 pixels, or 1 in 4, and so on, depending on the zoom.
     */
    private static float getDownsampleZoom(float actualZoom) {
        if (actualZoom > 1) {
            // Very zoomed in, but we can't sample more than 1 pixel per pixel.
            return 1.0f;
        }
        float lower = 1.0f / roundUpToPower2((int) Math.ceil(1 / actualZoom));
        float upper = lower * 2;
        return nearestValue(actualZoom, lower, upper);
    }

    /**
     * Returns the integer rounded up to the next power of 2.
     */
    private static int roundUpToPower2(int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    /**
     * Returns the closer of two values a and b to the given value.
     */
    private static float nearestValue(float value, float a, float b) {
        return Math.abs(a - value) < Math.abs(b - value) ? a : b;
    }

    private void setUpBottomSheetListeners() {
        final BottomSheetBehavior bottomSheetBehavior = BottomSheetBehavior.from(mBottomSheet);

        OnClickListener onClickListener = new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                } else if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                }
            }
        };
        mAttributionTitle.setOnClickListener(onClickListener);
        mPreviewPaneArrow.setOnClickListener(onClickListener);

        bottomSheetBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(View bottomSheet, int newState) {
                // Don't respond to lingering state change events occurring after the fragment has already
                // been detached from the activity. Else, IllegalStateException may occur when trying to
                // fetch resources.
                if (getActivity() == null) {
                    return;
                }

                updatePreviewPaneArrow(newState);
            }

            @Override
            public void onSlide(View bottomSheet, float slideOffset) {
                float alpha;
                if (slideOffset >= 0) {
                    alpha = slideOffset;
                } else {
                    alpha = 1f - slideOffset;
                }
                mAttributionTitle.setAlpha(alpha);
                mAttributionSubtitle1.setAlpha(alpha);
                mAttributionSubtitle2.setAlpha(alpha);
                mAttributionExploreButton.setAlpha(alpha);
            }
        });
    }

    private boolean isWallpaperLoaded() {
        return mFullResImageView.hasImage();
    }

    private void populateAttributionPane() {
        final Context context = getContext();

        final BottomSheetBehavior bottomSheetBehavior = BottomSheetBehavior.from(mBottomSheet);

        List<String> attributions = mWallpaper.getAttributions(context);
        if (attributions.size() > 0 && attributions.get(0) != null) {
            mAttributionTitle.setText(attributions.get(0));
        }

        if (attributions.size() > 1 && attributions.get(1) != null) {
            mAttributionSubtitle1.setVisibility(View.VISIBLE);
            mAttributionSubtitle1.setText(attributions.get(1));
        }

        if (attributions.size() > 2 && attributions.get(2) != null) {
            mAttributionSubtitle2.setVisibility(View.VISIBLE);
            mAttributionSubtitle2.setText(attributions.get(2));
        }

        String actionUrl = mWallpaper.getActionUrl(context);
        if (actionUrl != null && !actionUrl.isEmpty()) {
            if (mExploreIntent != null) {
                if (Flags.skipDailyWallpaperButtonEnabled) {
                    Drawable exploreButtonDrawable = context.getDrawable(
                            mWallpaper.getActionIconRes(context));

                    // This Drawable's state is shared across the app, so make a copy of it before applying a
                    // color tint as not to affect other clients elsewhere in the app.
                    exploreButtonDrawable = exploreButtonDrawable.getConstantState()
                            .newDrawable().mutate();
                    // Color the "compass" icon with the accent color.
                    exploreButtonDrawable.setColorFilter(
                            getResources().getColor(R.color.accent_color), Mode.SRC_IN);
                    ButtonDrawableSetterCompat.setDrawableToButtonStart(
                            mAttributionExploreButton, exploreButtonDrawable);
                    mAttributionExploreButton.setText(context.getString(
                            mWallpaper.getActionLabelRes(context)));
                }

                mAttributionExploreSection.setVisibility(View.VISIBLE);
                mAttributionExploreButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        mUserEventLogger.logActionClicked(mWallpaper.getCollectionId(context),
                                mWallpaper.getActionLabelRes(context));

                        startActivity(mExploreIntent);
                    }
                });
            }
        }

        mBottomSheet.setVisibility(View.VISIBLE);

        // Initialize the state of the BottomSheet based on the current state because if the initial
        // and current state are the same, the state change listener won't fire and set the correct
        // arrow asset and text alpha.
        if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            updatePreviewPaneArrow(BottomSheetBehavior.STATE_EXPANDED);
        } else {
            mAttributionTitle.setAlpha(0f);
            mAttributionSubtitle1.setAlpha(0f);
            mAttributionSubtitle2.setAlpha(0f);
        }

        // Let the state change listener take care of animating a state change to the initial state if
        // there's a state change.
        bottomSheetBehavior.setState(mBottomSheetInitialState);
    }

    /**
     * Initializes MosaicView by initializing tiling, setting a fallback page bitmap, and initializing
     * a zoom-scroll observer and click listener.
     */
    private void initFullResView() {
        mFullResImageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP);

        // Set a solid black "page bitmap" so MosaicView draws a black background while waiting
        // for the image to load or a transparent one if a thumbnail already loaded.
        Bitmap blackBitmap = Bitmap.createBitmap(1, 1, Config.ARGB_8888);
        int color = (mLowResImageView.getDrawable() == null) ? Color.BLACK : Color.TRANSPARENT;
        blackBitmap.setPixel(0, 0, color);
        mFullResImageView.setImage(ImageSource.bitmap(blackBitmap));

        // Then set a fallback "page bitmap" to cover the whole MosaicView, which is an actual
        // (lower res) version of the image to be displayed.
        Point targetPageBitmapSize = new Point(mRawWallpaperSize);
        mWallpaperAsset.decodeBitmap(targetPageBitmapSize.x, targetPageBitmapSize.y,
                new BitmapReceiver() {
                    @Override
                    public void onBitmapDecoded(Bitmap pageBitmap) {
                        // Check that the activity is still around since the decoding task started.
                        if (getActivity() == null) {
                            return;
                        }

                        // Some of these may be null depending on if the Fragment is paused, stopped,
                        // or destroyed.
                        if (mLoadingIndicator != null) {
                            mLoadingIndicator.setVisibility(View.GONE);
                        }
                        // The page bitmap may be null if there was a decoding error, so show an error dialog.
                        if (pageBitmap == null) {
                            showLoadWallpaperErrorDialog();
                            return;
                        }
                        if (mFullResImageView != null) {
                            // Set page bitmap.
                            mFullResImageView.setImage(ImageSource.bitmap(pageBitmap));

                            setDefaultWallpaperZoomAndScroll();
                            crossFadeInMosaicView();
                        }
                        if (mProgressDrawable != null) {
                            mProgressDrawable.stop();
                        }
                        getActivity().invalidateOptionsMenu();

                        populateAttributionPane();
                    }
                });
    }

    /**
     * Makes the MosaicView visible with an alpha fade-in animation while fading out the loading
     * indicator.
     */
    private void crossFadeInMosaicView() {
        long shortAnimationDuration = getResources().getInteger(android.R.integer.config_shortAnimTime);

        mFullResImageView.setAlpha(0f);
        mFullResImageView.animate()
                .alpha(1f)
                .setDuration(shortAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Clear the thumbnail bitmap reference to save memory since it's no longer visible.
                        if (mLowResImageView != null) {
                            mLowResImageView.setImageBitmap(null);
                        }
                    }
                });

        mLoadingIndicator.animate()
                .alpha(0f)
                .setDuration(shortAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mLoadingIndicator != null) {
                            mLoadingIndicator.setVisibility(View.GONE);
                        }
                    }
                });
    }

    /**
     * Sets the default wallpaper zoom and scroll position based on a "crop surface"
     * (with extra width to account for parallax) superimposed on the screen. Shows as much of the
     * wallpaper as possible on the crop surface and align screen to crop surface such that the
     * default preview matches what would be seen by the user in the left-most home screen.
     *
     * <p>This method is called once in the Fragment lifecycle after the wallpaper asset has loaded
     * and rendered to the layout.
     */
    private void setDefaultWallpaperZoomAndScroll() {
        // Determine minimum zoom to fit maximum visible area of wallpaper on crop surface.
        float defaultWallpaperZoom =
                WallpaperCropUtils.calculateMinZoom(mRawWallpaperSize, mDefaultCropSurfaceSize);
        float minWallpaperZoom =
                WallpaperCropUtils.calculateMinZoom(mRawWallpaperSize, mScreenSize);

        Point screenToCropSurfacePosition = WallpaperCropUtils.calculateCenterPosition(
                mDefaultCropSurfaceSize, mScreenSize, true /* alignStart */, isRtl());
        Point zoomedWallpaperSize = new Point(
                Math.round(mRawWallpaperSize.x * defaultWallpaperZoom),
                Math.round(mRawWallpaperSize.y * defaultWallpaperZoom));
        Point cropSurfaceToWallpaperPosition = WallpaperCropUtils.calculateCenterPosition(
                zoomedWallpaperSize, mDefaultCropSurfaceSize, false /* alignStart */, isRtl());

        // Set min wallpaper zoom and max zoom on MosaicView widget.
        mFullResImageView.setMaxScale(Math.max(DEFAULT_WALLPAPER_MAX_ZOOM, defaultWallpaperZoom));
        mFullResImageView.setMinScale(minWallpaperZoom);

        // Set center to composite positioning between scaled wallpaper and screen.
        PointF centerPosition = new PointF(
                mRawWallpaperSize.x / 2f,
                mRawWallpaperSize.y / 2f);
        centerPosition.offset( - (screenToCropSurfacePosition.x + cropSurfaceToWallpaperPosition.x),
                - (screenToCropSurfacePosition.y + cropSurfaceToWallpaperPosition.y));

        mFullResImageView.setScaleAndCenter(defaultWallpaperZoom, centerPosition);
    }

    protected Rect calculateCropRect() {
        // Calculate Rect of wallpaper in physical pixel terms (i.e., scaled to current zoom).
        float wallpaperZoom = mFullResImageView.getScale();
        int scaledWallpaperWidth = (int) (mRawWallpaperSize.x * wallpaperZoom);
        int scaledWallpaperHeight = (int) (mRawWallpaperSize.y * wallpaperZoom);
        Rect rect = new Rect();
        mFullResImageView.visibleFileRect(rect);
        int scrollX = (int) (rect.left * wallpaperZoom);
        int scrollY = (int) (rect.top * wallpaperZoom);

        rect.set(0, 0, scaledWallpaperWidth, scaledWallpaperHeight);
        Point screenSize = ScreenSizeCalculator.getInstance().getScreenSize(
                getActivity().getWindowManager().getDefaultDisplay());
        // Crop rect should start off as the visible screen and then include extra width and height if
        // available within wallpaper at the current zoom.
        Rect cropRect = new Rect(scrollX, scrollY, scrollX + screenSize.x, scrollY + screenSize.y);

        Point defaultCropSurfaceSize = WallpaperCropUtils.getDefaultCropSurfaceSize(
                getResources(), getActivity().getWindowManager().getDefaultDisplay());
        int extraWidth = defaultCropSurfaceSize.x - screenSize.x;
        int extraHeightTopAndBottom = (int) ((defaultCropSurfaceSize.y - screenSize.y) / 2f);

        // Try to increase size of screenRect to include extra width depending on the layout direction.
        if (isRtl()) {
            cropRect.left = Math.max(cropRect.left - extraWidth, rect.left);
        } else {
            cropRect.right = Math.min(cropRect.right + extraWidth, rect.right);
        }

        // Try to increase the size of the cropRect to to include extra height.
        int availableExtraHeightTop = cropRect.top - Math.max(
                rect.top,
                cropRect.top - extraHeightTopAndBottom);
        int availableExtraHeightBottom = Math.min(
                rect.bottom,
                cropRect.bottom + extraHeightTopAndBottom) - cropRect.bottom;

        int availableExtraHeightTopAndBottom =
                Math.min(availableExtraHeightTop, availableExtraHeightBottom);
        cropRect.top -= availableExtraHeightTopAndBottom;
        cropRect.bottom += availableExtraHeightTopAndBottom;

        return cropRect;
    }

    /**
     * Sets current wallpaper to the device based on current zoom and scroll state.
     *
     * @param destination The wallpaper destination i.e. home vs. lockscreen vs. both.
     */
    private void setCurrentWallpaper(@Destination final int destination) {
        mPreferences.setPendingWallpaperSetStatus(WallpaperPreferences.WALLPAPER_SET_PENDING);

        // Save current screen rotation so we can temporarily disable rotation while setting the
        // wallpaper and restore after setting the wallpaper finishes.
        saveAndLockScreenOrientation();

        // Clear MosaicView tiles and Glide's cache and pools to reclaim memory for final cropped
        // bitmap.
        Glide.get(getActivity()).clearMemory();

        // ProgressDialog endlessly updates the UI thread, keeping it from going idle which therefore
        // causes Espresso to hang once the dialog is shown.
        if (!mTestingModeEnabled) {
            int themeResId;
            if (VERSION.SDK_INT < VERSION_CODES.LOLLIPOP) {
                themeResId = R.style.ProgressDialogThemePreL;
            } else {
                themeResId = R.style.LightDialogTheme;
            }
            mProgressDialog = new ProgressDialog(getActivity(), themeResId);

            mProgressDialog.setTitle(PROGRESS_DIALOG_NO_TITLE);
            mProgressDialog.setMessage(
                    getResources().getString(R.string.set_wallpaper_progress_message));
            mProgressDialog.setIndeterminate(PROGRESS_DIALOG_INDETERMINATE);
            mProgressDialog.show();
        }

        float wallpaperScale = mFullResImageView.getScale();
        Rect cropRect = calculateCropRect();
        mWallpaperPersister.setIndividualWallpaper(mWallpaper, mWallpaperAsset, cropRect,
                wallpaperScale, destination, new SetWallpaperCallback() {
                    @Override
                    public void onSuccess() {
                        Context context = getContext();
                        mUserEventLogger.logWallpaperSet(
                                mWallpaper.getCollectionId(context),
                                mWallpaper.getWallpaperId());
                        mPreferences.setPendingWallpaperSetStatus(
                                WallpaperPreferences.WALLPAPER_SET_NOT_PENDING);
                        mUserEventLogger.logWallpaperSetResult(
                                UserEventLogger.WALLPAPER_SET_RESULT_SUCCESS);

                        if (getActivity() == null) {
                            return;
                        }

                        if (mProgressDialog != null) {
                            mProgressDialog.dismiss();
                        }

                        restoreScreenOrientation();
                        finishActivityWithResultOk();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        mPreferences.setPendingWallpaperSetStatus(
                                WallpaperPreferences.WALLPAPER_SET_NOT_PENDING);
                        mUserEventLogger.logWallpaperSetResult(
                                UserEventLogger.WALLPAPER_SET_RESULT_FAILURE);
                        @WallpaperSetFailureReason int failureReason = ThrowableAnalyzer.isOOM(throwable)
                                ? UserEventLogger.WALLPAPER_SET_FAILURE_REASON_OOM
                                : UserEventLogger.WALLPAPER_SET_FAILURE_REASON_OTHER;
                        mUserEventLogger.logWallpaperSetFailureReason(failureReason);

                        if (getActivity() == null) {
                            return;
                        }

                        if (mProgressDialog != null) {
                            mProgressDialog.dismiss();
                        }
                        restoreScreenOrientation();
                        showSetWallpaperErrorDialog(destination);
                    }
                });
    }

    private void finishActivityWithResultOk() {
        try {
            Toast.makeText(
                    getActivity(), R.string.wallpaper_set_successfully_message, Toast.LENGTH_SHORT).show();
        } catch (NotFoundException e) {
            Log.e(TAG, "Could not show toast " + e);
        }
        getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        getActivity().setResult(Activity.RESULT_OK);
        getActivity().finish();
    }

    private void showSetWallpaperErrorDialog(@Destination int wallpaperDestination) {
        SetWallpaperErrorDialogFragment newFragment = SetWallpaperErrorDialogFragment.newInstance(
                R.string.set_wallpaper_error_message, wallpaperDestination);
        newFragment.setTargetFragment(this, UNUSED_REQUEST_CODE);

        // Show 'set wallpaper' error dialog now if it's safe to commit fragment transactions, otherwise
        // stage it for later when the hosting activity is in a state to commit fragment transactions.
        BasePreviewActivity activity = (BasePreviewActivity) getActivity();
        if (activity.isSafeToCommitFragmentTransaction()) {
            newFragment.show(getFragmentManager(), TAG_SET_WALLPAPER_ERROR_DIALOG_FRAGMENT);
        } else {
            mStagedSetWallpaperErrorDialogFragment = newFragment;
        }
    }

    /**
     * Shows 'load wallpaper' error dialog now or stage it to be shown when the hosting activity is in
     * a state that allows committing fragment transactions.
     */
    private void showLoadWallpaperErrorDialog() {
        LoadWallpaperErrorDialogFragment dialogFragment =
                LoadWallpaperErrorDialogFragment.newInstance();
        dialogFragment.setTargetFragment(PreviewFragment.this, UNUSED_REQUEST_CODE);

        // Show 'load wallpaper' error dialog now or stage it to be shown when the hosting
        // activity is in a state that allows committing fragment transactions.
        BasePreviewActivity activity = (BasePreviewActivity) getActivity();
        if (activity != null && activity.isSafeToCommitFragmentTransaction()) {
            dialogFragment.show(PreviewFragment.this.getFragmentManager(),
                    TAG_LOAD_WALLPAPER_ERROR_DIALOG_FRAGMENT);
        } else {
            mStagedLoadWallpaperErrorDialogFragment = dialogFragment;
        }
    }

    @IntDef({
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE})
    private @interface ActivityInfoScreenOrientation {
    }

    /**
     * Gets the appropriate ActivityInfo orientation for the current configuration orientation to
     * enable locking screen rotation at API levels lower than 18.
     */
    @ActivityInfoScreenOrientation
    private int getCompatActivityInfoOrientation() {
        int configOrientation = getResources().getConfiguration().orientation;
        final Display display = getActivity().getWindowManager().getDefaultDisplay();
        int naturalOrientation = Configuration.ORIENTATION_LANDSCAPE;
        switch (display.getRotation()) {
            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                // We are currently in the same basic orientation as the natural orientation.
                naturalOrientation = configOrientation;
                break;
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                // We are currently in the other basic orientation to the natural orientation.
                naturalOrientation = (configOrientation == Configuration.ORIENTATION_LANDSCAPE)
                        ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                break;
            default:
                // continue below
        }

        // Since the map starts at portrait, we need to offset if this device's natural orientation
        // is landscape.
        int indexOffset = 0;
        if (naturalOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            indexOffset = 1;
        }

        switch ((display.getRotation() + indexOffset) % 4) {
            case 0:
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case 1:
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case 2:
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case 3:
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            default:
                Log.e(TAG, "Display rotation did not correspond to a valid ActivityInfo orientation with"
                        + " display rotation: " + display.getRotation() + " and index offset: " + indexOffset
                        + ".");
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void saveAndLockScreenOrientation() {
        mCurrentScreenOrientation = getActivity().getRequestedOrientation();
        if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN_MR2) {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        } else {
            getActivity().setRequestedOrientation(getCompatActivityInfoOrientation());
        }
    }

    private void restoreScreenOrientation() {
        getActivity().setRequestedOrientation(mCurrentScreenOrientation);
    }

    /**
     * Returns whether layout direction is RTL (or false for LTR). Since native RTL layout support was
     * added in API 17, returns false for versions lower than 17.
     */
    private boolean isRtl() {
        return VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }
}