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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import com.serenegiant.common.BaseService;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;

import java.nio.ByteBuffer;
import java.util.List;

public class UVCService extends BaseService {
	private static final boolean DEBUG = true;
	private static final String TAG = "UVCService";

	private static final int NOTIFICATION = R.string.app_name;

	private USBMonitor mUSBMonitor;
	private NotificationManager mNotificationManager;

	private int mVlCamera;
	private int mIrCamera;

	final RemoteCallbackList<CameraCallback> mCallbacks = new RemoteCallbackList<>();

	private final byte[] data0 = new byte[460800];
	private int date0_index ;
	private final byte[] data1 = new byte[460800];
	private int date1_index ;

	private IFrameCallback mIFrameCallback_Obj = new IFrameCallback() {

		@Override
		public void onFrame(ByteBuffer frame) {
			//Log.d(TAG,"ByteBuffer onFrame " + frame.remaining());
			//byte[] data = new byte[frame.remaining()];
			if (frame.remaining()>0)
			{

				if (date0_index %2 == 0){//%2 ==0){
					Log.d(TAG,"onFrame: remaining=" + frame.remaining() );
					frame.get(data0, 0, frame.remaining());
					//callback(data0, 0);
					Log.d(TAG,"mVlCamera = " + mVlCamera);
					callback(data0,mVlCamera);

				}
				date0_index++;
			}
		}

		@Override
		public void onConnected(int camera) {
			callback(0);

		}
	};


	private IFrameCallback mIFrameCallback_Obj_R = new IFrameCallback() {
		@Override
		public void onFrame(ByteBuffer frame) {
			Log.d(TAG,"ByteBuffer onFrame");
			if (frame.remaining() > 0)
			{
				if (date1_index %2 == 0){//2 ==0){
					Log.d(TAG,"111remaining is : " + frame.remaining() );
					frame.get(data1, 0, frame.remaining());
					//callback(data1, 1);
					Log.d(TAG,"mIrCamera = " + mIrCamera);
					callback(data1,mIrCamera);
				}
				date1_index++;

			}
		}

		@Override
		public void onConnected(int camera) {
			callback(1);
		}
	};


	void callback(int camera) {
		final int N = mCallbacks.beginBroadcast();
		Log.d(TAG,"onConnected callback N is : " + N + "camera = " + camera);
		for (int i=0; i<N; i++) {
			Log.d(TAG,"inner for");
			try {
				mCallbacks.getBroadcastItem(i).onConnected(camera);

			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
		mCallbacks.finishBroadcast();
	}

	void callback(byte[] data,int camera) {
    	Log.d(TAG, "data length="+data.length);

		final int N = mCallbacks.beginBroadcast();
		Log.d(TAG,"n is : " + N);
		for (int i=0; i<N; i++) {
			Log.d(TAG,"inner for");
			try {
				if(data!=null&&data.length>0){
					Log.d(TAG," getBroadcastItem1");
					mCallbacks.getBroadcastItem(i).onFrame(data,camera);
					Log.d(TAG," getBroadcastItem2");


				}
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
		mCallbacks.finishBroadcast();
	}


	public UVCService() {
		if (DEBUG) Log.d(TAG, "Constructor:");
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (DEBUG) Log.d(TAG, "onCreate:");
		if (mUSBMonitor == null) {
			mUSBMonitor = new USBMonitor(getApplicationContext(), mOnDeviceConnectListener);
			mUSBMonitor.register();
		}
		mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		showNotification(getString(R.string.app_name));
	}

	@Override
	public void onDestroy() {
		if (DEBUG) Log.d(TAG, "onDestroy:");
		if (mUSBMonitor != null) {
			mUSBMonitor.unregister();
			mUSBMonitor = null;
		}
		stopForeground(true/*removeNotification*/);
		if (mNotificationManager != null) {
			mNotificationManager.cancel(NOTIFICATION);
			mNotificationManager = null;
		}
		super.onDestroy();
	}

	@Override
	public IBinder onBind(final Intent intent) {
		if (DEBUG) Log.d(TAG, "onBind:" + intent);
		final String action = intent != null ? intent.getAction() : null;
		if (CameraInterface.class.getName().equals(action)) {
			Log.i(TAG, "return mBasicBinder");
			return mBasicBinder;
		}
		return null;
	}

	@Override
	public void onRebind(final Intent intent) {
		if (DEBUG) Log.d(TAG, "onRebind:" + intent);
	}

	@Override
	public boolean onUnbind(final Intent intent) {
		if (DEBUG) Log.d(TAG, "onUnbind:" + intent);
		if (checkReleaseService()) {
			stopSelf();
		}
		if (DEBUG) Log.d(TAG, "onUnbind:finished");
		return true;
	}

//********************************************************************************
	/**
	 * helper method to show/change message on notification area
	 * and set this service as foreground service to keep alive as possible as this can.
	 * @param text
	 */
	private void showNotification(final CharSequence text) {
		if (DEBUG) Log.v(TAG, "showNotification:" + text);
        // Set the info for the views that show in the notification panel.
        final Notification notification = new Notification.Builder(this)
			.setSmallIcon(R.drawable.ic_launcher)  // the status icon
			.setTicker(text)  // the status text
			.setWhen(System.currentTimeMillis())  // the time stamp
			.setContentTitle(getText(R.string.app_name))  // the label of the entry
			.setContentText(text)  // the contents of the entry
			.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0))  // The intent to send when the entry is clicked
			.build();

		startForeground(NOTIFICATION, notification);
        // Send the notification.
		mNotificationManager.notify(NOTIFICATION, notification);
    }

	private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
		@Override
		public void onAttach(final UsbDevice device) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onAttach:");
		}

		@Override
		public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onConnect:");

			queueEvent(new Runnable() {
				@Override
				public void run() {
					final int key = device.hashCode();
					CameraServer service;
					synchronized (sServiceSync) {
						service = sCameraServers.get(key);
						Log.d(TAG, "OnDeviceConnectListener#onConnect: key = " +key + "service is " + service);
						if (service == null) {
							service = CameraServer.createServer(UVCService.this, ctrlBlock, device.getVendorId(), device.getProductId());
							sCameraServers.append(key, service);
							Log.d(TAG,"append ,size is" + sCameraServers.size());

						} else {
							Log.w(TAG, "service already exist before connection");
						}
						sServiceSync.notifyAll();
					}
				}
			}, 0);
		}

