/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.hisign.cameraserver;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.hisign.encoder.MediaAudioEncoder;
import com.hisign.encoder.MediaEncoder;
import com.hisign.encoder.MediaMuxerWrapper;
import com.hisign.encoder.MediaSurfaceEncoder;
import com.serenegiant.glutils.RenderHolderCallback;
import com.serenegiant.glutils.RendererHolder;
import com.hisign.cameraserver.CameraCallback;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

public final class CameraServer extends Handler {
	private static final boolean DEBUG = true;
	private static final String TAG = "CameraServer";

	private static final int DEFAULT_WIDTH = 480;
	private static final int DEFAULT_HEIGHT = 640;
	
	private int mFrameWidth = DEFAULT_WIDTH, mFrameHeight = DEFAULT_HEIGHT;

    private static class CallbackCookie {
		boolean isConnected;
	}

    private final RemoteCallbackList<CameraCallback> mCallbacks
		= new RemoteCallbackList<CameraCallback>();
    private int mRegisteredCallbackCount;

	private RendererHolder mRendererHolder;
	private RendererHolder mRendererHolder1;

	private final WeakReference<CameraThread> mWeakThread;

	public static CameraServer createServer(final Context context, final UsbControlBlock ctrlBlock, final int vid, final int pid) {
		if (DEBUG) Log.d(TAG, "createServer:");
		final CameraThread thread = new CameraThread(context, ctrlBlock);
		thread.start();
		return thread.getHandler();
	}

	private CameraServer(final CameraThread thread) {
		if (DEBUG) Log.d(TAG, "Constructor:");
		mWeakThread = new WeakReference<CameraThread>(thread);
		mRegisteredCallbackCount = 0;
		mRendererHolder = new RendererHolder(mFrameWidth, mFrameHeight, mRenderHolderCallback);
		mRendererHolder1 = new RendererHolder(mFrameWidth, mFrameHeight, mRenderHolderCallback1);

	}

	@Override
	protected void finalize() throws Throwable {
		if (DEBUG) Log.i(TAG, "finalize:");
		release();
		super.finalize();
	}

	public void registerCallback(final CameraCallback callback) {
		if (DEBUG) Log.d(TAG, "registerCallback:");
		mCallbacks.register(callback, new CallbackCookie());
		mRegisteredCallbackCount++;
	}

	public boolean unregisterCallback(final CameraCallback callback) {
		if (DEBUG) Log.d(TAG, "unregisterCallback:");
		mCallbacks.unregister(callback);
		mRegisteredCallbackCount--;
		if (mRegisteredCallbackCount < 0) mRegisteredCallbackCount = 0;
		return mRegisteredCallbackCount == 0;
	}

	public void release() {
		if (DEBUG) Log.d(TAG, "release:");
		disconnect();
		mCallbacks.kill();
		if (mRendererHolder != null) {
			mRendererHolder.release();
			mRendererHolder = null;
		}
	}

//********************************************************************************
//********************************************************************************
	public void resize(final int width, final int height) {
		if (DEBUG) Log.d(TAG, String.format("resize(%d,%d)", width, height));
		if (!isRecording()) {
			mFrameWidth = width;
			mFrameHeight = height;
			if (mRendererHolder != null) {
				mRendererHolder.resize(width, height);
			}
		}
	}

	public void connect() {
		if (DEBUG) Log.d(TAG, "connect:");
		final CameraThread thread = mWeakThread.get();
		if (!thread.isCameraOpened()) {
			sendMessage(obtainMessage(MSG_OPEN));
			sendMessage(obtainMessage(MSG_PREVIEW_START, mFrameWidth, mFrameHeight, mRendererHolder.getSurface()));

		} else {
			if (DEBUG) Log.d(TAG, "already connected, just call callback");
			processOnCameraStart();
		}

	}

	public void connect1() {
		if (DEBUG) Log.d(TAG, "connect1:");
		final CameraThread thread = mWeakThread.get();
		if (!thread.isCameraOpened()) {
			sendMessage(obtainMessage(MSG_OPEN));
			sendMessage(obtainMessage(MSG_PREVIEW_START, mFrameWidth, mFrameHeight, mRendererHolder1.getSurface()));

		} else {
			if (DEBUG) Log.d(TAG, "already connected, just call callback");
			processOnCameraStart();
		}

	}

/*
	public void connectSlave() {
		if (DEBUG) Log.d(TAG, "connectSlave:");
		final CameraThread thread = mWeakThread.get();
		if (thread.isCameraOpened()) {
			processOnCameraStart();
		}
	}*/

