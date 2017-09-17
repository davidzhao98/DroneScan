package com.dji.FPVDemo;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
//import android.graphics.BitmapFactory;
//import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
//import android.os.Environment;
//import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseArray;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
//import android.widget.ImageView;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
//import java.io.*;
//import java.util.Date;

import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.common.error.DJIError;
import dji.common.error.DJIFlightControllerError;
import dji.common.flightcontroller.FlightOrientationMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.gimbal.CapabilityKey;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;

import dji.common.gimbal.Rotation;
import dji.sdk.gimbal.Gimbal;
import dji.common.gimbal.RotationMode;
import dji.common.util.DJIParamMinMaxCapability;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;

import static dji.common.flightcontroller.virtualstick.RollPitchControlMode.VELOCITY;


public class MainActivity extends Activity implements SurfaceTextureListener,OnClickListener{

    private static final String TAG = MainActivity.class.getName();
    protected VideoFeeder.VideoDataCallback mReceivedVideoDataCallBack = null;

    // Codec for video live view
    protected DJICodecManager mCodecManager = null;

    protected TextureView mVideoSurface = null;
    private Button mShootPhotoModeBtn, mRecordVideoModeBtn;
    private ToggleButton mRecordBtn, mCaptureBtn;
    private TextView recordingTime;

    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler();