		@Override
		public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onDisconnect:");
			queueEvent(new Runnable() {
				@Override
				public void run() {
					removeService(device);
				}
			}, 0);
		}

		@Override
		public void onDettach(final UsbDevice device) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onDettach:");
		}

		@Override
		public void onCancel(final UsbDevice device) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onCancel:");
			synchronized (sServiceSync) {
				sServiceSync.notifyAll();
			}
		}
	};

	private void removeService(final UsbDevice device) {
		final int key = device.hashCode();
		synchronized (sServiceSync) {
			final CameraServer service = sCameraServers.get(key);
			if (service != null)
				service.release();
			sCameraServers.remove(key);
			sServiceSync.notifyAll();
		}
		if (checkReleaseService()) {
			stopSelf();
		}
	}
//********************************************************************************
	private static final Object sServiceSync = new Object();
	private static final SparseArray<CameraServer> sCameraServers = new SparseArray<CameraServer>();

	/**
	 * get CameraService that has specific ID<br>
	 * if zero is provided as ID, just return top of CameraServer instance(non-blocking method) if exists or null.<br>
	 * if non-zero ID is provided, return specific CameraService if exist. block if not exists.<br>
	 * return null if not exist matched specific ID<br>
	 * @param serviceId
	 * @return
	 */
	private static CameraServer getCameraServer(final int serviceId) {
		synchronized (sServiceSync) {
			CameraServer server = null;
			if ((serviceId == 0) && (sCameraServers.size() > 0)) {
			    Log.d(TAG,"valueAt");
				server = sCameraServers.valueAt(0);
			} else {
                Log.d(TAG,"get serviceId");
                server = sCameraServers.get(serviceId);
				if (server == null)
					try {
						Log.i(TAG, "waiting for service is ready");
						sServiceSync.wait();
					} catch (final InterruptedException e) {
					}
					server = sCameraServers.get(serviceId);
			}
			return server;
		}
	}

	/**
	 * @return true if there are no camera connection
	 */
	private static boolean checkReleaseService() {
		CameraServer server = null;
		synchronized (sServiceSync) {
			final int n = sCameraServers.size();
			if (DEBUG) Log.d(TAG, "checkReleaseService:number of service=" + n);
			for (int i = 0; i < n; i++) {
				server = sCameraServers.valueAt(i);
				Log.i(TAG, "checkReleaseService:server=" + server + ",isConnected=" + (server != null && server.isConnected()));
				if (server != null && !server.isConnected()) {
					sCameraServers.removeAt(i);
					server.release();
				}
			}
			return sCameraServers.size() == 0;
		}
	}

//********************************************************************************