	public void disconnect() {
		if (DEBUG) Log.d(TAG, "disconnect:");
		//stopRecording();
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		synchronized (thread.mSync) {
			sendEmptyMessage(MSG_PREVIEW_STOP);
			sendEmptyMessage(MSG_CLOSE);
			// wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
			// while preview is still running.
			// therefore this method will take a time to execute
			try {
				thread.mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
	}

	public boolean isConnected() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isCameraOpened();
	}

	public boolean isRecording() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isRecording();
	}

	public void addSurface(final int id, final Surface surface, final boolean isRecordable) {
		if (DEBUG) Log.d(TAG, "addSurface:id=" + id +",surface=" + surface);
		if (mRendererHolder != null)
			mRendererHolder.addSurface(id, surface, isRecordable);
	}

	public void addSurface1(final int id, final Surface surface, final boolean isRecordable) {
		if (DEBUG) Log.d(TAG, "addSurface1:id=" + id +",surface=" + surface);
		if (mRendererHolder1 != null)
			mRendererHolder1.addSurface(id, surface, isRecordable);
	}


	/*	public void addSurface(final int id) {
		if (DEBUG) Log.d(TAG, "addSurface:id=" + id );

	}

	public void removeSurface(final int id) {
		if (DEBUG) Log.d(TAG, "removeSurface:id=" + id);
		if (mRendererHolder != null)
			mRendererHolder.removeSurface(id);
	}*/
	private IFrameCallback mIFrameCallback_Obj;

