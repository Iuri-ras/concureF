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
    
    private BleScanCallback scanCallback;
    private BleConnectionCallback connectionCallback;
    
    // Map to store discovered devices with their RSSI values
    private Map<String, BluetoothDevice> discoveredDevices = new HashMap<>();
    
    public BleManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        
        // Initialize Bluetooth adapter
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
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
     * Check if Bluetooth is available and enabled
     */
    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
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
        if (!isBluetoothAvailable()) {
            if (scanCallback != null) {
                scanCallback.onScanError("Bluetooth is not available or enabled");
            }
            return;
        }
        
        if (bluetoothLeScanner == null) {
            if (scanCallback != null) {
                scanCallback.onScanError("BLE Scanner is not available");
            }
            return;
        }
        
        // Check permissions
        if (!hasRequiredPermissions()) {
            if (scanCallback != null) {
                scanCallback.onScanError("Required permissions not granted");
            }
            return;
        }
        
        if (isScanning) {
            stopScan();
        }
        
        discoveredDevices.clear();
        isScanning = true;
        
        if (scanCallback != null) {
            scanCallback.onScanStarted();
        }
        
        // Create scan settings for better performance
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build();
        
        // Always scan without filters for maximum compatibility (like nRF Connect)
        Log.d(TAG, "Starting scan without filters (nRF Connect style)");
        bluetoothLeScanner.startScan(null, settings, leScanCallback);
        
        // Also try the most basic scan possible as backup
        handler.postDelayed(() -> {
            if (isScanning) {
                Log.d(TAG, "Starting backup scan with minimal settings...");
                ScanSettings basicSettings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                        .build();
                try {
                    bluetoothLeScanner.startScan(null, basicSettings, leScanCallback);
                } catch (Exception e) {
                    Log.e(TAG, "Backup scan failed: " + e.getMessage());
                }
            }
        }, 2000); // Start backup scan after 2 seconds
        
        // Stop scanning after timeout
        handler.postDelayed(this::stopScan, SCAN_PERIOD);
        
        Log.d(TAG, "BLE scan started");
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
     * Check if required permissions are granted
     */
    private boolean hasRequiredPermissions() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * BLE scan callback
     */
    private ScanCallback leScanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceName = device.getName();
            int rssi = result.getRssi();
            String deviceAddress = device.getAddress();
            
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
            if (deviceName != null) {
                isEsp32 = deviceName.toLowerCase().contains("esp32");
                Log.d(TAG, "Name check - contains 'esp32': " + isEsp32);
            } else {
                Log.d(TAG, "Device name is NULL");
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
                
                // Discover services
                gatt.discoverServices();
                
                if (connectionCallback != null) {
                    connectionCallback.onConnected();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server");
                isConnected = false;
                characteristic = null;
                
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
     * Clean up resources
     */
    public void cleanup() {
        stopScan();
        disconnect();
    }
}
