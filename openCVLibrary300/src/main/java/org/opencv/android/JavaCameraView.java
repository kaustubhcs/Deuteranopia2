package org.opencv.android;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup.LayoutParams;
import java.util.List;
import android.widget.Toast;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.core.Core;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * This class is an implementation of the Bridge View between OpenCV and Java Camera.
 * This class relays on the functionality available in base class and only implements
 * required functions:
 * connectCamera - opens Java camera and sets the PreviewCallback to be delivered.
 * disconnectCamera - closes the camera and stops preview.
 * When frame is delivered via callback from Camera - it processed via OpenCV to be
 * converted to RGBA32 and then passed to the external callback for modifications if required.
 */
public class JavaCameraView extends CameraBridgeViewBase implements PreviewCallback {

    private static final int MAGIC_TEXTURE_ID = 10;
    private static final String TAG = "JavaCameraView";

    private byte mBuffer[];
    private Mat[] mFrameChain;
    private int mChainIdx = 0;
    private Thread mThread;
    private boolean mStopThread;

    protected Camera mCamera;
    protected JavaCameraFrame[] mCameraFrame;
    private SurfaceTexture mSurfaceTexture;

    public static class JavaCameraSizeAccessor implements ListItemAccessor {

        @Override
        public int getWidth(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.width;
        }

        @Override
        public int getHeight(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.height;
        }
    }

    public JavaCameraView(Context context, int cameraId) {
        super(context, cameraId);
    }