	public void setThreadCallback(IFrameCallback iFrameCallback_Obj){
		mIFrameCallback_Obj= iFrameCallback_Obj;
	}




//********************************************************************************
	private void processOnCameraStart() {
		if (DEBUG) Log.d(TAG, "processOnCameraStart:");
		try {
			final int n = mCallbacks.beginBroadcast();
			Log.d(TAG,"N = " + n);
			for (int i = 0; i < n; i++) {
				if (!((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected)
				try {
					//mUVCCamera.setFrameCallback(mCallbacks, UVCCamera.PIXEL_FORMAT_YUV);
					//mCallbacks.getBroadcastItem(i).onConnected(i);

					((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = true;

					final CameraThread thread = mWeakThread.get();
					thread.setIFrameCallback(mIFrameCallback_Obj);

				} catch (final Exception e) {
					Log.e(TAG, "failed to call IOverlayCallback#onFrameAvailable");
				}
			}
			mCallbacks.finishBroadcast();
		} catch (final Exception e) {
			Log.w(TAG, e);
		}
	}

	private void processOnCameraStop() {
		if (DEBUG) Log.d(TAG, "processOnCameraStop:");
		final int n = mCallbacks.beginBroadcast();
		for (int i = 0; i < n; i++) {
			if (((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected)
			try {
				//mCallbacks.getBroadcastItem(i).onDisConnected();
				((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = false;
			} catch (final Exception e) {
				Log.e(TAG, "failed to call IOverlayCallback#onDisConnected");
			}
		}
		mCallbacks.finishBroadcast();
	}

//**********************************************************************
	private static final int MSG_OPEN = 0;
	private static final int MSG_CLOSE = 1;
	private static final int MSG_PREVIEW_START = 2;
	private static final int MSG_PREVIEW_STOP = 3;
	private static final int MSG_CAPTURE_STILL = 4;
	private static final int MSG_CAPTURE_START = 5;
	private static final int MSG_CAPTURE_STOP = 6;
	private static final int MSG_MEDIA_UPDATE = 7;
	private static final int MSG_RELEASE = 9;

	@Override
	public void handleMessage(final Message msg) {
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		switch (msg.what) {
		case MSG_OPEN:
			thread.handleOpen();
			break;
	/*	case MSG_OPEN_1:
			thread.handleOpen1();
			break;*/
		case MSG_CLOSE:
			thread.handleClose();
			break;
		case MSG_PREVIEW_START:
		    Log.d(TAG,"MSG_PREVIEW_START");
			thread.handleStartPreview(msg.arg1, msg.arg2, (Surface)msg.obj);
			break;
		case MSG_PREVIEW_STOP:
			thread.handleStopPreview();
			break;
		case MSG_MEDIA_UPDATE:
			thread.handleUpdateMedia((String)msg.obj);
			break;
		case MSG_RELEASE:
			thread.handleRelease();
			break;
		default:
			throw new RuntimeException("unsupported message:what=" + msg.what);
		}
	}

	private final RenderHolderCallback mRenderHolderCallback
			= new RenderHolderCallback() {
		@Override
		public void onCreate(final Surface surface) {
		}

		@Override
		public void onFrameAvailable() {
			final CameraThread thread = mWeakThread.get();
			if ((thread != null) && (thread.mVideoEncoder != null)) {
				try {
					thread.mVideoEncoder.frameAvailableSoon();
				} catch (final Exception e) {
					//
				}
			}
		}

		@Override
		public void onDestroy() {
		}
	};
	private final RenderHolderCallback mRenderHolderCallback1
			= new RenderHolderCallback() {
		@Override
		public void onCreate(final Surface surface) {
		}

		@Override
		public void onFrameAvailable() {
			final CameraThread thread = mWeakThread.get();
			if ((thread != null) && (thread.mVideoEncoder != null)) {
				try {
					thread.mVideoEncoder.frameAvailableSoon();
				} catch (final Exception e) {
					//
				}
			}
		}

		@Override
		public void onDestroy() {
		}
	};

	//  需要2个CameraThread

	private static final class CameraThread extends Thread {
		private static final String TAG_THREAD = "CameraThread";
		private final Object mSync = new Object();
		private boolean mIsRecording;
	    private final WeakReference<Context> mWeakContext;
		private int mEncoderSurfaceId;
		private int mFrameWidth, mFrameHeight;
		/**
		 * shutter sound
		 */
		private SoundPool mSoundPool;
		private int mSoundId;
		private CameraServer mHandler;
		private UsbControlBlock mCtrlBlock;
		/**
		 * for accessing UVC camera
		 */
		private volatile UVCCamera mUVCCamera;


		/**
		 * muxer for audio/video recording
		 */
		private MediaMuxerWrapper mMuxer;
		private MediaSurfaceEncoder mVideoEncoder;


		public void setIFrameCallback(IFrameCallback iFrameCallback_Obj){
			mUVCCamera.setFrameCallback(iFrameCallback_Obj,UVCCamera.FRAME_FORMAT_MJPEG);//PIXEL_FORMAT_NV21);
			Log.d(TAG,"Thread  setIFrameCallback");
		//	mUVCCamera.setFrameCallback(mCallbacks, UVCCamera.PIXEL_FORMAT_YUV);
		}

		private CameraThread(final Context context, final UsbControlBlock ctrlBlock) {
			super("CameraThread");
			if (DEBUG) Log.d(TAG_THREAD, "Constructor:");
			mWeakContext = new WeakReference<Context>(context);
			mCtrlBlock = ctrlBlock;
			loadShutterSound(context);
		}

		@Override
		protected void finalize() throws Throwable {
			Log.i(TAG_THREAD, "CameraThread#finalize");
			super.finalize();
		}

		public CameraServer getHandler() {
			if (DEBUG) Log.d(TAG_THREAD, "getHandler:");
			synchronized (mSync) {
				if (mHandler == null)
				try {
					mSync.wait();
				} catch (final InterruptedException e) {
				}
			}
			return mHandler;
		}

		public boolean isCameraOpened() {
			return mUVCCamera != null;
		}

		public boolean isRecording() {
			return (mUVCCamera != null) && (mMuxer != null);
		}

		public void handleOpen() {
			if (DEBUG) Log.d(TAG_THREAD, "handleOpen:");
			handleClose();
			synchronized (mSync) {
				mUVCCamera = new UVCCamera();
				mUVCCamera.open(mCtrlBlock);
				if (DEBUG) Log.i(TAG, "supportedSize:" + mUVCCamera.getSupportedSize());
			}
			mHandler.processOnCameraStart();
		}

		public void handleClose() {
			if (DEBUG) Log.d(TAG_THREAD, "handleClose:");
			//handleStopRecording();
			boolean closed = false;
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
					mUVCCamera.destroy();
					mUVCCamera = null;
					closed = true;
				}
				mSync.notifyAll();
			}
			if (closed)
				mHandler.processOnCameraStop();
			if (DEBUG) Log.d(TAG_THREAD, "handleClose:finished");
		}

		public void handleStartPreview(final int width, final int height, final Surface surface) {
			if (DEBUG) Log.d(TAG_THREAD, "handleStartPreview:");
			synchronized (mSync) {
				if (mUVCCamera == null) return;
				try {
					mUVCCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG);
				} catch (final IllegalArgumentException e) {
					try {
						// fallback to YUV mode
						mUVCCamera.setPreviewSize(width, height, UVCCamera.DEFAULT_PREVIEW_MODE);
					} catch (final IllegalArgumentException e1) {
						mUVCCamera.destroy();
						mUVCCamera = null;
					}
				}
				if (mUVCCamera == null) return;
				mFrameWidth = width;
				mFrameHeight = height;
				mUVCCamera.setPreviewDisplay(surface);
				mUVCCamera.startPreview();
			}
		}

		public void handleStopPreview() {
			if (DEBUG) Log.d(TAG_THREAD, "handleStopPreview:");
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
			}
		}

		private void handleResize(final int width, final int height, final Surface surface) {
			synchronized (mSync) {
				if (mUVCCamera != null) {
					final Size sz = mUVCCamera.getPreviewSize();
					if ((sz != null) && ((width != sz.width) || (height != sz.height))) {
						mUVCCamera.stopPreview();
						try {
							mUVCCamera.setPreviewSize(width, height);
						} catch (final IllegalArgumentException e) {
							try {
								mUVCCamera.setPreviewSize(sz.width, sz.height);
							} catch (final IllegalArgumentException e1) {
								// unexpectedly #setPreviewSize failed
								mUVCCamera.destroy();
								mUVCCamera = null;
							}
						}
						if (mUVCCamera == null) return;
						mFrameWidth = width;
						mFrameHeight = height;
						mUVCCamera.setPreviewDisplay(surface);
						mUVCCamera.startPreview();
					}
				}
			}
		}


		public void handleUpdateMedia(final String path) {
			if (DEBUG) Log.d(TAG_THREAD, "handleUpdateMedia:path=" + path);
			final Context context = mWeakContext.get();
			if (context != null) {
				try {
					if (DEBUG) Log.i(TAG, "MediaScannerConnection#scanFile");
					MediaScannerConnection.scanFile(context, new String[]{ path }, null, null);
				} catch (final Exception e) {
					Log.e(TAG, "handleUpdateMedia:", e);
				}
			} else {
				Log.w(TAG, "MainActivity already destroyed");
				// give up to add this movice to MediaStore now.
				// Seeing this movie on Gallery app etc. will take a lot of time.
				handleRelease();
			}
		}

		public void handleRelease() {
			if (DEBUG) Log.d(TAG_THREAD, "handleRelease:");
			handleClose();
			if (mCtrlBlock != null) {
				mCtrlBlock.close();
				mCtrlBlock = null;
			}
			if (!mIsRecording)
				Looper.myLooper().quit();
		}

		/**
		 * prepare and load shutter sound for still image capturing
		 */
		@SuppressWarnings("deprecation")
		private void loadShutterSound(final Context context) {
			if (DEBUG) Log.d(TAG_THREAD, "loadShutterSound:");
	    	// get system stream type using refrection
	        int streamType;
	        try {
	            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
	            final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
	            streamType = sseField.getInt(null);
	        } catch (final Exception e) {
	        	streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
	        }
	        if (mSoundPool != null) {
	        	try {
	        		mSoundPool.release();
	        	} catch (final Exception e) {
	        	}
	        	mSoundPool = null;
	        }
	        // load sutter sound from resource
		    mSoundPool = new SoundPool(2, streamType, 0);
		    mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
		}

		@Override
		public void run() {
			if (DEBUG) Log.d(TAG_THREAD, "run:");
			Looper.prepare();
			synchronized (mSync) {
				mHandler = new CameraServer(this);
				mSync.notifyAll();
			}
			Looper.loop();
			synchronized (mSync) {
				mHandler = null;
				mSoundPool.release();
				mSoundPool = null;
				mSync.notifyAll();
			}
			if (DEBUG) Log.d(TAG_THREAD, "run:finished");
		}
	}

}
