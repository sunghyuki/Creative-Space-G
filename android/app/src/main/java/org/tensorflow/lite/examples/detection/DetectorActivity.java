/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import org.tensorflow.lite.examples.detection.customview.OverlayView;
import org.tensorflow.lite.examples.detection.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.tflite.Detector;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;
import org.tensorflow.lite.examples.detection.tracking.MultiBoxTracker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static android.speech.tts.TextToSpeech.ERROR;

/*
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
*/
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  private TextToSpeech tts;


  // Configuration values for the prepackaged SSD model.
  private static final int TF_OD_API_INPUT_SIZE = 300;
  private static final boolean TF_OD_API_IS_QUANTIZED = true;
  private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
  private static final String TF_OD_API_LABELS_FILE = "labelmap.txt";
  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
  private static final boolean MAINTAIN_ASPECT = false;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;
  private Integer sensorOrientation;

  private Detector detector;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private BorderedText borderedText;
  //TextView xyposition = (TextView) findViewById(R.id.xyPosition);
  public static Toast mToast;
  String beverageName;

  private Button button;

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {

    button = (Button) findViewById(R.id.backButton);
    button.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        finish();
      }
    });

    Intent intent = getIntent();
    beverageName = intent.getStringExtra("voice");


    tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
      @Override
      public void onInit(int status) {
        if (status != ERROR) {
          tts.setLanguage(Locale.KOREA);
        }
      }
    });

    final float textSizePx =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    try {
      detector =
              TFLiteObjectDetectionAPIModel.create(
                      this,
                      TF_OD_API_MODEL_FILE,
                      TF_OD_API_LABELS_FILE,
                      TF_OD_API_INPUT_SIZE,
                      TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e(e, "Exception initializing Detector!");
      Toast toast =
              Toast.makeText(
                      getApplicationContext(), "Detector could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
            ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    cropSize, cropSize,
                    sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
            new DrawCallback() {
              @Override
              public void drawCallback(final Canvas canvas) {
                tracker.draw(canvas);
                if (isDebug()) {
                  tracker.drawDebug(canvas);
                }
              }
            });

    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
  }

  @Override
  protected void processImage() {

    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();


    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
            new Runnable() {
              @Override
              public void run() {

                try {
                  Thread.sleep(2000);
                } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
                }


                LOGGER.i("Running detection on image " + currTimestamp);
                final long startTime = SystemClock.uptimeMillis();
                final List<Detector.Recognition> results = detector.recognizeImage(croppedBitmap);
                lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                final Canvas canvas = new Canvas(cropCopyBitmap);
                final Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStyle(Style.STROKE);
                paint.setStrokeWidth(2.0f);

                float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                switch (MODE) {
                  case TF_OD_API:
                    minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                    break;
                }

                final List<Detector.Recognition> mappedRecognitions =
                        new ArrayList<Detector.Recognition>();

                int phone_width = 360;
                int phone_height = 740;


                for (final Detector.Recognition result : results) {
                  final RectF location = result.getLocation();
                  if (location != null && result.getConfidence() >= minimumConfidence) {
                    canvas.drawRect(location, paint);

                    Display display = getWindowManager().getDefaultDisplay();
                    Point size = new Point();
                    display.getSize(size);
                    Log.d("DetectorActivity", size.x + "xy" + size.y);

                    determine_direction(300, 300, result, beverageName); //beverageName를 넣어줘서 비교

                    cropToFrameTransform.mapRect(location);

                    result.setLocation(location);
                    mappedRecognitions.add(result);
                  }
                }

                tracker.trackResults(mappedRecognitions, currTimestamp);
                trackingOverlay.postInvalidate();

                computingDetection = false;

                runOnUiThread(
                        new Runnable() {
                          @Override
                          public void run() {
                            showFrameInfo(previewWidth + "x" + previewHeight);
                            showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                            showInference(lastProcessingTimeMs + "ms");
                          }
                        });
              }
            });
  }

  @Override
  protected int getLayoutId() {
    return R.layout.tfe_od_camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  private enum DetectorMode {
    TF_OD_API;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
    runInBackground(
            () -> {
              try {
                detector.setUseNNAPI(isChecked);
              } catch (UnsupportedOperationException e) {
                LOGGER.e(e, "Failed to set \"Use NNAPI\".");
                runOnUiThread(
                        () -> {
                          Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        });
              }
            });
  }

  @Override
  protected void setNumThreads(final int numThreads) {
    runInBackground(
            () -> {
              try {
                detector.setNumThreads(numThreads);
              } catch (IllegalArgumentException e) {
                LOGGER.e(e, "Failed to set multithreads.");
                runOnUiThread(
                        () -> {
                          Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        });
              }
            });
  }

  public void determine_direction(int phone_width, int phone_height, Detector.Recognition result, String text) {


    int x = phone_width / 3;
    int y = phone_height / 3;

    if (text.equals(new String("컵"))) {
      text = "cup";
    }

    RectF location = result.getLocation();

    float midx = (location.left + location.right) / 2;
    float midy = (location.top + location.bottom) / 2;

    //Toast mpoint = Toast.makeText(this.getApplicationContext(), "x: "+ midx + "y: "+midy , 1000);
    //mpoint.show();
    String res = (String) result.getTitle().toString().trim();

    if(text.equals(new String("이거뭐야")) || text.equals(new String("이거 뭐야"))) {
      ShortMessage(res);
    }

    if (res.equals(text)) {


      //Toast.makeText(this, res, Toast.LENGTH_LONG).show();
      if (midy < y && midx > x && midx < 2 * x) {

        ShortMessage("위에 있음");
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
        Log.d("DetectorActivity", "위에 있음 x 좌표: " + midx + "y 좌표:" + midy);
      } else if (midy < y && midx < x) {
        ShortMessage("왼쪽 대각선 위에 있음");
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
        Log.d("DetectorActivity", "왼쪽 대각선 위에 있음 x 좌표: " + midx + "y 좌표:" + midy);
      } else if (midy < y && midx > 2 * x) {
        ShortMessage("오른쪽 대각선 위에 있음");
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
        Log.d("DetectorActivity", "오른쪽 대각선 위에 있음 x 좌표: " + midx + "y 좌표:" + midy);
      } else if (midy > 2 * y && midx < x) {
        ShortMessage("왼쪽 대각선 아래에 있음");
        Log.d("DetectorActivity", "왼쪽 대각선 아래에 있음 x 좌표: " + midx + "y 좌표:" + midy);
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
      } else if (midy > 2 * y && midx >= x && midx <= 2 * x) {
        ShortMessage("아래에 있음");
        Log.d("DetectorActivity", "아래에 있음 x 좌표: " + midx + "y 좌표:" + midy);
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
      } else if (midy > 2 * y && midx > 2 * x) {
        ShortMessage("오른쪽 대각선 아래에 있음");
        Log.d("DetectorActivity", "오른쪽 대각선 아래에 있음 x 좌표: " + midx + "y 좌표:" + midy);
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
      } else if (midx > 2 * x) {
        ShortMessage("오른쪽에 있음");
        Log.d("DetectorActivity", "오른쪽에 있음 x 좌표: " + midx + "y 좌표:" + midy);
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
      } else if (midx < x) {
        ShortMessage("왼쪽에 있음");
        Log.d("DetectorActivity", "왼쪽에 있음 x 좌표: " + midx + "y 좌표:" + midy);
        // Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
      } else {
        ShortMessage("가운데에 있음");
        Log.d("DetectorActivity", "가운데에 있음 x 좌표: " + midx + "y 좌표:" + midy);
        //Toast.makeText(this, result.getTitle().toString().trim(), Toast.LENGTH_LONG).show();
      }

    }


  }

//      if(String.valueOf(midx) != null) {
//        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
//        startActivity(intent);
//      }

  //logd()    result.getLocation();
  //  Log.d("DetectorActivity", result.getId()+"ㅎㅇㅎㅇ"+ result.getLocation()+ result.getTitle() +result.getConfidence());


  public void ShortMessage(final String message) {

    //xyposition.setText(message);
    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null);
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    Toast.makeText(this.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    if (mToast != null) mToast.cancel();
    mToast = Toast.makeText(this.getApplicationContext(), message, Toast.LENGTH_SHORT);
    mToast.show();

  }

}


