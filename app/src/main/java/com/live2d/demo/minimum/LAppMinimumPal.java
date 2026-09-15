/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */
package com.live2d.demo.minimum;

import android.util.Log;
import com.live2d.demo.LAppDefine;
import com.live2d.sdk.cubism.core.ICubismLogger;
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction;

import java.io.IOException;
import java.io.InputStream;

public class LAppMinimumPal {
    /**
     * Logging Function class to be registered in the CubismFramework's logging function.
     */
    public static class PrintLogFunction implements ICubismLogger {
        @Override
        public void print(String message) {
            Log.d(TAG, message);
        }
    }

    /**
     * File loading function class to be registered in the CubismFramework's file loading function.
     */
    public static class LoadFileFunction implements ICubismLoadFileFunction {
        @Override
        public byte[] load(String path) {
            return LAppMinimumPal.loadFileAsBytes(path);
        }
    }

    // アプリケーションを中断状態にする。実行されるとonPause()イベントが発生する
    public static void moveTaskToBack() {
        // Dropped: the sample backgrounded its Activity. The companion lives in a
        // foreground service, which has no task to move back.
    }

    // デルタタイムの更新
    public static void updateTime() {
        s_currentFrame = getSystemNanoTime();
        deltaNanoTime = s_currentFrame - lastNanoTime;
        lastNanoTime = s_currentFrame;
    }

    // Reads a file from assets as bytes.
    //
    // Rewritten from the sample: it used available() to size the buffer, which is
    // not reliable for compressed APK assets, and its finally block closed the
    // stream unconditionally — so a missing file produced a NullPointerException
    // instead of an error message. Returning null lets the framework report the
    // failure instead.
    public static byte[] loadFileAsBytes(final String path) {
        InputStream fileData = null;
        try {
            fileData = LAppMinimumDelegate.getInstance().getContext().getAssets().open(path);

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = fileData.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            if (LAppDefine.DEBUG_LOG_ENABLE) {
                printLog("File open error: " + path);
            }
            return null;
        } finally {
            if (fileData != null) {
                try {
                    fileData.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // デルタタイム(前回フレームとの差分)を取得する
    public static float getDeltaTime() {
        // ナノ秒を秒に変換
        return (float) (deltaNanoTime / 1000000000.0f);
    }

    /**
     * Logging function
     *
     * @param message log message
     */
    public static void printLog(String message) {
        Log.d(TAG, message);
    }

    private static long getSystemNanoTime() {
        return System.nanoTime();
    }

    private LAppMinimumPal() {}

    private static double s_currentFrame;
    private static double lastNanoTime;
    private static double deltaNanoTime;

    private static final String TAG = "[APP]";
}
