package com.example.concure;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MainActivity handles the UI and coordinates with BleManager for BLE operations
 * Features:
 * - Permission handling for Android 12+
 * - Device scanning and selection
 * - Connection management
 * - Message sending and receiving
 * - Real-time log display
 */
public class MainActivity extends AppCompatActivity implements 
        BleManager.BleScanCallback, 
        BleManager.BleConnectionCallback,
        DeviceAdapter.OnDeviceClickListener {

    private static final String TAG = "MainActivity";
    
    // Permission request launcher
    private ActivityResultLauncher<String[]> permissionLauncher;
    
    // UI Components
    private MaterialButton scanButton;
    private MaterialButton connectButton;
    private MaterialButton sendButton;
    private MaterialButton clearLogButton;
    private RecyclerView deviceList;
    private TextView connectionStatus;
    private TextView messageLog;
    private EditText messageInput;
    private ScrollView logScrollView;
    
    // BLE Manager and Device Adapter
    private BleManager bleManager;
    private DeviceAdapter deviceAdapter;
    
    // Selected device
    private BluetoothDevice selectedDevice;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        // Setup window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        
        // Initialize components
        initializeViews();
        initializeBleManager();
        setupPermissionLauncher();
        setupRecyclerView();
        setupClickListeners();
        
        // Check permissions and Bluetooth availability
        checkPermissionsAndBluetooth();
    }
    
    /**
     * Initialize all UI views
     */
    private void initializeViews() {
        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        sendButton = findViewById(R.id.sendButton);
        clearLogButton = findViewById(R.id.clearLogButton);
        deviceList = findViewById(R.id.deviceList);
        connectionStatus = findViewById(R.id.connectionStatus);
        messageLog = findViewById(R.id.messageLog);
        messageInput = findViewById(R.id.messageInput);
        logScrollView = findViewById(R.id.logScrollView);
        
        // Setup message log scrolling
        messageLog.setMovementMethod(new ScrollingMovementMethod());
        
        // Initial state
        connectButton.setEnabled(false);
        sendButton.setEnabled(false);
    }
    
    /**
     * Initialize BLE Manager and set callbacks
     */
    private void initializeBleManager() {
        bleManager = BleManager.getInstance(this);
        bleManager.setScanCallback(this);
        bleManager.setConnectionCallback(this);
    }
    
    /**
     * Setup permission launcher for runtime permissions
     */
    private void setupPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }
                    
                    if (allGranted) {
                        logMessage("All permissions granted");
                        checkBluetoothAvailability();
                    } else {
                        logMessage("Some permissions were denied");
                        Toast.makeText(this, "BLE permissions are required for this app to work", Toast.LENGTH_LONG).show();
                    }
                }
        );
    }
    
    /**
     * Setup RecyclerView for device list
     */
    private void setupRecyclerView() {
        deviceAdapter = new DeviceAdapter();
        deviceAdapter.setOnDeviceClickListener(this);
        deviceList.setLayoutManager(new LinearLayoutManager(this));
        deviceList.setAdapter(deviceAdapter);
    }
    
    /**
     * Setup click listeners for all buttons
     */
    private void setupClickListeners() {
        scanButton.setOnClickListener(v -> {
            if (bleManager.isScanning()) {
                bleManager.stopScan();
                scanButton.setText("Scan for ESP32 Devices");
            } else {
                startScanning();
            }
        });
        
        // Add long click listener for fallback scanning
        scanButton.setOnLongClickListener(v -> {
            if (!bleManager.isScanning()) {
                startScanningWithoutFilters();
                return true;
            }
            return false;
        });
        
        connectButton.setOnClickListener(v -> {
            if (selectedDevice != null) {
                if (bleManager.isConnected()) {
                    bleManager.disconnect();
                } else {
                    bleManager.connectToDevice(selectedDevice);
                }
            }
        });
        
        sendButton.setOnClickListener(v -> {
            String message = messageInput.getText().toString().trim();
            if (!message.isEmpty()) {
                bleManager.sendData(message);
                logMessage("Sent: " + message);
                messageInput.setText("");
            }
        });
        
        clearLogButton.setOnClickListener(v -> {
            messageLog.setText("Log cleared\n");
            scrollToBottom();
        });
        
        // Add dashboard button
        MaterialButton dashboardButton = findViewById(R.id.dashboardButton);
        if (dashboardButton != null) {
            dashboardButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, DashboardActivity.class);
                startActivity(intent);
            });
        }
    }
    
    /**
     * Check permissions and Bluetooth availability
     */
    private void checkPermissionsAndBluetooth() {
        if (!hasRequiredPermissions()) {
            requestPermissions();
        } else {
            checkBluetoothAvailability();
        }
    }
    
    /**
     * Check if all required permissions are granted
     */
    private boolean hasRequiredPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * Request required permissions
     */
    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        permissionLauncher.launch(permissions);
    }
    
    /**
     * Check Bluetooth availability and enable if needed
     */
    private void checkBluetoothAvailability() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter == null) {
                logMessage("Bluetooth is not supported on this device");
                Toast.makeText(this, "Bluetooth is not supported", Toast.LENGTH_LONG).show();
            } else if (!bluetoothAdapter.isEnabled()) {
                logMessage("Bluetooth is disabled. Please enable Bluetooth.");
                Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show();
            } else {
                logMessage("Bluetooth is available and enabled");
            }
        }
    }
    
    /**
     * Start BLE scanning
     */
    private void startScanning() {
        if (!bleManager.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_SHORT).show();
            return;
        }
        
        deviceAdapter.clearDevices();
        selectedDevice = null;
        connectButton.setEnabled(false);
        
        scanButton.setText("Stop Scanning");
        logMessage("Starting BLE scan...");
        logMessage("Looking for devices with name containing 'ESP32' or service UUID: 4fafc201-1fb5-459e-8fcc-c5c9c331914b");
        bleManager.startScan();
    }
    
    /**
     * Start BLE scanning without filters (fallback method)
     */
    private void startScanningWithoutFilters() {
        if (!bleManager.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_SHORT).show();
            return;
        }
        
        deviceAdapter.clearDevices();
        selectedDevice = null;
        connectButton.setEnabled(false);
        
        scanButton.setText("Stop Scanning");
        logMessage("Starting BLE scan WITHOUT filters (fallback mode)...");
        logMessage("This will show ALL BLE devices - look for ESP32-ConCure");
        Toast.makeText(this, "Scanning without filters - check log for all devices", Toast.LENGTH_LONG).show();
        bleManager.startScanWithoutFilters();
    }
    
    /**
     * Add message to log with timestamp
     */
    private void logMessage(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = "[" + timestamp + "] " + message + "\n";
        
        runOnUiThread(() -> {
            messageLog.append(logEntry);
            scrollToBottom();
        });
    }
    
    /**
     * Scroll log to bottom
     */
    private void scrollToBottom() {
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }
    
    // BleManager.BleScanCallback implementation
    @Override
    public void onDeviceFound(BluetoothDevice device, int rssi) {
        runOnUiThread(() -> {
            deviceAdapter.addDevice(device, rssi);
            String deviceName = device.getName();
            if (deviceName == null) deviceName = "Unknown";
            
            // Log all devices for debugging
            if (deviceName.toLowerCase().contains("esp32")) {
                logMessage("🎯 Found ESP32: " + deviceName + " (RSSI: " + rssi + ")");
            } else {
                logMessage("Found device: " + deviceName + " (RSSI: " + rssi + ")");
            }
        });
    }
    
    @Override
    public void onScanStarted() {
        runOnUiThread(() -> {
            logMessage("BLE scan started");
        });
    }
    
    @Override
    public void onScanStopped() {
        runOnUiThread(() -> {
            scanButton.setText("Scan for ESP32 Devices");
            logMessage("BLE scan stopped");
        });
    }
    
    @Override
    public void onScanError(String error) {
        runOnUiThread(() -> {
            logMessage("Scan error: " + error);
            scanButton.setText("Scan for ESP32 Devices");
            Toast.makeText(this, "Scan error: " + error, Toast.LENGTH_SHORT).show();
        });
    }
    
    // BleManager.BleConnectionCallback implementation
    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            connectionStatus.setText("Status: Connected");
            connectButton.setText("Disconnect");
            sendButton.setEnabled(true);
            logMessage("Connected to ESP32");
            Toast.makeText(this, "Connected to ESP32", Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            connectionStatus.setText("Status: Disconnected");
            connectButton.setText("Connect to Selected Device");
            connectButton.setEnabled(selectedDevice != null);
            sendButton.setEnabled(false);
            logMessage("Disconnected from ESP32");
            Toast.makeText(this, "Disconnected from ESP32", Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onConnectionFailed(String error) {
        runOnUiThread(() -> {
            connectionStatus.setText("Status: Connection Failed");
            connectButton.setText("Connect to Selected Device");
            connectButton.setEnabled(selectedDevice != null);
            sendButton.setEnabled(false);
            logMessage("Connection failed: " + error);
            Toast.makeText(this, "Connection failed: " + error, Toast.LENGTH_LONG).show();
        });
    }
    
    @Override
    public void onDataReceived(String data) {
        runOnUiThread(() -> {
            logMessage("Received: " + data);
        });
    }
    
    @Override
    public void onDataSent(boolean success) {
        runOnUiThread(() -> {
            if (success) {
                logMessage("Message sent successfully");
            } else {
                logMessage("Failed to send message");
                Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    // DeviceAdapter.OnDeviceClickListener implementation
    @Override
    public void onDeviceClick(BluetoothDevice device, int position) {
        selectedDevice = device;
        connectButton.setEnabled(true);
        logMessage("Selected device: " + device.getName());
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.cleanup();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Stop scanning when app goes to background
        if (bleManager != null && bleManager.isScanning()) {
            bleManager.stopScan();
        }
    }
}