//	final RemoteCallbackList<IUVCServiceCallback> mCallbacks = new RemoteCallbackList <>();

	private final CameraInterface.Stub mBasicBinder = new CameraInterface.Stub() {
		private CameraCallback mCallback;


		@Override
		public int openCamera(int pid_0,int pid_1) throws RemoteException {
			if (DEBUG) Log.d(TAG, "openCamera");
			final List<UsbDevice> list = mUSBMonitor.getDeviceList();
			//依次打开2个摄像头
			UsbDevice device0 = list.get(0);
			Log.d(TAG,"pid_0 = " + pid_0 + "device0.getProductId()  = " + device0.getProductId() );
			if (device0.getProductId() ==pid_0 ){
				mVlCamera = 0;
				mIrCamera = 1;
			}else {
				mVlCamera = 1;
				mIrCamera = 0;
			}
			Log.d(TAG,"mVlCamera = " + mVlCamera + " ,mIrCamera =  " + mIrCamera);

			final int serviceId0 = device0.hashCode();
			final CameraServer server0 = getCameraServer(serviceId0);
			if (server0 == null) {
				throw new IllegalArgumentException("invalid serviceId");
			}
			server0.connect();
			Log.d(TAG,"serviceId0 ,  is : " + serviceId0 + " , " );

			UsbDevice device1 = list.get(1);
			final int serviceId1 = device1.hashCode();
			Log.d(TAG,"before getCameraServer serviceId1");
			final CameraServer server1 = getCameraServer(serviceId1);
			Log.d(TAG,"after getCameraServer serviceId1");

			if (server1 == null) {
				Log.d(TAG,"error getCameraServer serviceId1");
				throw new IllegalArgumentException("invalid serviceId");
			}
			server1.connect1();
			Log.d(TAG,"serviceId1 ,  is : " + serviceId1 + " , " );

            return 0;
		}

		@Override
		public int stop() throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#stop:");
			final List<UsbDevice> list = mUSBMonitor.getDeviceList();
			UsbDevice device = list.get(0);
			final int serviceId = device.hashCode();

			final CameraServer server = getCameraServer(serviceId);
			if (server == null) {
				throw new IllegalArgumentException("invalid serviceId");
			}
			server.disconnect();
            return 0;
		}

		@Override
		public void registerCallback(CameraCallback cb) throws RemoteException {
			Log.d(TAG,"registerCallback 111");
			mCallback = cb;
			final List<UsbDevice> list = mUSBMonitor.getDeviceList();

			//根据device利用requestPermission(device)打开不同的摄像头，requestPermission创建Handle（CameraService）
			UsbDevice device = list.get(0);
			final int serviceId = device.hashCode();
			CameraServer server = null;
			synchronized (sServiceSync) {
				server = sCameraServers.get(serviceId);
				if (server == null) {
					Log.i(TAG, "request permission1");
					mUSBMonitor.requestPermission(device);
					Log.i(TAG, "wait for getting permission1");
					try {
						sServiceSync.wait();
					} catch (final Exception e) {
						Log.e(TAG, "connect:", e);
					}
					Log.i(TAG, "check service again");
					server = sCameraServers.get(serviceId);
					if (server == null) {
						throw new RuntimeException("failed to open USB device(has no permission)");
					}
				}
			}
			if (server != null) {
				Log.i(TAG, "success to get service:serviceId=" + serviceId);
				server.registerCallback(cb);
				mCallbacks.register(cb);
				server.setThreadCallback(mIFrameCallback_Obj);

			}
			//return serviceId;
			Log.d(TAG,"before device1");
			UsbDevice device1 = list.get(1);
			final int serviceId1 = device1.hashCode();
			CameraServer server1 = null;
			Log.d(TAG,"before sServiceSync");

			synchronized (sServiceSync) {
				server1 = sCameraServers.get(serviceId1);

				if (server1 == null) {
					Log.i(TAG, "request permission2");
					mUSBMonitor.requestPermission(device1);
					Log.i(TAG, "wait for getting permission2");
					try {
						sServiceSync.wait();
					} catch (final Exception e) {
						Log.e(TAG, "connect:", e);
					}
					Log.i(TAG, "check service again");
					server1 = sCameraServers.get(serviceId1);
					if (server1 == null) {
						throw new RuntimeException("failed to open USB device(has no permission)");
					}
				}
			}
			if (server1 != null) {
				Log.i(TAG, "success to get service:serviceId=" + serviceId1);
				server1.registerCallback(cb);
				mCallbacks.register(cb);
				server1.setThreadCallback(mIFrameCallback_Obj_R);

			}


		}

		@Override
		public void unregisterCallback(CameraCallback cb) throws RemoteException {

		}

		@Override
		public void addSurface(int serviceId, int id_surface, Surface surface, boolean isRecordable) throws RemoteException {
		/*	if (DEBUG) Log.d(TAG, "mSlaveBinder#addSurface:id=" + id_surface + ",surface=" + surface);
			final CameraServer server = getCameraServer(serviceId);
			if (server != null) {
				server.addSurface(id_surface, surface, isRecordable);
			} else {
				Log.e(TAG, "failed to get CameraServer:serviceID=" + serviceId);
			}*/

            if (DEBUG) Log.d(TAG, "mBasicBinder#addSurface:id=" + id_surface + ",surface=" + surface);
            final CameraServer server = getCameraServer(serviceId);
            if (server != null)
                server.addSurface(id_surface, surface, isRecordable);

		}

		@Override
		public void addSurface1(int serviceId, int id_surface, Surface surface, boolean isRecordable) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#addSurface1:id=" + id_surface + ",surface=" + surface);
			final CameraServer server = getCameraServer(serviceId);
			if (server != null)
				server.addSurface1(id_surface, surface, isRecordable);

		}


	};


}