        initUI();

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataCallBack = new VideoFeeder.VideoDataCallback() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
            }
        };

        Camera camera = FPVDemoApplication.getCameraInstance();

        if (camera != null) {

            camera.setSystemStateCallback(new SystemState.Callback() {
                @Override
                public void onUpdate(SystemState cameraSystemState) {
                    if (null != cameraSystemState) {

                        int recordTime = cameraSystemState.getCurrentVideoRecordingTimeInSeconds();
                        int minutes = (recordTime % 3600) / 60;
                        int seconds = recordTime % 60;

                        final String timeString = String.format("%02d:%02d", minutes, seconds);
                        final boolean isVideoRecording = cameraSystemState.isRecording();

                        MainActivity.this.runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                recordingTime.setText(timeString);

                                /*
                                 * Update recordingTime TextView visibility and mRecordBtn's check state
                                 */
                                if (isVideoRecording){
                                    recordingTime.setVisibility(View.VISIBLE);
                                }else
                                {
                                    recordingTime.setVisibility(View.INVISIBLE);
                                }
                            }
                        });
                    }
                }
            });

        }
    }

    protected void onProductChange() {
        initPreviewer();
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        initPreviewer();
        onProductChange();

        if(mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view){
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();
        super.onDestroy();
    }

    private void initUI() {
        // init mVideoSurface
        mVideoSurface = (TextureView)findViewById(R.id.video_previewer_surface);

        recordingTime = (TextView) findViewById(R.id.timer);
        mCaptureBtn = (ToggleButton) findViewById(R.id.btn_capture);
        mRecordBtn = (ToggleButton) findViewById(R.id.btn_record);
        mShootPhotoModeBtn = (Button) findViewById(R.id.btn_shoot_photo_mode);
        mRecordVideoModeBtn = (Button) findViewById(R.id.btn_record_video_mode);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }

        mCaptureBtn.setOnClickListener(this);
        mRecordBtn.setOnClickListener(this);
        mShootPhotoModeBtn.setOnClickListener(this);
        mRecordVideoModeBtn.setOnClickListener(this);

        recordingTime.setVisibility(View.INVISIBLE);

        mRecordBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startRecord();
                } else {
                    stopRecord();
                }
            }
        });

        mCaptureBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    captureAction();
                } else {
                    faceUp();
                }
            }
        });
    }

    private void initPreviewer() {

        BaseProduct product = FPVDemoApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {
            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                if (VideoFeeder.getInstance().getVideoFeeds() != null
                        && VideoFeeder.getInstance().getVideoFeeds().size() > 0) {
                    VideoFeeder.getInstance().getVideoFeeds().get(0).setCallback(mReceivedVideoDataCallBack);
                }
            }
        }
    }

    private void uninitPreviewer() {
        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null){
            // Reset the callback
            VideoFeeder.getInstance().getVideoFeeds().get(0).setCallback(null);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG,"onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {
            case R.id.btn_shoot_photo_mode:{
                //switchCameraMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO);
                moveDrone();
                break;
            }

            case R.id.btn_record_video_mode: {
//                switchCameraMode(SettingsDefinitions.CameraMode.RECORD_VIDEO);
                String url = getCodeCamera();
                //showToast("QR code = " + url);
                switch (url) {
                    case "forward": {
                        try {
                            showToast("Moving drone forwards");
                            moveDrone();

                        } catch (Exception e) {
                            showToast("Failed to move!");
                        }
                        break;
                    }

                        case "backwards": {
                            try {
                                //moveDrone();
                                showToast("Moving drone backwards");
                            } catch (Exception e) {
                                showToast("Failed to move!");
                            }
                        }
                        case "left": {
                            try {
                                //moveDrone();
                                showToast("Moving drone left");
                            } catch (Exception e) {
                                showToast("Failed to move!");
                            }
                        }
                        case "right": {
                            try {
                                //moveDrone();
                                showToast("Moving drone right");
                            } catch (Exception e) {
                                showToast("Failed to move!");
                            }
                        }
                        default: {
                            break;
                        }
                    }
                }
                default:
                    break;
            }
        }


    private void moveDrone(){
        FlightController myFlight = ((Aircraft) FPVDemoApplication.getProductInstance()).getFlightController();
        myFlight.setFlightOrientationMode(FlightOrientationMode.COURSE_LOCK, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("RotateGimbal", "RotateGimbal successfully");
                    showToast("IT WORKED");
                } else {
                    Log.d("PitchRangeExtension", "RotateGimbal failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
        myFlight.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("RotateGimbal", "RotateGimbal successfully");
                    showToast("IT WORKED");
                } else {
                    Log.d("PitchRangeExtension", "RotateGimbal failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
        myFlight.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        FlightControlData myDirections = new FlightControlData(1, 0, 0, 0);
        //for (int i = 0; i < 100; i++) {
            myFlight.sendVirtualStickFlightControlData(myDirections, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError == null) {
                        Log.d("RotateGimbal", "RotateGimbal successfully");
                        showToast("IT WORKED");
                    } else {
                        Log.d("PitchRangeExtension", "RotateGimbal failed");
                        showToast("I HATE THIS PLS WORK");
                    }
                }
            });
        //}
    }

    private void moveYaw(){
        FlightController myFlight = ((Aircraft) FPVDemoApplication.getProductInstance()).getFlightController();
        myFlight.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("RotateGimbal", "RotateGimbal successfully");
                    showToast("IT WORKED");
                } else {
                    Log.d("PitchRangeExtension", "RotateGimbal failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
        myFlight.setYawControlMode(YawControlMode.ANGLE);
        FlightControlData myDirections = new FlightControlData(0, 0, 180, 0);
        myFlight.sendVirtualStickFlightControlData(myDirections, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("RotateGimbal", "RotateGimbal successfully");
                    showToast("IT WORKED");
                } else {
                    Log.d("PitchRangeExtension", "RotateGimbal failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
    }

    private void switchCameraMode(SettingsDefinitions.CameraMode cameraMode){

        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.setMode(cameraMode, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {

                    if (error == null) {
                        showToast("Switch Camera Mode Succeeded");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            });
            }
    }

    private void faceUp(){
        BaseProduct product = FPVDemoApplication.getProductInstance();
        Rotation.Builder builder = new Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).time(2);
        Gimbal gimbal = product.getGimbal();
        Number maxValue = ((DJIParamMinMaxCapability) (gimbal.getCapabilities().get(CapabilityKey.ADJUST_PITCH))).getMax();
        builder.pitch(maxValue.floatValue());
        Rotation newAngle = builder.build();
        gimbal.rotate(newAngle, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("RotateGimbal", "RotateGimbal successfully");
                    showToast("IT WORKED");
                } else {
                    Log.d("PitchRangeExtension", "RotateGimbal failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
    }
    // Method for taking photo
    private void captureAction(){
        BaseProduct product = FPVDemoApplication.getProductInstance();
        Rotation.Builder builder = new Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).time(2);
        Gimbal gimbal = product.getGimbal();
        Number minValue = ((DJIParamMinMaxCapability) (gimbal.getCapabilities().get(CapabilityKey.ADJUST_PITCH))).getMin();
        builder.pitch(minValue.floatValue());
        Rotation newAngle = builder.build();
        gimbal.rotate(newAngle, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("RotateGimbal", "RotateGimbal successfully");
                    showToast("IT WORKED");
                } else {
                    Log.d("PitchRangeExtension", "RotateGimbal failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
        showToast(minValue.toString());
//        final Camera camera = FPVDemoApplication.getCameraInstance();
//        if (camera != null) {
//
//            SettingsDefinitions.ShootPhotoMode photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE; // Set the camera capture mode as Single mode
//            camera.setShootPhotoMode(photoMode, new CommonCallbacks.CompletionCallback(){
//                    @Override
//                    public void onResult(DJIError djiError) {
//                        if (null == djiError) {
//                            handler.postDelayed(new Runnable() {
//                                @Override
//                                public void run() {
//                                    camera.startShootPhoto(new CommonCallbacks.CompletionCallback() {
//                                        @Override
//                                        public void onResult(DJIError djiError) {
//                                            if (djiError == null) {
//                                                showToast("take photo: success");
//                                            } else {
//                                                showToast(djiError.getDescription());
//                                            }
//                                        }
//                                    });
//                                }
//                            }, 2000);
//                        }
//                    }
//            });
//        }

    }

    // Method for starting recording
    private void startRecord(){
        FlightController myFlight = ((Aircraft) FPVDemoApplication.getProductInstance()).getFlightController();
        myFlight.startTakeoff(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("TakeOff", "Take off successfully");
                    showToast("TAKEOFF WORKED");
                } else {
                    Log.d("TakeOff", "Take off failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
//        final Camera camera = FPVDemoApplication.getCameraInstance();
//        if (camera != null) {
//            camera.startRecordVideo(new CommonCallbacks.CompletionCallback(){
//                @Override
//                public void onResult(DJIError djiError)
//                {
//                    if (djiError == null) {
//                        showToast("Record video: success");
//                    }else {
//                        showToast(djiError.getDescription());
//                    }
//                }
//            }); // Execute the startRecordVideo API
//        }
    }

    // Method for stopping recording
    private void stopRecord(){
        FlightController myFlight = ((Aircraft) FPVDemoApplication.getProductInstance()).getFlightController();
        myFlight.startLanding(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError == null) {
                    Log.d("TakeOff", "Take off successfully");
                    showToast("TAKEOFF WORKED");
                } else {
                    Log.d("TakeOff", "Take off failed");
                    showToast("I HATE THIS PLS WORK");
                }
            }
        });
//        Camera camera = FPVDemoApplication.getCameraInstance();
//        if (camera != null) {
//            camera.stopRecordVideo(new CommonCallbacks.CompletionCallback(){
//
//                @Override
//                public void onResult(DJIError djiError)
//                {
//                    if(djiError == null) {
//                        showToast("Stop recording: success");
//                    }else {
//                        showToast(djiError.getDescription());
//                    }
//                }
//            }); // Execute the stopRecordVideo API
//        }

    }

     //Method for taking a QR code off the camera
    private String getCodeCamera() {
//        showToast("inside getCodeCamera()");
        Barcode thisCode = null;
        Bitmap qrMap = mVideoSurface.getBitmap();

//        showToast("took screenshot (I hope)");

        try {

            BarcodeDetector detector = new BarcodeDetector.Builder(getApplicationContext()).setBarcodeFormats(Barcode.DATA_MATRIX | Barcode.QR_CODE).build();

//            showToast("Created BarcodeDetector");

            Frame frame = new Frame.Builder().setBitmap(qrMap).build();

//            showToast("Created frame");

            SparseArray<Barcode> barcodesAry = detector.detect(frame);

//            showToast("Created barcode array");

            thisCode = barcodesAry.valueAt(0);

//            showToast("Set thisCode to the first array value");

            showToast("Barcode = " + thisCode.rawValue);
        }
        catch(Exception e){
            e.printStackTrace();
            showToast("no QR code detected");
        }

        return thisCode.rawValue;
    }
}
