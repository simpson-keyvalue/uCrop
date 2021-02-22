package com.yalantis.ucrop;

import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.AutoTransition;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;

import com.google.android.material.tabs.TabLayout;
import com.yalantis.ucrop.callback.BitmapCropCallback;
import com.yalantis.ucrop.model.AspectRatio;
import com.yalantis.ucrop.model.ImageTask;
import com.yalantis.ucrop.util.SelectedStateListDrawable;
import com.yalantis.ucrop.view.CropImageView;
import com.yalantis.ucrop.view.GestureCropImageView;
import com.yalantis.ucrop.view.ImageList.ImageAdapter;
import com.yalantis.ucrop.view.ImageList.ImageTaskListOwner;
import com.yalantis.ucrop.view.ImageList.ImageTaskListener;
import com.yalantis.ucrop.view.OverlayView;
import com.yalantis.ucrop.view.TransformImageView;
import com.yalantis.ucrop.view.UCropView;
import com.yalantis.ucrop.view.widget.AspectRatioTextView;
import com.yalantis.ucrop.view.widget.HorizontalProgressWheelView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Created by Oleksii Shliama (https://github.com/shliama).
 */

@SuppressWarnings("ConstantConditions")
public class UCropActivity extends AppCompatActivity implements ImageTaskListOwner, BitmapCropCallback {

    public static final int DEFAULT_COMPRESS_QUALITY = 90;
    public static final Bitmap.CompressFormat DEFAULT_COMPRESS_FORMAT = Bitmap.CompressFormat.JPEG;

    public static final int NONE = 0;
    public static final int SCALE = 1;
    public static final int ROTATE = 2;
    public static final int ALL = 3;

    @IntDef({NONE, SCALE, ROTATE, ALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GestureTypes {

    }

    private static final String TAG = "UCropActivity";
    private static final long CONTROLS_ANIMATION_DURATION = 50;
    private static final int TABS_COUNT = 3;
    private static final int SCALE_WIDGET_SENSITIVITY_COEFFICIENT = 15000;
    private static final int ROTATE_WIDGET_SENSITIVITY_COEFFICIENT = 42;

    private String mToolbarTitle;

    // Enables dynamic coloring
    private int mStatusBarColor;
    private int mActiveControlsWidgetColor;
    @ColorInt
    private int mRootViewBackgroundColor;
    private int mLogoColor;

    private boolean mShowBottomControls;
    private boolean mShowLoader = true;

    private UCropView mUCropView;
    private GestureCropImageView mGestureCropImageView;
    private OverlayView mOverlayView;
    private TabLayout tabLayout;
    private ViewGroup mLayoutAspectRatio, mLayoutRotate, mLayoutScale;
    private Button cancelButton, saveButton;
    private List<ViewGroup> mCropAspectRatioViews = new ArrayList<>();
    private TextView mTextViewRotateAngle, mTextViewScalePercent;
    private View mBlockingView;
    private ImageTaskListener mTaskListener;
    private ArrayList<Uri> mResultArray = new ArrayList<Uri>();

    private Transition mControlsTransition;

    private Bitmap.CompressFormat mCompressFormat = DEFAULT_COMPRESS_FORMAT;
    private int mCompressQuality = DEFAULT_COMPRESS_QUALITY;
    private int[] mAllowedGestures = new int[]{SCALE, ROTATE, ALL};

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ucrop_activity_photobox);

        final Intent intent = getIntent();
        setupViews(intent);
        setInitialState();
        addBlockingView();

        if (intent.hasExtra(UCrop.EXTRA_LIST_INPUT_URI))
            setupImageList(intent);
        else
            setImageData(intent);
    }

    @Override
    public void onSelectionChange(ImageTask currentTask) {
        Intent bundle = new Intent();
        bundle.putExtra(UCrop.EXTRA_INPUT_URI, currentTask.getSource());
        bundle.putExtra(UCrop.EXTRA_OUTPUT_URI, currentTask.getDestination());
        setImageData(bundle);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mGestureCropImageView != null) {
            mGestureCropImageView.cancelAllAnimations();
        }
    }

    /**
     * This method extracts all data from the incoming intent and setups views properly.
     */
    private void setImageData(@NonNull Intent intent) {
        Uri inputUri = intent.getParcelableExtra(UCrop.EXTRA_INPUT_URI);
        Uri outputUri = intent.getParcelableExtra(UCrop.EXTRA_OUTPUT_URI);
        processOptions(intent);

        if (inputUri != null && outputUri != null) {
            try {
                mGestureCropImageView.setImageUri(inputUri, outputUri);
            } catch (Exception e) {
                setResultError(e);
                finish();
            }
        } else {
            setResultError(new NullPointerException(getString(R.string.ucrop_error_input_data_is_absent)));
            finish();
        }
    }

    /**
     * This method extracts {@link com.yalantis.ucrop.UCrop.Options #optionsBundle} from incoming intent
     * and setups Activity, {@link OverlayView} and {@link CropImageView} properly.
     */
    @SuppressWarnings("deprecation")
    private void processOptions(@NonNull Intent intent) {
        // Bitmap compression options
        String compressionFormatName = intent.getStringExtra(UCrop.Options.EXTRA_COMPRESSION_FORMAT_NAME);
        Bitmap.CompressFormat compressFormat = null;
        if (!TextUtils.isEmpty(compressionFormatName)) {
            compressFormat = Bitmap.CompressFormat.valueOf(compressionFormatName);
        }
        mCompressFormat = (compressFormat == null) ? DEFAULT_COMPRESS_FORMAT : compressFormat;

        mCompressQuality = intent.getIntExtra(UCrop.Options.EXTRA_COMPRESSION_QUALITY, UCropActivity.DEFAULT_COMPRESS_QUALITY);

        // Gestures options
        int[] allowedGestures = intent.getIntArrayExtra(UCrop.Options.EXTRA_ALLOWED_GESTURES);
        if (allowedGestures != null && allowedGestures.length == TABS_COUNT) {
            mAllowedGestures = allowedGestures;
        }

        // Crop image view options
        mGestureCropImageView.setMaxBitmapSize(intent.getIntExtra(UCrop.Options.EXTRA_MAX_BITMAP_SIZE, CropImageView.DEFAULT_MAX_BITMAP_SIZE));
        mGestureCropImageView.setMaxScaleMultiplier(intent.getFloatExtra(UCrop.Options.EXTRA_MAX_SCALE_MULTIPLIER, CropImageView.DEFAULT_MAX_SCALE_MULTIPLIER));
        mGestureCropImageView.setImageToWrapCropBoundsAnimDuration(intent.getIntExtra(UCrop.Options.EXTRA_IMAGE_TO_CROP_BOUNDS_ANIM_DURATION, CropImageView.DEFAULT_IMAGE_TO_CROP_BOUNDS_ANIM_DURATION));

        // Overlay view options
        mOverlayView.setFreestyleCropEnabled(intent.getBooleanExtra(UCrop.Options.EXTRA_FREE_STYLE_CROP, OverlayView.DEFAULT_FREESTYLE_CROP_MODE != OverlayView.FREESTYLE_CROP_MODE_DISABLE));

        mOverlayView.setDimmedColor(intent.getIntExtra(UCrop.Options.EXTRA_DIMMED_LAYER_COLOR, getResources().getColor(R.color.ucrop_color_default_dimmed)));
        mOverlayView.setCircleDimmedLayer(intent.getBooleanExtra(UCrop.Options.EXTRA_CIRCLE_DIMMED_LAYER, OverlayView.DEFAULT_CIRCLE_DIMMED_LAYER));

        mOverlayView.setShowCropFrame(intent.getBooleanExtra(UCrop.Options.EXTRA_SHOW_CROP_FRAME, OverlayView.DEFAULT_SHOW_CROP_FRAME));
        mOverlayView.setCropFrameColor(intent.getIntExtra(UCrop.Options.EXTRA_CROP_FRAME_COLOR, getResources().getColor(R.color.ucrop_color_default_crop_frame)));
        mOverlayView.setCropFrameStrokeWidth(intent.getIntExtra(UCrop.Options.EXTRA_CROP_FRAME_STROKE_WIDTH, getResources().getDimensionPixelSize(R.dimen.ucrop_default_crop_frame_stoke_width)));

        mOverlayView.setShowCropGrid(intent.getBooleanExtra(UCrop.Options.EXTRA_SHOW_CROP_GRID, OverlayView.DEFAULT_SHOW_CROP_GRID));
        mOverlayView.setCropGridRowCount(intent.getIntExtra(UCrop.Options.EXTRA_CROP_GRID_ROW_COUNT, OverlayView.DEFAULT_CROP_GRID_ROW_COUNT));
        mOverlayView.setCropGridColumnCount(intent.getIntExtra(UCrop.Options.EXTRA_CROP_GRID_COLUMN_COUNT, OverlayView.DEFAULT_CROP_GRID_COLUMN_COUNT));
        mOverlayView.setCropGridColor(intent.getIntExtra(UCrop.Options.EXTRA_CROP_GRID_COLOR, getResources().getColor(R.color.ucrop_color_default_crop_grid)));
        mOverlayView.setCropGridCornerColor(intent.getIntExtra(UCrop.Options.EXTRA_CROP_GRID_CORNER_COLOR, getResources().getColor(R.color.ucrop_color_default_crop_grid)));
        mOverlayView.setCropGridStrokeWidth(intent.getIntExtra(UCrop.Options.EXTRA_CROP_GRID_STROKE_WIDTH, getResources().getDimensionPixelSize(R.dimen.ucrop_default_crop_grid_stoke_width)));

        // Aspect ratio options
        float aspectRatioX = intent.getFloatExtra(UCrop.EXTRA_ASPECT_RATIO_X, 0);
        float aspectRatioY = intent.getFloatExtra(UCrop.EXTRA_ASPECT_RATIO_Y, 0);

        int aspectRationSelectedByDefault = intent.getIntExtra(UCrop.Options.EXTRA_ASPECT_RATIO_SELECTED_BY_DEFAULT, 0);
        ArrayList<AspectRatio> aspectRatioList = intent.getParcelableArrayListExtra(UCrop.Options.EXTRA_ASPECT_RATIO_OPTIONS);

        if (aspectRatioX > 0 && aspectRatioY > 0) {
            if (tabLayout.getTabCount() > 2) {
               tabLayout.removeTabAt(0);
          }
            mGestureCropImageView.setTargetAspectRatio(aspectRatioX / aspectRatioY);
        } else if (aspectRatioList != null && aspectRationSelectedByDefault < aspectRatioList.size()) {
            mGestureCropImageView.setTargetAspectRatio(aspectRatioList.get(aspectRationSelectedByDefault).getAspectRatioX() /
                    aspectRatioList.get(aspectRationSelectedByDefault).getAspectRatioY());
        } else {
            mGestureCropImageView.setTargetAspectRatio(CropImageView.SOURCE_IMAGE_ASPECT_RATIO);
        }

        // Result bitmap max size options
        int maxSizeX = intent.getIntExtra(UCrop.EXTRA_MAX_SIZE_X, 0);
        int maxSizeY = intent.getIntExtra(UCrop.EXTRA_MAX_SIZE_Y, 0);

        if (maxSizeX > 0 && maxSizeY > 0) {
            mGestureCropImageView.setMaxResultImageSizeX(maxSizeX);
            mGestureCropImageView.setMaxResultImageSizeY(maxSizeY);
        }
    }

    private void setupViews(@NonNull Intent intent) {
        mStatusBarColor = intent.getIntExtra(UCrop.Options.EXTRA_STATUS_BAR_COLOR, ContextCompat.getColor(this, R.color.ucrop_color_statusbar));
        mActiveControlsWidgetColor = intent.getIntExtra(UCrop.Options.EXTRA_UCROP_COLOR_CONTROLS_WIDGET_ACTIVE, ContextCompat.getColor(this, R.color.ucrop_color_active_controls_color));

        mLogoColor = intent.getIntExtra(UCrop.Options.EXTRA_UCROP_LOGO_COLOR, ContextCompat.getColor(this, R.color.ucrop_color_default_logo));
        mShowBottomControls = !intent.getBooleanExtra(UCrop.Options.EXTRA_HIDE_BOTTOM_CONTROLS, false);
        mRootViewBackgroundColor = intent.getIntExtra(UCrop.Options.EXTRA_UCROP_ROOT_VIEW_BACKGROUND_COLOR, ContextCompat.getColor(this, R.color.ucrop_color_crop_background));

        initiateRootViews();

        if (mShowBottomControls) {

            ViewGroup viewGroup = findViewById(R.id.ucrop_photobox);
            ViewGroup wrapper = viewGroup.findViewById(R.id.controls_wrapper);
            wrapper.setVisibility(View.VISIBLE);
            LayoutInflater.from(this).inflate(R.layout.ucrop_controls, wrapper, true);

            mControlsTransition = new AutoTransition();
            mControlsTransition.setDuration(CONTROLS_ANIMATION_DURATION);

            cancelButton = findViewById(R.id.cancel_button);
            cancelButton.setOnClickListener(cancelClickListener);
            saveButton = findViewById(R.id.save_button);
            saveButton.setOnClickListener(saveClickListener);
            tabLayout = findViewById(R.id.tab_layout);
            tabLayout.addOnTabSelectedListener(tabSelectedListener);

            mLayoutAspectRatio = findViewById(R.id.layout_aspect_ratio);
            mLayoutRotate = findViewById(R.id.layout_rotate_wheel);
            mLayoutScale = findViewById(R.id.layout_scale_wheel);

            setupAspectRatioWidget(intent);
            setupRotateWidget();
            setupScaleWidget();
        }
    }

    private void initiateRootViews() {
        mUCropView = findViewById(R.id.ucrop);
        mGestureCropImageView = mUCropView.getCropImageView();
        mOverlayView = mUCropView.getOverlayView();

        mGestureCropImageView.setTransformImageListener(mImageListener);

        ((ImageView) findViewById(R.id.image_view_logo)).setColorFilter(mLogoColor, PorterDuff.Mode.SRC_ATOP);

        findViewById(R.id.ucrop_frame).setBackgroundColor(mRootViewBackgroundColor);
        if (!mShowBottomControls) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) findViewById(R.id.ucrop_frame).getLayoutParams();
            params.bottomMargin = 0;
            findViewById(R.id.ucrop_frame).requestLayout();
        }
    }

    private TransformImageView.TransformImageListener mImageListener = new TransformImageView.TransformImageListener() {
        @Override
        public void onRotate(float currentAngle) {

            setAngleText(currentAngle);
        }

        @Override
        public void onScale(float currentScale) {

            setScaleText(currentScale);
        }

        @Override
        public void onLoadComplete() {
            mUCropView.animate().alpha(1).setDuration(300).setInterpolator(new AccelerateInterpolator());
            mBlockingView.setClickable(false);
            mShowLoader = false;
            saveButton.setEnabled(true);
            supportInvalidateOptionsMenu();
        }

        @Override
        public void onLoadFailure(@NonNull Exception e) {
            setResultError(e);
            finish();
        }

    };



    /**
     * Sets status-bar color for L devices.
     *
     * @param color - status-bar color
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setStatusBarColor(@ColorInt int color) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final Window window = getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(color);
            }
        }
    }

    private void setupAspectRatioWidget(@NonNull Intent intent) {

        int aspectRationSelectedByDefault = intent.getIntExtra(UCrop.Options.EXTRA_ASPECT_RATIO_SELECTED_BY_DEFAULT, 0);
        ArrayList<AspectRatio> aspectRatioList = intent.getParcelableArrayListExtra(UCrop.Options.EXTRA_ASPECT_RATIO_OPTIONS);

        if (aspectRatioList == null || aspectRatioList.isEmpty()) {
            aspectRationSelectedByDefault = 2;

            aspectRatioList = new ArrayList<>();
            aspectRatioList.add(new AspectRatio(null, 1, 1));
            aspectRatioList.add(new AspectRatio(null, 3, 4));
            aspectRatioList.add(new AspectRatio(getString(R.string.ucrop_label_original).toUpperCase(),
                    CropImageView.SOURCE_IMAGE_ASPECT_RATIO, CropImageView.SOURCE_IMAGE_ASPECT_RATIO));
            aspectRatioList.add(new AspectRatio(null, 3, 2));
            aspectRatioList.add(new AspectRatio(null, 16, 9));
        }

        LinearLayout wrapperAspectRatioList = findViewById(R.id.layout_aspect_ratio);

        FrameLayout wrapperAspectRatio;
        AspectRatioTextView aspectRatioTextView;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.weight = 1;
        for (AspectRatio aspectRatio : aspectRatioList) {
            wrapperAspectRatio = (FrameLayout) getLayoutInflater().inflate(R.layout.ucrop_aspect_ratio, null);
            wrapperAspectRatio.setLayoutParams(lp);
            aspectRatioTextView = ((AspectRatioTextView) wrapperAspectRatio.getChildAt(0));
            aspectRatioTextView.setActiveColor(mActiveControlsWidgetColor);
            aspectRatioTextView.setAspectRatio(aspectRatio);

            wrapperAspectRatioList.addView(wrapperAspectRatio);
            mCropAspectRatioViews.add(wrapperAspectRatio);
        }

        mCropAspectRatioViews.get(aspectRationSelectedByDefault).setSelected(true);

        for (ViewGroup cropAspectRatioView : mCropAspectRatioViews) {
            cropAspectRatioView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mGestureCropImageView.setTargetAspectRatio(
                            ((AspectRatioTextView) ((ViewGroup) v).getChildAt(0)).getAspectRatio(v.isSelected()));
                    mGestureCropImageView.setImageToWrapCropBounds();
                    if (!v.isSelected()) {
                        for (ViewGroup cropAspectRatioView : mCropAspectRatioViews) {
                            cropAspectRatioView.setSelected(cropAspectRatioView == v);
                        }
                    }
                }
            });
        }
    }

    private void setupRotateWidget() {
        mTextViewRotateAngle = findViewById(R.id.text_view_rotate);
        ((HorizontalProgressWheelView) findViewById(R.id.rotate_scroll_wheel))
                .setScrollingListener(new HorizontalProgressWheelView.ScrollingListener() {
                    @Override
                    public void onScroll(float delta, float totalDistance) {
                        mGestureCropImageView.postRotate(delta / ROTATE_WIDGET_SENSITIVITY_COEFFICIENT);
                    }

                    @Override
                    public void onScrollEnd() {
                        mGestureCropImageView.setImageToWrapCropBounds();
                    }

                    @Override
                    public void onScrollStart() {
                        mGestureCropImageView.cancelAllAnimations();
                    }
                });

        ((HorizontalProgressWheelView) findViewById(R.id.rotate_scroll_wheel)).setMiddleLineColor(mActiveControlsWidgetColor);


        findViewById(R.id.wrapper_reset_rotate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetRotation();
            }
        });
        findViewById(R.id.wrapper_rotate_by_angle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateByAngle(90);
            }
        });
       setAngleTextColor(mActiveControlsWidgetColor);
    }

    private void setupScaleWidget() {
        mTextViewScalePercent = findViewById(R.id.text_view_scale);
        ((HorizontalProgressWheelView) findViewById(R.id.scale_scroll_wheel))
                .setScrollingListener(new HorizontalProgressWheelView.ScrollingListener() {
                    @Override
                    public void onScroll(float delta, float totalDistance) {
                        if (delta > 0) {
                            mGestureCropImageView.zoomInImage(mGestureCropImageView.getCurrentScale()
                                    + delta * ((mGestureCropImageView.getMaxScale() - mGestureCropImageView.getMinScale()) / SCALE_WIDGET_SENSITIVITY_COEFFICIENT));
                        } else {
                            mGestureCropImageView.zoomOutImage(mGestureCropImageView.getCurrentScale()
                                    + delta * ((mGestureCropImageView.getMaxScale() - mGestureCropImageView.getMinScale()) / SCALE_WIDGET_SENSITIVITY_COEFFICIENT));
                        }
                    }

                    @Override
                    public void onScrollEnd() {
                        mGestureCropImageView.setImageToWrapCropBounds();
                    }

                    @Override
                    public void onScrollStart() {
                        mGestureCropImageView.cancelAllAnimations();
                    }
                });
        ((HorizontalProgressWheelView) findViewById(R.id.scale_scroll_wheel)).setMiddleLineColor(mActiveControlsWidgetColor);

        setScaleTextColor(mActiveControlsWidgetColor);
    }

    private void setAngleText(float angle) {
        if (mTextViewRotateAngle != null) {
            mTextViewRotateAngle.setText(String.format(Locale.getDefault(), "%.1f°", angle));
        }
    }

    private void setAngleTextColor(int textColor) {
        if (mTextViewRotateAngle != null) {
            mTextViewRotateAngle.setTextColor(textColor);
        }
    }

    private void setScaleText(float scale) {
        if (mTextViewScalePercent != null) {
            mTextViewScalePercent.setText(String.format(Locale.getDefault(), "%d%%", (int) (scale * 100)));
        }
    }

    private void setScaleTextColor(int textColor) {
        if (mTextViewScalePercent != null) {
            mTextViewScalePercent.setTextColor(textColor);
        }
    }

    private void resetRotation() {
        mGestureCropImageView.postRotate(-mGestureCropImageView.getCurrentAngle());
        mGestureCropImageView.setImageToWrapCropBounds();
    }

    private void rotateByAngle(int angle) {
        mGestureCropImageView.postRotate(angle);
        mGestureCropImageView.setImageToWrapCropBounds();
    }


    private final TabLayout.OnTabSelectedListener tabSelectedListener = new TabLayout.OnTabSelectedListener() {
        @Override
        public void onTabSelected(TabLayout.Tab tab) {
            setWidgetState(tab.getPosition());
        }

        @Override
        public void onTabUnselected(TabLayout.Tab tab) {

        }

        @Override
        public void onTabReselected(TabLayout.Tab tab) {

        }
    };

    private final View.OnClickListener cancelClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            onBackPressed();
        }
    };

    private final View.OnClickListener saveClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            cropAndSaveImage();
        }
    };

    private void setInitialState() {
        if (mShowBottomControls) {
            if (tabLayout.getSelectedTabPosition() == 0) {
                setWidgetState(0);
            } else {
                setWidgetState(2);
            }
        } else {
            setAllowedGestures(0);
        }
    }

    private void setWidgetState(int stateViewId) {
        mLayoutAspectRatio.setVisibility(stateViewId == 0 ? View.VISIBLE : View.GONE);
        mLayoutRotate.setVisibility(stateViewId == 1 ? View.VISIBLE : View.GONE);
        mLayoutScale.setVisibility(stateViewId == 2 ? View.VISIBLE : View.GONE);

        if (stateViewId == 2) {
            setAllowedGestures(0);
        } else if (stateViewId == 1) {
            setAllowedGestures(1);
        } else {
            setAllowedGestures(2);
        }
    }

    private void setAllowedGestures(int tab) {
        mGestureCropImageView.setScaleEnabled(mAllowedGestures[tab] == ALL || mAllowedGestures[tab] == SCALE);
        mGestureCropImageView.setRotateEnabled(mAllowedGestures[tab] == ALL || mAllowedGestures[tab] == ROTATE);
    }

    /**
     * Adds view that covers everything below the Toolbar.
     * When it's clickable - user won't be able to click/touch anything below the Toolbar.
     * Need to block user input while loading and cropping an image.
     */
    private void addBlockingView() {
        if (mBlockingView == null) {
            mBlockingView = new View(this);
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.addRule(RelativeLayout.BELOW, R.id.toolbar);
            mBlockingView.setLayoutParams(lp);
            mBlockingView.setClickable(true);
        }

        ((RelativeLayout) findViewById(R.id.ucrop_photobox)).addView(mBlockingView);
    }

    protected void cropAndSaveImage() {
        mBlockingView.setClickable(true);
        mShowLoader = true;
        saveButton.setEnabled(false);
        supportInvalidateOptionsMenu();

        mGestureCropImageView.cropAndSaveImage(mCompressFormat, mCompressQuality, this);
    }

    @Override
    public void onBitmapCropped(@NonNull Uri resultUri, int offsetX, int offsetY, int imageWidth, int imageHeight) {
        if (getIntent().hasExtra(UCrop.EXTRA_LIST_INPUT_URI))
            mResultArray.add(resultUri);
        else
            setResultUri(resultUri, mGestureCropImageView.getTargetAspectRatio(), offsetX, offsetY, imageWidth, imageHeight);
        mBlockingView.setClickable(false);
        mShowLoader = false;
        saveButton.setEnabled(true);
        supportInvalidateOptionsMenu();
        if (mTaskListener == null)
            finish();
        else if (mTaskListener.onImageTaskFinish()) {
            setResultUriArray(mResultArray);
            finish();
        }
    }

    @Override
    public void onCropFailure(@NonNull Throwable t) {
        setResultError(t);
        mBlockingView.setClickable(false);
        mShowLoader = false;
        saveButton.setEnabled(true);
        supportInvalidateOptionsMenu();
        if (mTaskListener == null)
            finish();
        else if (mTaskListener.onImageTaskFinish()) {
            setResultUriArray(mResultArray);
            finish();
        }
    }

    protected void setResultUri(Uri uri, float resultAspectRatio, int offsetX, int offsetY, int imageWidth, int imageHeight) {
        setResult(RESULT_OK, new Intent()
                .putExtra(UCrop.EXTRA_OUTPUT_URI, uri)
                .putExtra(UCrop.EXTRA_OUTPUT_CROP_ASPECT_RATIO, resultAspectRatio)
                .putExtra(UCrop.EXTRA_OUTPUT_IMAGE_WIDTH, imageWidth)
                .putExtra(UCrop.EXTRA_OUTPUT_IMAGE_HEIGHT, imageHeight)
                .putExtra(UCrop.EXTRA_OUTPUT_OFFSET_X, offsetX)
                .putExtra(UCrop.EXTRA_OUTPUT_OFFSET_Y, offsetY)
        );
    }

    protected void setResultUriArray(ArrayList<Uri> uris) {
        setResult(RESULT_OK, new Intent()
                .putParcelableArrayListExtra(UCrop.EXTRA_LIST_OUTPUT_URI, uris)
        );
    }

    protected void setResultError(Throwable throwable) {
        setResult(UCrop.RESULT_ERROR, new Intent().putExtra(UCrop.EXTRA_ERROR, throwable));
    }

    private void setupImageList(Intent intent) {
        ViewGroup wrapper = findViewById(R.id.controls_wrapper);
        RecyclerView imgList = wrapper.findViewById(R.id.list_img);

        imgList.setVisibility(View.VISIBLE);
        ArrayList<Uri> inputUris = (ArrayList<Uri>) intent.getSerializableExtra(UCrop.EXTRA_LIST_INPUT_URI);
        ArrayList<Uri> outputUris = (ArrayList<Uri>) intent.getSerializableExtra(UCrop.EXTRA_LIST_OUTPUT_URI);
        ArrayList<ImageTask> imageTasks = new ArrayList<ImageTask>();
        for (int i = 0; i < inputUris.size(); i++) {
            imageTasks.add(new ImageTask(inputUris.get(i), outputUris.get(i)));
        }
        ImageAdapter adapter = new ImageAdapter(imageTasks, this);
        mTaskListener = adapter;
        imgList.setAdapter(adapter);
        imgList.setLayoutManager(new LinearLayoutManager(imgList.getContext(), LinearLayoutManager.HORIZONTAL, false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            imgList.setForegroundGravity(Gravity.CENTER_HORIZONTAL);
        }
    }
}