    public JavaCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected boolean initializeCamera(int width, int height) {
        Log.d(TAG, "Initialize java camera");
        boolean result = true;
        synchronized (this) {
            mCamera = null;

            if (mCameraIndex == CAMERA_ID_ANY) {
                Log.d(TAG, "Trying to open camera with old open()");
                try {
                    mCamera = Camera.open();
                }
                catch (Exception e){
                    Log.e(TAG, "Camera is not available (in use or does not exist): " + e.getLocalizedMessage());
                }

                if(mCamera == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    boolean connected = false;
                    for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                        Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(camIdx) + ")");
                        try {
                            mCamera = Camera.open(camIdx);
                            connected = true;
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Camera #" + camIdx + "failed to open: " + e.getLocalizedMessage());
                        }
                        if (connected) break;
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    int localCameraIndex = mCameraIndex;
                    if (mCameraIndex == CAMERA_ID_BACK) {
                        Log.i(TAG, "Trying to open back camera");
                        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                        for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                            Camera.getCameraInfo( camIdx, cameraInfo );
                            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                                localCameraIndex = camIdx;
                                break;
                            }
                        }
                    } else if (mCameraIndex == CAMERA_ID_FRONT) {
                        Log.i(TAG, "Trying to open front camera");
                        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                        for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                            Camera.getCameraInfo( camIdx, cameraInfo );
                            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                localCameraIndex = camIdx;
                                break;
                            }
                        }
                    }
                    if (localCameraIndex == CAMERA_ID_BACK) {
                        Log.e(TAG, "Back camera not found!");
                    } else if (localCameraIndex == CAMERA_ID_FRONT) {
                        Log.e(TAG, "Front camera not found!");
                    } else {
                        Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(localCameraIndex) + ")");
                        try {
                            mCamera = Camera.open(localCameraIndex);
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Camera #" + localCameraIndex + "failed to open: " + e.getLocalizedMessage());
                        }
                    }
                }
            }

            if (mCamera == null)
                return false;

            /* Now set camera parameters */
            try {
                Camera.Parameters params = mCamera.getParameters();
                Log.d(TAG, "getSupportedPreviewSizes()");
                List<android.hardware.Camera.Size> sizes = params.getSupportedPreviewSizes();

                if (sizes != null) {
                    /* Select the size that fits surface considering maximum size allowed */
                    Size frameSize = calculateCameraFrameSize(sizes, new JavaCameraSizeAccessor(), width, height);

                    params.setPreviewFormat(ImageFormat.NV21);
                    Log.d(TAG, "Set preview size to " + Integer.valueOf((int)frameSize.width) + "x" + Integer.valueOf((int)frameSize.height));
                    params.setPreviewSize((int)frameSize.width, (int)frameSize.height);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH && !android.os.Build.MODEL.equals("GT-I9100"))
                        params.setRecordingHint(true);

                    List<String> FocusModes = params.getSupportedFocusModes();
                    if (FocusModes != null && FocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                    {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    }

                    mCamera.setParameters(params);
                    params = mCamera.getParameters();

                    mFrameWidth = params.getPreviewSize().width;
                    mFrameHeight = params.getPreviewSize().height;

                    if ((getLayoutParams().width == LayoutParams.MATCH_PARENT) && (getLayoutParams().height == LayoutParams.MATCH_PARENT))
                        mScale = Math.min(((float)height)/mFrameHeight, ((float)width)/mFrameWidth);
                    else
                        mScale = 0;

                    if (mFpsMeter != null) {
                        mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
                    }

                    int size = mFrameWidth * mFrameHeight;
                    size  = size * ImageFormat.getBitsPerPixel(params.getPreviewFormat()) / 8;
                    mBuffer = new byte[size];

                    mCamera.addCallbackBuffer(mBuffer);
                    mCamera.setPreviewCallbackWithBuffer(this);

                    mFrameChain = new Mat[2];
                    mFrameChain[0] = new Mat(mFrameHeight + (mFrameHeight/2), mFrameWidth, CvType.CV_8UC1);
                    mFrameChain[1] = new Mat(mFrameHeight + (mFrameHeight/2), mFrameWidth, CvType.CV_8UC1);

                    AllocateCache();

                    mCameraFrame = new JavaCameraFrame[2];
                    mCameraFrame[0] = new JavaCameraFrame(mFrameChain[0], mFrameWidth, mFrameHeight);
                    mCameraFrame[1] = new JavaCameraFrame(mFrameChain[1], mFrameWidth, mFrameHeight);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        mSurfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
                        mCamera.setPreviewTexture(mSurfaceTexture);
                    } else
                       mCamera.setPreviewDisplay(null);

                    /* Finally we are ready to start the preview */
                    Log.d(TAG, "startPreview");
                    mCamera.startPreview();
                }
                else
                    result = false;
            } catch (Exception e) {
                result = false;
                e.printStackTrace();
            }
        }

        return result;
    }

    protected void releaseCamera() {
        synchronized (this) {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);

                mCamera.release();
            }
            mCamera = null;
            if (mFrameChain != null) {
                mFrameChain[0].release();
                mFrameChain[1].release();
            }
            if (mCameraFrame != null) {
                mCameraFrame[0].release();
                mCameraFrame[1].release();
            }
        }
    }

    private boolean mCameraFrameReady = false;

    @Override
    protected boolean connectCamera(int width, int height) {

        /* 1. We need to instantiate camera
         * 2. We need to start thread which will be getting frames
         */
        /* First step - initialize camera connection */
        Log.d(TAG, "Connecting to camera");
        if (!initializeCamera(width, height))
            return false;

        mCameraFrameReady = false;

        /* now we can start update thread */
        Log.d(TAG, "Starting processing thread");
        mStopThread = false;
        mThread = new Thread(new CameraWorker());
        mThread.start();

        return true;
    }

    @Override
    protected void disconnectCamera() {
        /* 1. We need to stop thread which updating the frames
         * 2. Stop camera and release it
         */
        Log.d(TAG, "Disconnecting from camera");
        try {
            mStopThread = true;
            Log.d(TAG, "Notify thread");
            synchronized (this) {
                this.notify();
            }
            Log.d(TAG, "Wating for thread");
            if (mThread != null)
                mThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            mThread =  null;
        }

        /* Now release camera */
        releaseCamera();

        mCameraFrameReady = false;
    }

    @Override
    public void onPreviewFrame(byte[] frame, Camera arg1) {
        Log.d(TAG, "Preview Frame received. Frame size: " + frame.length);

        synchronized (this) {
            byte[] frame2 = frame.clone();

            mFrameChain[mChainIdx].put(0, 0, frame);
            mCameraFrameReady = true;
            this.notify();
        }
        if (mCamera != null)
            mCamera.addCallbackBuffer(mBuffer);
    }

    private class JavaCameraFrame implements CvCameraViewFrame {


        @Override
        public Mat gray() {
            Log.i("KTB", "Mat Gray Entered");
            return mYuvFrameData.submat(0, mHeight, 0, mWidth);
        }

        @Override
        public Mat rgba() {

          //  int ktb_moder = 1;


//            Log.i("KTB", "Mat rgba Entered");

            Imgproc.cvtColor(mYuvFrameData, mRgba, Imgproc.COLOR_YUV2RGBA_NV21, 4);


             //    Imgproc.cvtColor(mYuvFrameData, mRgba, Imgproc.COLOR_YUV2RGB_NV21, 4);

            //Log.i("KTB", "Number of CPUS" + Core.getNumberOfCPUs());
            List<Mat> splt = new ArrayList<Mat>(3);
            List<Mat> output = new ArrayList<Mat>(3);

            Core.split(mRgba,splt);

            Mat mR = splt.get(0);
            Mat mG = splt.get(1);
            Mat mB = splt.get(2);
            Mat mA = splt.get(3);


            /*
            Mat mR2 = mR.clone();
            Mat mG2 = mG.clone();
            Mat mG3 = mG.clone();

            Mat mB2 = mB.clone();

            Mat mRG = mR.clone();
            Mat mBG = mG.clone();
            Mat mBR = mB.clone();

            Mat mnR = mR.clone();
            Mat mnG = mG.clone();
            Mat mnB = mB.clone();

            Mat mpR = mR.clone();
            Mat mpG = mG.clone();
            Mat mpB = mB.clone();

            Mat mRGB = mB.clone();


            Mat moutB1 = mB.clone();
            Mat moutB2 = mB.clone();
            Mat moutB3 = mB.clone();
            Mat moutB4 = mB.clone();
*/
            // if (ktb_moder == 1) {
             //   Core.add(mR, mG, mR);
           // }

           // else if (ktb_moder == 2)
          //  {


            //FIXME UNCOMMENT THE NEXT LINE {KTB}
           // Core.multiply(mB, mG, mB);
  //          Core.add(mB, mG, mB);
            //Core.add(mB, mG, mB);

            //Core.add(mR, mG, mR);

            // }


/*
            Imgproc.threshold(mR2, mR2, 0, 255, mR2.type());
            Imgproc.threshold(mG2, mG2, 0, 255, mG2.type());
      //      Imgproc.threshold(mG3, mG3, 100, 255, mG3.type());

            Imgproc.threshold(mB2, mB2, 0, 255, mB2.type());




           Core.bitwise_not(mnR, mnR);
            Core.bitwise_not(mnG, mnG);
            Core.bitwise_not(mnB, mnB);


            Core.bitwise_and(mR2, mG2, mRG);
            Core.bitwise_and(mRG, mnB, mRG);

            Core.bitwise_and(mG2,mnB,mpG);
            Core.bitwise_and(mpG,mnR,mpG);

            Core.bitwise_and(mR2, mB2, mBR);
            Core.bitwise_and(mBR, mnG, mBR);

            Core.bitwise_and(mR2, mG2, mRGB);
            Core.bitwise_and(mRGB, mB2, mRGB);

            Core.bitwise_and(mB2, mnR, mpB);
            Core.bitwise_and(mpB, mnG, mpB);

            Core.bitwise_and(mG2, mnR, mBG);
            Core.bitwise_and(mBG, mB2, mBG);

            //  Log.i('KTB' , "Max Val: " + Core.ma)

          //  Core.add(mB, mT, mB);
           // Core.add(mB, mT, mB);
           // Core.add(mB, mT, mB);
           // Core.add(mB, mT, mB);

            //Core.bitwise_not(mG,mG);
            Mat mZ = Mat.zeros(mG.size(),mG.type());

            //splt.add(1,mG);


            Core.min(mpB, mB, moutB1);
            Core.min(mBG, mB, moutB2);
            Core.min(mRGB, mB, moutB3);
            Core.min(mpG, mB, moutB4);

            Core.bitwise_or(moutB1, moutB2, moutB1);
            Core.bitwise_or(moutB3, mpG, moutB3);
            Core.bitwise_or(moutB1, moutB3, moutB1);

        //    Core.min(mpG, mG, mpG);
         //   Core.bitwise_or(mB, mpG, mB);
            Imgproc.adaptiveThreshold();
*/


         //   Core.add(mG,mB,mB);
            Core.add(mB , mG, mB);
          //  Core.add(mB, mG, mB);

            Core.subtract(mB , mR, mB);
            Core.add(mB, mB, mB);

            Mat mZ = Mat.zeros(mG.size(),mG.type());

            output.add(mR);
            output.add(mG);
            output.add(mB);
         //   output.add(mA);



         //   Log.i("KTB" , "Type of SPLT INITL " + splt.size());

         //  Log.i("KTB", "Adding took place: " + splt.add(mG));
          //  Log.i("KTB" , "Type of SPLT Final " + splt.size());


            //Core.add(mG,mG,);




             Core.merge(output, mRgba);

           // Mat img = mRgba;

         //   mRgba = Imgproc.accumulate();
         //   Imgproc.accumulate();




//            Imgproc.cvtColor(mRgba, mRgba, Imgproc.COLOR_RGB2HSV, 4);

          //  StringBuilder sb = new StringBuilder();
          //  sb.append("");

          //  byte dat;
          //  int[] i1 = {};
         //   int j = mRgba.get(10, 10, i1);
         //   sb.append(j);


          //  String tester = sb.toString();
          //    Log.i("KTB", tester);

          //  Log.i("KTB" , mRgba.get(10,10).toString());
          //  final Mat kit = Mat.ones(mRgba.size(),mRgba.type());




           // Log.i("KTB" , "KIT" + kit.get(10,10));
           // Log.i("KTB" , "STR" + kit.get(10,10).toString());


            // Mat kit2 = Mat.diag(kit);
           // Mat identity_mat = Mat.ones(mRgba.size(),mRgba.type());

          //  mRgba = mRgba.mul(kit);




       //     Imgproc.cvtColor(mRgba, mRgba, Imgproc.COLOR_HSV2RGB, 4);

         //   mRgba.channels();
// FIXME KTB USE mrgba.cross() dot() mul()
            // Mat kit = mRgba;

       //     Log.i("KTB","mRGBA Pixel Value Initial" + mRgba.get(100,100).toString());

     //       Mat kit2 = Mat.ones(mRgba.size(),mRgba.type());

            //      kit2 = kit2.mul(kit2);

      //      mRgba = mRgba.mul(kit2);

            //byte[] dit;
          //  Log.i("KTB", "mRgba" + mRgba.get(100,100).toString());
           // kit.convertTo(mRgba, 2);


           // Log.i("KTB", "KIT" + kit.toString());
            //  Log.i("KTB","kit2 Pixel Value Final" + kit2.get(100,100,new byte[] dit));

            //   Log.i("KTB",mRgba.get(1,1).toString());
//            String kitty = "This is Null";

  //                  if (mRgba.get(1000,100) > kit) {
  //                      kitty = mRgba.get(1000, 100).toString();
//            String kitty_size = mRgba.size().toString();
                        //  String kitty_size =
   //                 }
//                        String kitty_size = Integer.toString(mHeight);

  //        Log.i("KTB", "Kitty Size = " +  kitty_size);
    //        Log.i("KTB", "Kitty = " +  kitty);

            //byte data = 0xffffffff;
            //double[] test = mRgba.get(1,1);
            //test = 12;
//
//
//            for (int i = 0;i<mWidth;i++)
//            {
////             //   Log.i("KTB","Width" + i + "Height" + "j" + "Pixel Value" + mRgba.get(1,i).toString());
//                for (int j = 0;j<mHeight;j++)
//                {
//                    Log.i("KTB","Width" + i + "Height" + j + "Pixel Value" + mRgba.get(i,j).toString());
////                    mRgba.put(j,i,test);
////
//                }
//            }






            return mRgba;
          //  return img;
        }































        public JavaCameraFrame(Mat Yuv420sp, int width, int height) {
            super();
            Log.i("KTB", "JavaCameraFrame Entered");
            mWidth = width;
            mHeight = height;
            mYuvFrameData = Yuv420sp;
            mRgba = new Mat();
        }

        public void release() {
            mRgba.release();
        }

        private Mat mYuvFrameData;
        private Mat mRgba;
        private int mWidth;
        private int mHeight;
    };

    private class CameraWorker implements Runnable {

        @Override
        public void run() {
            do {
                synchronized (JavaCameraView.this) {
                    try {
                        while (!mCameraFrameReady && !mStopThread) {
                            JavaCameraView.this.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (mCameraFrameReady)
                        mChainIdx = 1 - mChainIdx;
                }

                if (!mStopThread && mCameraFrameReady) {
                    mCameraFrameReady = false;
                    if (!mFrameChain[1 - mChainIdx].empty())
                        deliverAndDrawFrame(mCameraFrame[1 - mChainIdx]);
                }
            } while (!mStopThread);
            Log.d(TAG, "Finish processing thread");
        }
    }
}
