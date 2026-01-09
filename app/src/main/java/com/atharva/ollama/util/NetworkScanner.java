package com.atharva.ollama.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility class for network operations.
 */
public class NetworkScanner {

    public interface ScanCallback {
        void onServerFound(String ip, int port);
        void onScanProgress(int current, int total);
        void onScanComplete(boolean found);
    }

    private static final int DEFAULT_PORT = 11434;
    private static final int CONNECTION_TIMEOUT = 500;

    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean isScanning;

    public NetworkScanner(Context context) {
        this.context = context;
        this.executor = Executors.newFixedThreadPool(20);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isScanning = new AtomicBoolean(false);
    }

    /**
     * Test connection to a specific server.
     */
    public void testConnection(String ip, int port, ConnectionCallback callback) {
        executor.execute(() -> {
            boolean success = tryConnect(ip, port);
            mainHandler.post(() -> callback.onResult(success));
        });
    }

    /**
     * Scan local network for Ollama server.
     */
    public void scanNetwork(ScanCallback callback) {
        if (isScanning.get()) return;
        isScanning.set(true);

        executor.execute(() -> {
            String subnet = getSubnet();
            if (subnet == null) {
                mainHandler.post(() -> {
                    isScanning.set(false);
                    callback.onScanComplete(false);
                });
                return;
            }

            AtomicBoolean found = new AtomicBoolean(false);
            int total = 254;

            for (int i = 1; i <= 254 && !found.get(); i++) {
                final int current = i;
                String testIp = subnet + i;

                if (tryConnect(testIp, DEFAULT_PORT)) {
                    found.set(true);
                    String foundIp = testIp;
                    mainHandler.post(() -> callback.onServerFound(foundIp, DEFAULT_PORT));
                }

                mainHandler.post(() -> callback.onScanProgress(current, total));
            }

            isScanning.set(false);
            boolean wasFound = found.get();
            mainHandler.post(() -> callback.onScanComplete(wasFound));
        });
    }

    private boolean tryConnect(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), CONNECTION_TIMEOUT);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String getSubnet() {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                int ip = wifiManager.getConnectionInfo().getIpAddress();
                String ipAddress = Formatter.formatIpAddress(ip);
                int lastDot = ipAddress.lastIndexOf('.');
                if (lastDot > 0) {
                    return ipAddress.substring(0, lastDot + 1);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "192.168.1.";
    }

    public void stopScanning() {
        isScanning.set(false);
    }

    public interface ConnectionCallback {
        void onResult(boolean success);
    }
}
