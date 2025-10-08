package com.example.concure;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanRecord;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BleManager handles all Bluetooth Low Energy operations including:
 * - Scanning for devices
 * - Connecting to ESP32 devices
 * - Sending and receiving data
 * - Managing connection state
 */
public class BleManager {
    private static final String TAG = "BleManager";
    
    // ESP32 BLE Service and Characteristic UUIDs
    private static final String SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b";
    private static final String CHARACTERISTIC_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8";
    
    // BLE scanning timeout (10 seconds)
    private static final long SCAN_PERIOD = 10000;
    
    // Singleton instance
    private static volatile BleManager instance;
    
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic characteristic;
    
    private Handler handler;
    private boolean isScanning = false;
    private boolean isConnected = false;
    
    // Callback interfaces for UI updates
    public interface BleScanCallback {
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onScanStarted();
        void onScanStopped();
        void onScanError(String error);
    }
    
    public interface BleConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(String error);
        void onDataReceived(String data);
        void onDataSent(boolean success);
    }
    
    // Enhanced callback interfaces for Dashboard integration
    public interface OnDeviceConnectedListener {
        void onDeviceConnected(String deviceName, String deviceAddress);
    }
    
    public interface OnDeviceDisconnectedListener {
        void onDeviceDisconnected();
    }
    
    public interface OnDataReceivedListener {
        void onTemperatureReceived(float temperature);
        void onHumidityReceived(float humidity);
        void onRawDataReceived(String data);
    }
    
    public interface OnConnectionStatusListener {
        void onConnectionStatusChanged(boolean isConnected, String status);
    }
    
    private BleScanCallback scanCallback;
    private BleConnectionCallback connectionCallback;
    
    // Enhanced callback listeners for Dashboard
    private OnDeviceConnectedListener deviceConnectedListener;
    private OnDeviceDisconnectedListener deviceDisconnectedListener;
    private OnDataReceivedListener dataReceivedListener;
    private OnConnectionStatusListener connectionStatusListener;
    
    // Current connected device info
    private String connectedDeviceName = "";
    private String connectedDeviceAddress = "";
    
    // Map to store discovered devices with their RSSI values
    private Map<String, BluetoothDevice> discoveredDevices = new HashMap<>();
    
    private BleManager(Context context) {
        this.context = context.getApplicationContext(); // Use application context to avoid memory leaks
        this.handler = new Handler(Looper.getMainLooper());
        
        // Initialize Bluetooth adapter
        BluetoothManager bluetoothManager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }
    
    /**
     * Get singleton instance of BleManager
     */
    public static BleManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BleManager.class) {
                if (instance == null) {
                    instance = new BleManager(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Set callback for scan events
     */
    public void setScanCallback(BleScanCallback callback) {
        this.scanCallback = callback;
    }
    
    /**
     * Set callback for connection events
     */
    public void setConnectionCallback(BleConnectionCallback callback) {
        this.connectionCallback = callback;
    }
    
    /**
     * Set callback for device connected events
     */
    public void setOnDeviceConnectedListener(OnDeviceConnectedListener listener) {
        this.deviceConnectedListener = listener;
    }
    
    /**
     * Set callback for device disconnected events
     */
    public void setOnDeviceDisconnectedListener(OnDeviceDisconnectedListener listener) {
        this.deviceDisconnectedListener = listener;
    }
    
    /**
     * Set callback for data received events
     */
    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        this.dataReceivedListener = listener;
    }
    
    /**
     * Set callback for connection status changes
     */
    public void setOnConnectionStatusListener(OnConnectionStatusListener listener) {
        this.connectionStatusListener = listener;
    }
    
    /**
     * Check if Bluetooth is available and enabled
     */
    public boolean isBluetoothAvailable() {
        boolean adapterExists = bluetoothAdapter != null;
        boolean adapterEnabled = adapterExists && bluetoothAdapter.isEnabled();
        boolean leScannerExists = bluetoothLeScanner != null;
        
        Log.d(TAG, "Bluetooth check - Adapter exists: " + adapterExists + 
                   ", Enabled: " + adapterEnabled + 
                   ", LE Scanner exists: " + leScannerExists);
        
        return adapterEnabled;
    }
    
    /**
     * Check if BLE is supported on this device
     */
    public boolean isBleSupported() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null");
            return false;
        }
        
        boolean leSupported = bluetoothLeScanner != null;
        Log.d(TAG, "BLE supported: " + leSupported);
        return leSupported;
    }
    
    /**
     * Enable Bluetooth if it's not already enabled
     */
    @SuppressLint("MissingPermission")
    public void enableBle() {
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Enabling Bluetooth...");
            try {
                bluetoothAdapter.enable();
                // Wait a bit for Bluetooth to enable
                handler.postDelayed(() -> {
                    Log.d(TAG, "Bluetooth enable attempt completed");
                }, 2000);
            } catch (Exception e) {
                Log.e(TAG, "Failed to enable Bluetooth: " + e.getMessage());
            }
        }
    }
    
    /**
     * Start scanning for BLE devices with filters
     */
    @SuppressLint("MissingPermission")
    public void startScan() {
        startScanInternal(true);
    }
    
    /**
     * Start scanning for BLE devices without filters (fallback method)
     */
    @SuppressLint("MissingPermission")
    public void startScanWithoutFilters() {
        startScanInternal(false);
    }
    
    /**
     * Internal method to start scanning
     */
    @SuppressLint("MissingPermission")
    private void startScanInternal(boolean useFilters) {
        Log.d(TAG, "=== STARTING BLE SCAN ===");
        
        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth is not available or enabled");
            if (scanCallback != null) {
                scanCallback.onScanError("Bluetooth is not available or enabled");
            }
            return;
        }
        
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BLE Scanner is null");
            if (scanCallback != null) {
                scanCallback.onScanError("BLE Scanner is not available");
            }
            return;
        }
        
        // Check permissions
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Required permissions not granted");
            if (scanCallback != null) {
                scanCallback.onScanError("Required permissions not granted");
            }
            return;
        }
        
        if (isScanning) {
            Log.d(TAG, "Already scanning, stopping previous scan");
            stopScan();
        }
        
        discoveredDevices.clear();
        isScanning = true;
        
        if (scanCallback != null) {
            scanCallback.onScanStarted();
        }
        
        // Try multiple scan configurations for maximum compatibility
        tryMultipleScanConfigurations();
    }
    
    /**
     * Try multiple scan configurations to find one that works
     */
    @SuppressLint("MissingPermission")
    private void tryMultipleScanConfigurations() {
        Log.d(TAG, "Trying multiple scan configurations...");
        
        // Configuration 1: Basic scan (most compatible)
        try {
            Log.d(TAG, "Trying basic scan configuration...");
            ScanSettings basicSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            
            bluetoothLeScanner.startScan(null, basicSettings, leScanCallback);
            Log.d(TAG, "Basic scan started successfully");
            
            // Stop scanning after timeout
            handler.postDelayed(this::stopScan, SCAN_PERIOD);
            return;
        } catch (Exception e) {
            Log.e(TAG, "Basic scan failed: " + e.getMessage());
        }
        
        // Configuration 2: Balanced scan
        try {
            Log.d(TAG, "Trying balanced scan configuration...");
            ScanSettings balancedSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setReportDelay(0)
                    .build();
            
            bluetoothLeScanner.startScan(null, balancedSettings, leScanCallback);
            Log.d(TAG, "Balanced scan started successfully");
            
            // Stop scanning after timeout
            handler.postDelayed(this::stopScan, SCAN_PERIOD);
            return;
        } catch (Exception e) {
            Log.e(TAG, "Balanced scan failed: " + e.getMessage());
        }
        
        // Configuration 3: High power scan
        try {
            Log.d(TAG, "Trying high power scan configuration...");
            ScanSettings highPowerSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build();
            
            bluetoothLeScanner.startScan(null, highPowerSettings, leScanCallback);
            Log.d(TAG, "High power scan started successfully");
            
            // Stop scanning after timeout
            handler.postDelayed(this::stopScan, SCAN_PERIOD);
            return;
        } catch (Exception e) {
            Log.e(TAG, "High power scan failed: " + e.getMessage());
        }
        
        // If all configurations fail
        Log.e(TAG, "All scan configurations failed");
        isScanning = false;
        if (scanCallback != null) {
            scanCallback.onScanError("All scan configurations failed - check device compatibility");
        }
    }
    
    /**
     * Stop scanning for BLE devices
     */
    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (isScanning && bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(leScanCallback);
            isScanning = false;
            
            if (scanCallback != null) {
                scanCallback.onScanStopped();
            }
            
            Log.d(TAG, "BLE scan stopped");
        }
    }
    
    /**
     * Connect to a specific BLE device
     */
    @SuppressLint("MissingPermission")
    public void connectToDevice(BluetoothDevice device) {
        if (!isBluetoothAvailable()) {
            if (connectionCallback != null) {
                connectionCallback.onConnectionFailed("Bluetooth is not available");
            }
            return;
        }
        
        if (!hasRequiredPermissions()) {
            if (connectionCallback != null) {
                connectionCallback.onConnectionFailed("Required permissions not granted");
            }
            return;
        }
        
        // Disconnect from any existing connection
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        
        Log.d(TAG, "Connecting to device: " + device.getAddress());
        
        // Connect to the device
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }
    
    /**
     * Disconnect from the current device
     */
    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        isConnected = false;
        characteristic = null;
        
        // Clear connected device info
        connectedDeviceName = "";
        connectedDeviceAddress = "";
        
        // Notify disconnection listeners immediately
        if (connectionStatusListener != null) {
            connectionStatusListener.onConnectionStatusChanged(false, "Disconnected");
        }
        
        if (deviceDisconnectedListener != null) {
            deviceDisconnectedListener.onDeviceDisconnected();
        }
        
        if (connectionCallback != null) {
            connectionCallback.onDisconnected();
        }
    }
    
    /**
     * Send data to the connected ESP32
     */
    @SuppressLint("MissingPermission")
    public void sendData(String data) {
        if (!isConnected || characteristic == null) {
            if (connectionCallback != null) {
                connectionCallback.onDataSent(false);
            }
            return;
        }
        
        if (!hasRequiredPermissions()) {
            if (connectionCallback != null) {
                connectionCallback.onDataSent(false);
            }
            return;
        }
        
        // Convert string to bytes
        byte[] dataBytes = data.getBytes();
        
        // Split data into chunks if it's too long (BLE characteristic limit is usually 20 bytes)
        int chunkSize = 20;
        for (int i = 0; i < dataBytes.length; i += chunkSize) {
            int end = Math.min(i + chunkSize, dataBytes.length);
            byte[] chunk = new byte[end - i];
            System.arraycopy(dataBytes, i, chunk, 0, chunk.length);
            
            characteristic.setValue(chunk);
            boolean success = bluetoothGatt.writeCharacteristic(characteristic);
            
            if (!success) {
                Log.e(TAG, "Failed to write characteristic");
                if (connectionCallback != null) {
                    connectionCallback.onDataSent(false);
                }
                return;
            }
        }
        
        Log.d(TAG, "Data sent: " + data);
        if (connectionCallback != null) {
            connectionCallback.onDataSent(true);
        }
    }
    
    /**
     * Get list of discovered devices
     */
    public List<BluetoothDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices.values());
    }
    
    /**
     * Check if currently connected
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * Check if currently scanning
     */
    public boolean isScanning() {
        return isScanning;
    }
    
    /**
     * Get connected device name
     */
    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }
    
    /**
     * Get connected device address
     */
    public String getConnectedDeviceAddress() {
        return connectedDeviceAddress;
    }
    
    /**
     * Check if required permissions are granted
     */
    private boolean hasRequiredPermissions() {
        boolean bluetoothConnect = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        boolean bluetoothScan = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        boolean location = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        
        Log.d(TAG, "Permission check - BLUETOOTH_CONNECT: " + bluetoothConnect + 
                   ", BLUETOOTH_SCAN: " + bluetoothScan + 
                   ", ACCESS_FINE_LOCATION: " + location);
        
        return bluetoothConnect && bluetoothScan && location;
    }
    
    /**
     * BLE scan callback
     */
    private ScanCallback leScanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            try {
                BluetoothDevice device = result.getDevice();
                String deviceName = null;
                int rssi = result.getRssi();
                String deviceAddress = device.getAddress();
                
                // Safely get device name
                try {
                    deviceName = device.getName();
                } catch (SecurityException e) {
                    Log.w(TAG, "Security exception getting device name: " + e.getMessage());
                }
                
                // Log ALL discovered devices for comprehensive debugging
                Log.d(TAG, "=== BLE DEVICE FOUND ===");
                Log.d(TAG, "Name: " + (deviceName != null ? deviceName : "NULL"));
                Log.d(TAG, "Address: " + deviceAddress);
                Log.d(TAG, "RSSI: " + rssi);
                Log.d(TAG, "Callback Type: " + callbackType);
                
                // Log scan record details
                if (result.getScanRecord() != null) {
                    Log.d(TAG, "Scan Record exists");
                    List<android.os.ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
                    if (serviceUuids != null && !serviceUuids.isEmpty()) {
                        Log.d(TAG, "Service UUIDs found: " + serviceUuids.size());
                        for (android.os.ParcelUuid uuid : serviceUuids) {
                            Log.d(TAG, "  Service UUID: " + uuid.toString());
                        }
                    } else {
                        Log.d(TAG, "No service UUIDs in scan record");
                    }
                } else {
                    Log.d(TAG, "No scan record");
                }
                Log.d(TAG, "========================");
                
                // Check if device name contains "ESP32" (case insensitive)
                boolean isEsp32 = false;
                if (deviceName != null && !deviceName.isEmpty()) {
                    isEsp32 = deviceName.toLowerCase().contains("esp32");
                    Log.d(TAG, "Name check - contains 'esp32': " + isEsp32);
                } else {
                    Log.d(TAG, "Device name is NULL or empty");
                }
                
                // Also check if the device has our service UUID in the scan record
                if (!isEsp32 && result.getScanRecord() != null) {
                    List<android.os.ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
                    if (serviceUuids != null) {
                        for (android.os.ParcelUuid uuid : serviceUuids) {
                            String uuidStr = uuid.toString().toLowerCase().replace("-", "");
                            String targetUuid = SERVICE_UUID.toLowerCase().replace("-", "");
                            Log.d(TAG, "Comparing UUIDs: " + uuidStr + " vs " + targetUuid);
                            if (uuidStr.contains(targetUuid)) {
                                isEsp32 = true;
                                Log.d(TAG, "Found ESP32 by service UUID: " + deviceName);
                                break;
                            }
                        }
                    }
                }
                
                // Show ALL devices in the UI (like nRF Connect)
                if (scanCallback != null) {
                    scanCallback.onDeviceFound(device, rssi);
                }
                
                // Add ALL devices to discovered devices list
                if (!discoveredDevices.containsKey(deviceAddress)) {
                    discoveredDevices.put(deviceAddress, device);
                    if (isEsp32) {
                        Log.d(TAG, "Added ESP32 to discovered devices: " + deviceName);
                    } else {
                        Log.d(TAG, "Added device to discovered devices: " + deviceName);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing scan result: " + e.getMessage(), e);
            }
        }
        
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE scan failed with error code: " + errorCode);
            isScanning = false;
            
            if (scanCallback != null) {
                scanCallback.onScanError("Scan failed with error code: " + errorCode);
            }
        }
    };
    
    /**
     * GATT callback for connection and data events
     */
    private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server");
                isConnected = true;
                
                // Store connected device info
                BluetoothDevice device = gatt.getDevice();
                connectedDeviceName = device.getName() != null ? device.getName() : "ESP32";
                connectedDeviceAddress = device.getAddress();
                
                // Notify connection status listeners
                if (connectionStatusListener != null) {
                    connectionStatusListener.onConnectionStatusChanged(true, "Connected to " + connectedDeviceName);
                }
                
                if (deviceConnectedListener != null) {
                    deviceConnectedListener.onDeviceConnected(connectedDeviceName, connectedDeviceAddress);
                }
                
                // Discover services
                gatt.discoverServices();
                
                if (connectionCallback != null) {
                    connectionCallback.onConnected();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server");
                isConnected = false;
                characteristic = null;
                
                // Clear connected device info
                connectedDeviceName = "";
                connectedDeviceAddress = "";
                
                // Notify disconnection listeners
                if (connectionStatusListener != null) {
                    connectionStatusListener.onConnectionStatusChanged(false, "Disconnected");
                }
                
                if (deviceDisconnectedListener != null) {
                    deviceDisconnectedListener.onDeviceDisconnected();
                }
                
                if (connectionCallback != null) {
                    connectionCallback.onDisconnected();
                }
            }
        }
        
        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered");
                
                // Find the ESP32 service and characteristic
                BluetoothGattService service = gatt.getService(UUID.fromString(SERVICE_UUID));
                if (service != null) {
                    characteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID));
                    if (characteristic != null) {
                        // Enable notifications
                        gatt.setCharacteristicNotification(characteristic, true);
                        
                        // Set up the descriptor for notifications
                        if (characteristic.getDescriptors().size() > 0) {
                            characteristic.getDescriptors().get(0).setValue(new byte[]{0x01, 0x00});
                            gatt.writeDescriptor(characteristic.getDescriptors().get(0));
                        }
                        
                        Log.d(TAG, "Characteristic found and notifications enabled");
                    } else {
                        Log.e(TAG, "Characteristic not found");
                        if (connectionCallback != null) {
                            connectionCallback.onConnectionFailed("Characteristic not found");
                        }
                    }
                } else {
                    Log.e(TAG, "Service not found");
                    if (connectionCallback != null) {
                        connectionCallback.onConnectionFailed("Service not found");
                    }
                }
            } else {
                Log.e(TAG, "Service discovery failed");
                if (connectionCallback != null) {
                    connectionCallback.onConnectionFailed("Service discovery failed");
                }
            }
        }
        
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Data received from ESP32
            byte[] data = characteristic.getValue();
            String receivedData = new String(data);
            
            Log.d(TAG, "Data received: " + receivedData);
            
            // Parse ESP32 data and notify listeners
            parseAndNotifyData(receivedData);
            
            if (connectionCallback != null) {
                connectionCallback.onDataReceived(receivedData);
            }
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Data written successfully");
            } else {
                Log.e(TAG, "Failed to write data");
            }
        }
    };
    
    /**
     * Parse ESP32 data and notify listeners
     */
    private void parseAndNotifyData(String data) {
        if (dataReceivedListener == null) return;
        
        try {
            // Parse temperature data (format: "Temp: 25.5°C")
            if (data.contains("Temp:")) {
                String tempStr = data.substring(data.indexOf("Temp:") + 5);
                tempStr = tempStr.replace("°C", "").trim();
                float temperature = Float.parseFloat(tempStr);
                dataReceivedListener.onTemperatureReceived(temperature);
                Log.d(TAG, "Parsed temperature: " + temperature + "°C");
            }
            
            // Parse humidity data (format: "Humidity: 65.2%")
            if (data.contains("Humidity:")) {
                String humidityStr = data.substring(data.indexOf("Humidity:") + 9);
                humidityStr = humidityStr.replace("%", "").trim();
                float humidity = Float.parseFloat(humidityStr);
                dataReceivedListener.onHumidityReceived(humidity);
                Log.d(TAG, "Parsed humidity: " + humidity + "%");
            }
            
            // Always notify raw data
            dataReceivedListener.onRawDataReceived(data);
            
        } catch (Exception e) {
            Log.e(TAG, "Error parsing data: " + data, e);
            // Still notify raw data even if parsing fails
            dataReceivedListener.onRawDataReceived(data);
        }
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        stopScan();
        disconnect();
    }
}
