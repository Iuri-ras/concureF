package com.example.concure;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;
import android.animation.ValueAnimator;

import androidx.core.app.ActivityCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import com.example.concure.data.AppDatabase;
import com.example.concure.data.CuringSession;
import com.example.concure.data.CuringSessionDao;
import com.example.concure.data.SensorReading;
import com.example.concure.data.SensorReadingDao;
import com.example.concure.databinding.ActivityDashboardBinding;
import com.example.concure.prediction.MaturityCalculator;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardActivity extends AppCompatActivity implements 
        BleManager.OnDeviceConnectedListener,
        BleManager.OnDeviceDisconnectedListener,
        BleManager.OnDataReceivedListener,
        BleManager.OnConnectionStatusListener {
    
    private static final String TAG = "DashboardActivity";
    private static final String CHANNEL_ID = "curing_notifications";
    private static final int NOTIFICATION_ID = 1;
    
    // ViewBinding
    private ActivityDashboardBinding binding;
    
    // BLE Manager
    private BleManager bleManager;
    
    // Database
    private AppDatabase database;
    private SensorReadingDao sensorReadingDao;
    private CuringSessionDao curingSessionDao;
    
    // Threading
    private ExecutorService executor;
    private Handler mainHandler;
    private Handler chartUpdateHandler;
    private Runnable chartUpdateRunnable;
    
    // Data
    private List<SensorReading> chartData;
    private CuringSession activeSession;
    private SensorReading lastReading;
    private boolean hasReceivedData = false;
    private long lastDataReceivedTime = 0;
    private int batteryLevel = -1; // -1 means unknown/disconnected
    
    // Performance optimization for curing calculations
    private long lastCuringUpdateTime = 0;
    private static final long CURING_UPDATE_INTERVAL = 5000; // 5 seconds
    private float lastCalculatedMaturity = 0f;
    
    // Chart components
    private LineDataSet temperatureDataSet;
    private LineDataSet humidityDataSet;
    private LineData lineData;
    
    // Permission request launcher
    private ActivityResultLauncher<String[]> permissionLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            // Initialize ViewBinding
            binding = ActivityDashboardBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());
            
            // Enable edge-to-edge display
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                );
            }
            
            // Initialize components
            initializeComponents();
            setupUI();
            setupChart();
            setupClickListeners();
            createNotificationChannel();
            loadExistingData();
            
            // Check permissions after UI is set up (non-blocking)
            checkPermissionsAfterStartup();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            Toast.makeText(this, "Error initializing app: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Initialize all components
     */
    private void initializeComponents() {
        try {
            // BLE Manager
            bleManager = BleManager.getInstance(this);
            bleManager.setOnDeviceConnectedListener(this);
            bleManager.setOnDeviceDisconnectedListener(this);
            bleManager.setOnDataReceivedListener(this);
            bleManager.setOnConnectionStatusListener(this);
            
            // Database
            try {
            database = AppDatabase.getDatabase(this);
            sensorReadingDao = database.sensorReadingDao();
            curingSessionDao = database.curingSessionDao();
                Log.d(TAG, "Database initialized successfully");
            } catch (Exception e) {
                Log.e(TAG, "Database initialization failed: " + e.getMessage(), e);
                throw new RuntimeException("Failed to initialize database", e);
            }
            
            // Threading
            executor = Executors.newSingleThreadExecutor();
            mainHandler = new Handler(Looper.getMainLooper());
            chartUpdateHandler = new Handler(Looper.getMainLooper());
            
            // Data
            chartData = new ArrayList<>();
            
            // Setup permission launcher
            setupPermissionLauncher();
        } catch (Exception e) {
            Log.e(TAG, "Error in initializeComponents: " + e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Setup UI components and initial state
     */
    private void setupUI() {
        try {
            // Set initial connection status based on BLE manager state
            boolean isConnected = bleManager.isConnected();
            String deviceName = bleManager.getConnectedDeviceName();
            String status = isConnected ? "Connected to " + deviceName : "Disconnected";
            updateConnectionStatus(isConnected, status);
            
            // Initialize temperature, humidity, and battery displays
            updateTemperatureDisplay(null);
            updateHumidityDisplay(null);
            updateBatteryDisplay(batteryLevel); // Initialize with -1 (disconnected state)
            
            // Test battery parsing logic
            testBatteryParsing();
            
            // Initialize with empty data - everything starts from zero
            Log.d(TAG, "Initializing with empty data - waiting for ESP32 connection");
            
            // Initialize UI with empty state
            initializeEmptyState();
            
            // Setup bottom navigation
            if (binding.bottomNavigation != null) {
                binding.bottomNavigation.setOnItemSelectedListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.nav_dashboard) {
                        return true;
                    } else if (itemId == R.id.nav_ble_settings) {
                        Intent intent = new Intent(this, MainActivity.class);
                        startActivity(intent);
                        return true;
                    } else if (itemId == R.id.nav_history) {
                        Toast.makeText(this, "History feature coming soon", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in setupUI: " + e.getMessage(), e);
            Toast.makeText(this, "Error setting up UI: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Setup the real-time chart
     */
    private void setupChart() {
        try {
            if (binding.trendChart == null) {
                Log.e(TAG, "Chart view is null");
                return;
            }
            
            LineChart chart = binding.trendChart;
            
            // Initialize chart with default data to prevent crashes
            if (chart.getData() == null) {
                Log.d(TAG, "Initializing chart with empty data");
                LineData emptyData = new LineData();
                chart.setData(emptyData);
            }
            
            // Set default axis ranges to prevent negative array issues
            YAxis defaultAxis = chart.getAxisLeft();
            defaultAxis.setAxisMinimum(0f);
            defaultAxis.setAxisMaximum(100f);
            defaultAxis.setGranularity(10f);
            
            // Ensure chart has valid dimensions
            chart.setMinimumWidth(200);
            chart.setMinimumHeight(200);
            
            // Configure chart appearance
            chart.setBackgroundColor(Color.WHITE);
            chart.getDescription().setEnabled(false);
            chart.setTouchEnabled(true);
            chart.setDragEnabled(true);
            chart.setScaleEnabled(true);
            chart.setPinchZoom(true);
            chart.setDrawGridBackground(false);
            
            // Configure X-axis (time in seconds)
            XAxis xAxis = chart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setDrawGridLines(true);
            xAxis.setGridColor(Color.LTGRAY);
            xAxis.setValueFormatter(new TimeIntervalValueFormatter());
            xAxis.setGranularity(2f); // 2-second intervals
            xAxis.setLabelCount(6, true);
            // Set default range for time (0-30 seconds)
            xAxis.setAxisMinimum(0f);
            xAxis.setAxisMaximum(30f); // Default to show 30 seconds (15 data points)
            
            // Configure Y-axis (sensor values)
            YAxis leftAxis = chart.getAxisLeft();
            leftAxis.setDrawGridLines(true);
            leftAxis.setGridColor(Color.LTGRAY);
            leftAxis.setAxisMinimum(0f);
            leftAxis.setAxisMaximum(100f);
            leftAxis.setGranularity(10f);
            leftAxis.setLabelCount(6, true);
            leftAxis.setSpaceTop(10f);
            leftAxis.setSpaceBottom(10f);
            leftAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return String.format(Locale.getDefault(), "%.1f", value);
                }
            });
            
            YAxis rightAxis = chart.getAxisRight();
            rightAxis.setEnabled(false);
            
            // Create data sets with empty lists initially
            temperatureDataSet = new LineDataSet(new ArrayList<Entry>(), "Temperature (°C)");
            temperatureDataSet.setColor(Color.parseColor("#1976D2"));
            temperatureDataSet.setCircleColor(Color.parseColor("#1976D2"));
            temperatureDataSet.setLineWidth(4f); // Thicker lines for better visibility
            temperatureDataSet.setCircleRadius(5f); // Larger circles for better visibility
            temperatureDataSet.setDrawValues(false);
            temperatureDataSet.setDrawCircles(true); // Ensure circles are drawn
            temperatureDataSet.setDrawFilled(false); // Don't fill under line
            temperatureDataSet.setMode(LineDataSet.Mode.LINEAR); // Ensure linear mode
            temperatureDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            
            humidityDataSet = new LineDataSet(new ArrayList<Entry>(), "Humidity (%)");
            humidityDataSet.setColor(Color.parseColor("#00796B"));
            humidityDataSet.setCircleColor(Color.parseColor("#00796B"));
            humidityDataSet.setLineWidth(4f); // Thicker lines for better visibility
            humidityDataSet.setCircleRadius(5f); // Larger circles for better visibility
            humidityDataSet.setDrawValues(false);
            humidityDataSet.setDrawCircles(true); // Ensure circles are drawn
            humidityDataSet.setDrawFilled(false); // Don't fill under line
            humidityDataSet.setMode(LineDataSet.Mode.LINEAR); // Ensure linear mode
            humidityDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            
            // Create line data
            lineData = new LineData();
            lineData.addDataSet(temperatureDataSet);
            lineData.addDataSet(humidityDataSet);
            chart.setData(lineData);
            
            // Configure legend
            Legend legend = chart.getLegend();
            legend.setEnabled(true);
            legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
            legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
            legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
            legend.setDrawInside(false);
            legend.setTextSize(12f);
            
            chart.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error in setupChart: " + e.getMessage(), e);
            Toast.makeText(this, "Error setting up chart: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Setup click listeners for buttons
     */
    private void setupClickListeners() {
        try {
            // Connection card clickable
            if (binding.connectionStatusCard != null) {
                binding.connectionStatusCard.setOnClickListener(v -> {
                    if (bleManager.isConnected()) {
                        bleManager.disconnect();
                    } else {
                        Intent intent = new Intent(this, MainActivity.class);
                        startActivity(intent);
                    }
                });
            }
            
            // Connect button
            if (binding.connectButton != null) {
                binding.connectButton.setOnClickListener(v -> {
                    if (bleManager.isConnected()) {
                        bleManager.disconnect();
                    } else {
                        Intent intent = new Intent(this, MainActivity.class);
                        startActivity(intent);
                    }
                });
            }
            
            // Start curing button
            if (binding.startCuringButton != null) {
                binding.startCuringButton.setOnClickListener(v -> startCuringProcess());
            }
            
            // Stop curing button
            if (binding.stopCuringButton != null) {
                binding.stopCuringButton.setOnClickListener(v -> stopCuringProcess());
            }
            
            // Sync data button
            if (binding.syncDataButton != null) {
                binding.syncDataButton.setOnClickListener(v -> syncData());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in setupClickListeners: " + e.getMessage(), e);
            Toast.makeText(this, "Error setting up click listeners: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Load existing data from database
     */
    private void loadExistingData() {
        try {
            executor.execute(() -> {
                try {
                    // Load recent sensor readings
                    List<SensorReading> recentReadings = sensorReadingDao.getRecentReadings(100);
                    mainHandler.post(() -> {
                        // Clear existing chart data and start fresh
                        if (temperatureDataSet != null) {
                            temperatureDataSet.clear();
                        }
                        if (humidityDataSet != null) {
                            humidityDataSet.clear();
                        }
                        
                        // Set new data
                        chartData.clear();
                        chartData.addAll(recentReadings);
                        
                        // Update chart with existing data
                        updateChart();
                    });
                    
                    // Load active curing session
                    CuringSession active = curingSessionDao.getActiveSession();
                    mainHandler.post(() -> {
                        activeSession = active;
                        updateCuringUI();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error loading existing data: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in loadExistingData: " + e.getMessage(), e);
        }
    }
    
    /**
     * Update battery display with animation
     */
    private void updateBatteryDisplay(int batteryPercent) {
        try {
            Log.d(TAG, "updateBatteryDisplay called with: " + batteryPercent + "%");
            Log.d(TAG, "Battery binding elements - Value: " + (binding.batteryValue != null) + 
                  ", Status: " + (binding.batteryStatus != null) + 
                  ", Icon: " + (binding.batteryIcon != null));
            
            if (batteryPercent < 0) {
                // Disconnected state
                if (binding.batteryValue != null) {
                    binding.batteryValue.setText("N/A");
                }
                if (binding.batteryStatus != null) {
                    binding.batteryStatus.setText("No data");
                }
                if (binding.batteryIcon != null) {
                    binding.batteryIcon.setImageResource(R.drawable.ic_battery_unknown);
                    binding.batteryIcon.setColorFilter(getColor(R.color.battery_icon_color_gray));
                }
                // Set gray background for disconnected state
                if (binding.batteryCard != null) {
                    binding.batteryCard.setCardBackgroundColor(getColor(R.color.battery_card_background_gray));
                }
            } else {
                // Connected state with battery data
                if (binding.batteryValue != null) {
                    // Animate the battery percentage change
                    animateBatteryValue(batteryPercent);
                }
                
                // Update battery icon and color based on charge level
                int iconResource;
                int iconColor;
                int cardBackgroundColor;
                String statusText;
                
                if (batteryPercent >= 80) {
                    iconResource = R.drawable.ic_battery_full;
                    iconColor = R.color.battery_icon_color_green;
                    cardBackgroundColor = R.color.battery_card_background_green;
                    statusText = "Excellent";
                } else if (batteryPercent >= 40) {
                    iconResource = R.drawable.ic_battery_medium;
                    iconColor = R.color.battery_icon_color_yellow;
                    cardBackgroundColor = R.color.battery_card_background_yellow;
                    statusText = "Good";
                } else if (batteryPercent >= 10) {
                    iconResource = R.drawable.ic_battery_low;
                    iconColor = R.color.battery_icon_color_orange;
                    cardBackgroundColor = R.color.battery_card_background_orange;
                    statusText = "Low";
                } else {
                    iconResource = R.drawable.ic_battery_alert;
                    iconColor = R.color.battery_icon_color_red;
                    cardBackgroundColor = R.color.battery_card_background_red;
                    statusText = "Critical";
                }
                
                if (binding.batteryIcon != null) {
                    binding.batteryIcon.setImageResource(iconResource);
                    binding.batteryIcon.setColorFilter(getColor(iconColor));
                }
                
                if (binding.batteryStatus != null) {
                    binding.batteryStatus.setText(statusText);
                }
                
                // Update card background color
                if (binding.batteryCard != null) {
                    binding.batteryCard.setCardBackgroundColor(getColor(cardBackgroundColor));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateBatteryDisplay: " + e.getMessage(), e);
        }
    }
    
    /**
     * Test battery parsing with sample data
     */
    private void testBatteryParsing() {
        Log.d(TAG, "=== TESTING NEW ESP32 FORMAT ===");
        
        // Test the new ESP32 format
        String[] testMessages = {"T:25.1", "H:60.2", "B:75"};
        
        for (String testData : testMessages) {
            Log.d(TAG, "Testing message: " + testData);
            
            if (testData.startsWith("T:")) {
                String tempStr = testData.substring(2).trim();
                float temperature = Float.parseFloat(tempStr);
                Log.d(TAG, "Temperature test parsed: " + temperature + "°C");
                
            } else if (testData.startsWith("H:")) {
                String humStr = testData.substring(2).trim();
                float humidity = Float.parseFloat(humStr);
                Log.d(TAG, "Humidity test parsed: " + humidity + "%");
                
            } else if (testData.startsWith("B:")) {
                String batteryStr = testData.substring(2).trim();
                int battery = Integer.parseInt(batteryStr);
                Log.d(TAG, "Battery test parsed: " + battery + "%");
            }
        }
        
        Log.d(TAG, "=== END NEW FORMAT TEST ===");
    }
    
    /**
     * Add sample data for testing chart display
     */
    private void addSampleChartData() {
        try {
            Log.d(TAG, "=== ADDING SAMPLE CHART DATA ===");
            long currentTime = System.currentTimeMillis();
            
            // Clear existing data first
            chartData.clear();
            
            // Add some sample data points with more visible variations
            for (int i = 0; i < 15; i++) {
                SensorReading reading = new SensorReading();
                // Create more interesting temperature pattern (20-30°C range)
                reading.setTemperature(20.0f + (float)Math.sin(i * 0.5) * 5.0f + i * 0.3f);
                // Create humidity pattern (50-80% range)
                reading.setHumidity(65.0f + (float)Math.cos(i * 0.3) * 10.0f + i * 0.5f);
                reading.setTimestamp(currentTime - (14 - i) * 30000); // Every 30 seconds for 15 points
                
                chartData.add(reading);
                Log.d(TAG, "Added data point " + i + ": Temp=" + reading.getTemperature() + 
                      "°C, Hum=" + reading.getHumidity() + "%");
            }
            
            Log.d(TAG, "Added " + chartData.size() + " sample data points to chart");
            
            // Force chart update
            updateChart();
            
            // Also try to force chart refresh
            if (binding.trendChart != null) {
                binding.trendChart.post(() -> {
                    binding.trendChart.invalidate();
                    Log.d(TAG, "Chart invalidated in post");
                });
            }
            
            // Test with simple data to ensure chart works
            testSimpleChartData();
            
            // Test with simulated ESP32 data
            testEsp32DataFlow();
            
            // Create a sample curing session for testing
            createSampleCuringSession();
            
            Log.d(TAG, "=== END SAMPLE CHART DATA ===");
        } catch (Exception e) {
            Log.e(TAG, "Error adding sample chart data: " + e.getMessage(), e);
        }
    }
    
    /**
     * Test simple chart data to ensure chart is working
     */
    private void testSimpleChartData() {
        try {
            Log.d(TAG, "=== TESTING SIMPLE CHART DATA ===");
            
            if (binding.trendChart == null) {
                Log.e(TAG, "Chart is null in simple test");
                return;
            }
            
            // Create very simple test data
            List<Entry> simpleTempEntries = new ArrayList<>();
            List<Entry> simpleHumEntries = new ArrayList<>();
            
            for (int i = 0; i < 5; i++) {
                simpleTempEntries.add(new Entry(i, 20.0f + i * 2.0f)); // 20, 22, 24, 26, 28
                simpleHumEntries.add(new Entry(i, 60.0f + i * 5.0f));  // 60, 65, 70, 75, 80
            }
            
            Log.d(TAG, "Created simple test data - Temp entries: " + simpleTempEntries.size() + 
                  ", Hum entries: " + simpleHumEntries.size());
            
            // Update data sets directly
            if (temperatureDataSet != null) {
                temperatureDataSet.clear();
                for (Entry entry : simpleTempEntries) {
                    temperatureDataSet.addEntry(entry);
                }
                Log.d(TAG, "Updated temperature data set");
            }
            
            if (humidityDataSet != null) {
                humidityDataSet.clear();
                for (Entry entry : simpleHumEntries) {
                    humidityDataSet.addEntry(entry);
                }
                Log.d(TAG, "Updated humidity data set");
            }
            
            // Force chart update
            if (lineData != null) {
                lineData.notifyDataChanged();
                binding.trendChart.notifyDataSetChanged();
                binding.trendChart.invalidate();
                Log.d(TAG, "Chart updated with simple data");
            }
            
            Log.d(TAG, "=== END SIMPLE CHART DATA TEST ===");
        } catch (Exception e) {
            Log.e(TAG, "Error in testSimpleChartData: " + e.getMessage(), e);
        }
    }
    
    /**
     * Test ESP32 data flow to verify parsing and processing
     */
    private void testEsp32DataFlow() {
        try {
            Log.d(TAG, "=== TESTING ESP32 DATA FLOW ===");
            
            // Simulate ESP32 sending temperature data
            String tempData = "T:25.5";
            Log.d(TAG, "Simulating temperature data: " + tempData);
            onRawDataReceived(tempData);
            
            // Wait a bit
            Thread.sleep(100);
            
            // Simulate ESP32 sending humidity data
            String humData = "H:65.2";
            Log.d(TAG, "Simulating humidity data: " + humData);
            onRawDataReceived(humData);
            
            // Wait a bit
            Thread.sleep(100);
            
            // Simulate ESP32 sending battery data
            String batteryData = "B:85";
            Log.d(TAG, "Simulating battery data: " + batteryData);
            onRawDataReceived(batteryData);
            
            Log.d(TAG, "ESP32 data flow test completed");
            Log.d(TAG, "Chart data size: " + chartData.size());
            Log.d(TAG, "Last reading: " + (lastReading != null ? 
                  "Temp=" + lastReading.getTemperature() + "°C, Hum=" + lastReading.getHumidity() + "%" : "null"));
            
            Log.d(TAG, "=== END ESP32 DATA FLOW TEST ===");
        } catch (Exception e) {
            Log.e(TAG, "Error in testEsp32DataFlow: " + e.getMessage(), e);
        }
    }
    
    /**
     * Initialize UI with empty state (no data, no active session)
     */
    private void initializeEmptyState() {
        try {
            Log.d(TAG, "=== INITIALIZING EMPTY STATE ===");
            
            // Clear any existing data
            chartData.clear();
            
            // Add a few sample data points to test chart visibility
            addSampleDataForTesting();
            
            // Hide progress section
            if (binding.progressSection != null) {
                binding.progressSection.setVisibility(View.GONE);
            }
            
            // Disable start curing button until connected
            if (binding.startCuringButton != null) {
                binding.startCuringButton.setEnabled(false);
                binding.startCuringButton.setText("Connect ESP32 First");
            }
            
            // Update chart with sample data
            updateChart();
            
            // Start continuous chart updates every 2 seconds
            startChartUpdateInterval();
            
            Log.d(TAG, "Empty state initialized with sample data - waiting for ESP32 connection");
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing empty state: " + e.getMessage(), e);
        }
    }
    
    /**
     * Add sample data for testing chart visibility
     */
    private void addSampleDataForTesting() {
        try {
            // Add 10 sample data points to test chart lines (0s to 18s)
            for (int i = 0; i < 10; i++) {
                SensorReading sampleReading = new SensorReading();
                // Create realistic temperature and humidity variations
                float tempVariation = (float) Math.sin(i * 0.3) * 2.0f; // Sine wave variation
                float humVariation = (float) Math.cos(i * 0.2) * 3.0f; // Cosine wave variation
                
                sampleReading.setTemperature(24.5f + tempVariation); // 24.5°C ± 2°C
                sampleReading.setHumidity(61.0f + humVariation); // 61.0% ± 3%
                sampleReading.setTimestamp(System.currentTimeMillis() - ((9 - i) * 2000)); // 18s, 16s, 14s, 12s, 10s, 8s, 6s, 4s, 2s, 0s ago
                chartData.add(sampleReading);
            }
            Log.d(TAG, "Added " + chartData.size() + " sample data points for testing (0s to 18s)");
        } catch (Exception e) {
            Log.e(TAG, "Error adding sample data: " + e.getMessage(), e);
        }
    }
    
    /**
     * Add a new data point every 2 seconds to simulate continuous progression
     */
    private void addContinuousDataPoint() {
        try {
            if (chartData == null) {
                chartData = new ArrayList<>();
            }
            
            // Get the last data point or create a baseline
            SensorReading lastReading = null;
            if (!chartData.isEmpty()) {
                lastReading = chartData.get(chartData.size() - 1);
            }
            
            // Create new data point
            SensorReading newReading = new SensorReading();
            
            if (lastReading != null) {
                // Add small variations to the last reading
                float tempVariation = (float) (Math.random() - 0.5) * 0.5f; // ±0.25°C variation
                float humVariation = (float) (Math.random() - 0.5) * 1.0f; // ±0.5% variation
                
                newReading.setTemperature(lastReading.getTemperature() + tempVariation);
                newReading.setHumidity(lastReading.getHumidity() + humVariation);
            } else {
                // Baseline values if no previous data
                newReading.setTemperature(24.5f);
                newReading.setHumidity(61.0f);
            }
            
            // Set timestamp to current time
            newReading.setTimestamp(System.currentTimeMillis());
            
            // Add to chart data
            chartData.add(newReading);
            
            // Keep only last 50 data points to prevent memory issues
            if (chartData.size() > 50) {
                chartData.remove(0);
            }
            
            Log.d(TAG, "Added continuous data point: T=" + newReading.getTemperature() + 
                  "°C, H=" + newReading.getHumidity() + "%");
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding continuous data point: " + e.getMessage(), e);
        }
    }
    
    /**
     * Start 2-second chart update interval
     */
    private void startChartUpdateInterval() {
        try {
            Log.d(TAG, "Starting 2-second chart update interval");
            
            // Stop any existing interval
            stopChartUpdateInterval();
            
            // Create new runnable for chart updates
            chartUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        // Check data flow status
                        checkDataFlowStatus();
                        
                        // Always update chart every 2 seconds, even if no new data
                        updateChartContinuous();
                        
                        // Add a new data point every 2 seconds to simulate continuous progression
                        addContinuousDataPoint();
                        
                        Log.d(TAG, "Chart updated via 2-second interval");
                        
                        // Schedule next update in 2 seconds
                        if (chartUpdateHandler != null) {
                            chartUpdateHandler.postDelayed(this, 2000);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in chart update interval: " + e.getMessage(), e);
                    }
                }
            };
            
            // Start the interval
            if (chartUpdateHandler != null) {
                chartUpdateHandler.post(chartUpdateRunnable);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting chart update interval: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if data is still flowing from ESP32 (within last 10 seconds)
     */
    private void checkDataFlowStatus() {
        try {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastData = currentTime - lastDataReceivedTime;
            
            // If no data received in last 10 seconds, disable start curing button
            if (hasReceivedData && timeSinceLastData > 10000) {
                if (binding.startCuringButton != null && binding.startCuringButton.isEnabled()) {
                    binding.startCuringButton.setEnabled(false);
                    binding.startCuringButton.setText("Data Stopped - Reconnecting...");
                    Log.w(TAG, "Data flow stopped - disabling start curing button");
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking data flow status: " + e.getMessage(), e);
        }
    }
    
    /**
     * Stop chart update interval
     */
    private void stopChartUpdateInterval() {
        try {
            Log.d(TAG, "Stopping chart update interval");
            
            if (chartUpdateHandler != null && chartUpdateRunnable != null) {
                chartUpdateHandler.removeCallbacks(chartUpdateRunnable);
                chartUpdateRunnable = null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping chart update interval: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create a sample curing session for testing
     */
    private void createSampleCuringSession() {
        try {
            Log.d(TAG, "=== CREATING SAMPLE CURING SESSION ===");
            
            executor.execute(() -> {
                try {
                    // Check if there's already an active session
                    CuringSession existingSession = curingSessionDao.getActiveSession();
                    if (existingSession != null) {
                        Log.d(TAG, "Active session already exists, using it");
                        mainHandler.post(() -> {
                            activeSession = existingSession;
                            updateCuringUI();
                        });
                        return;
                    }
                    
                    // Create a new sample curing session
                    CuringSession session = new CuringSession();
                    session.setStartTimestamp(System.currentTimeMillis() - 3600000); // Started 1 hour ago
                    session.setTargetMaturity(MaturityCalculator.getHighStrengthTarget()); // High strength target (7000)
                    session.setCurrentMaturity(1500.0f); // Simulate significant progress (about 21% of 7000)
                    session.setActive(true);
                    session.setNotes("Sample curing session for testing");
                    
                    long sessionId = curingSessionDao.insert(session);
                    session.setId(sessionId);
                    
                    mainHandler.post(() -> {
                        activeSession = session;
                        updateCuringUI();
                        Log.d(TAG, "Sample curing session created with " + session.getCompletionPercentage() + "% progress");
                        
                        // Force UI update to show progress immediately
                        updateCuringUI();
                    });
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error creating sample curing session: " + e.getMessage(), e);
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error in createSampleCuringSession: " + e.getMessage(), e);
        }
    }
    
    /**
     * Test battery color changes with different percentages
     */
    private void testBatteryColors() {
        try {
            Log.d(TAG, "=== TESTING BATTERY COLOR CHANGES ===");
            
            // Test different battery levels to show color changes
            int[] testLevels = {95, 65, 25, 5};
            String[] testNames = {"Excellent (Green)", "Good (Yellow)", "Low (Orange)", "Critical (Red)"};
            
            for (int i = 0; i < testLevels.length; i++) {
                Log.d(TAG, "Testing " + testNames[i] + ": " + testLevels[i] + "%");
                updateBatteryDisplay(testLevels[i]);
                
                // Small delay to see the changes
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // Reset to disconnected state
            updateBatteryDisplay(-1);
            Log.d(TAG, "=== END BATTERY COLOR TEST ===");
        } catch (Exception e) {
            Log.e(TAG, "Error testing battery colors: " + e.getMessage(), e);
        }
    }
    
    /**
     * Start a test curing session for demonstration
     */
    private void startTestCuringSession() {
        try {
            Log.d(TAG, "=== STARTING TEST CURING SESSION ===");
            
            // Create a test curing session with high strength target
            CuringSession testSession = new CuringSession();
            testSession.setStartTimestamp(System.currentTimeMillis());
            testSession.setTargetMaturity(MaturityCalculator.getHighStrengthTarget());
            testSession.setCurrentMaturity(0f);
            testSession.setActive(true);
            
            activeSession = testSession;
            
            Log.d(TAG, "Test curing session created:");
            Log.d(TAG, "- Target maturity: " + testSession.getTargetMaturity() + " degree-hours");
            Log.d(TAG, "- Current maturity: " + testSession.getCurrentMaturity());
            Log.d(TAG, "- Active: " + testSession.isActive());
            
            updateCuringUI();
            
            // Simulate some progress for testing
            simulateCuringProgress();
            
            Log.d(TAG, "=== END TEST CURING SESSION SETUP ===");
        } catch (Exception e) {
            Log.e(TAG, "Error starting test curing session: " + e.getMessage(), e);
        }
    }
    
    /**
     * Simulate curing progress for testing
     */
    private void simulateCuringProgress() {
        try {
            if (activeSession == null) return;
            
            Log.d(TAG, "=== SIMULATING CURING PROGRESS ===");
            
            // Simulate some curing progress for testing
            // At 25°C with datum temperature of 0°C: (25-0) * 24 = 600 degree-hours per day
            float simulatedMaturity = 1200.0f; // Simulate 2 days of curing
            activeSession.setCurrentMaturity(simulatedMaturity);
            
            float progress = MaturityCalculator.calculateCompletionPercentage(simulatedMaturity);
            
            // Calculate realistic time estimates
            float avgTemp = 25.0f; // Simulated average temperature
            float daysToCompletion = MaturityCalculator.estimateDaysToCompletion(
                simulatedMaturity, activeSession.getTargetMaturity(), avgTemp);
            
            Log.d(TAG, "Simulated progress: " + progress + "%");
            Log.d(TAG, "Current maturity: " + simulatedMaturity + " degree-hours");
            Log.d(TAG, "Target maturity: " + activeSession.getTargetMaturity() + " degree-hours");
            Log.d(TAG, "Average temperature: " + avgTemp + "°C");
            Log.d(TAG, "Estimated days to completion: " + daysToCompletion);
            
            updateCuringUI();
            
            Log.d(TAG, "=== END SIMULATING CURING PROGRESS ===");
        } catch (Exception e) {
            Log.e(TAG, "Error simulating curing progress: " + e.getMessage(), e);
        }
    }
    
    /**
     * Calculate average temperature from recent data
     */
    private float calculateAverageTemperature() {
        try {
            if (chartData == null || chartData.isEmpty()) {
                return 0f;
            }
            
            // Use last 10 readings or all if less than 10
            int count = Math.min(10, chartData.size());
            float sum = 0f;
            
            for (int i = chartData.size() - count; i < chartData.size(); i++) {
                sum += chartData.get(i).getTemperature();
            }
            
            float avgTemp = sum / count;
            Log.d(TAG, "Average temperature from " + count + " readings: " + avgTemp + "°C");
            return avgTemp;
        } catch (Exception e) {
            Log.e(TAG, "Error calculating average temperature: " + e.getMessage(), e);
            return 0f;
        }
    }
    
    /**
     * Sync current data to storage
     */
    private void syncData() {
        try {
            if (!bleManager.isConnected()) {
                Toast.makeText(this, "Not connected to ESP32", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Save current sensor reading if available
            if (lastReading != null) {
                executor.execute(() -> {
                    try {
                        // Add battery level to the reading if we have it
                        if (batteryLevel >= 0) {
                            // Note: You might need to add a battery field to SensorReading class
                            // For now, we'll just save the current reading
                        }
                        
                        sensorReadingDao.insert(lastReading);
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Data synced successfully", Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "Data synced: " + lastReading.toString());
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error syncing data: " + e.getMessage(), e);
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Error syncing data", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            } else {
                Toast.makeText(this, "No data to sync", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in syncData: " + e.getMessage(), e);
            Toast.makeText(this, "Error syncing data", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Update connection status UI
     */
    private void updateConnectionStatus(boolean connected, String status) {
        try {
            Log.d(TAG, "updateConnectionStatus called: connected=" + connected + ", status=" + status);
            if (connected) {
                if (binding.connectionStatusIcon != null) {
                    binding.connectionStatusIcon.setImageResource(R.drawable.ic_bluetooth_connected);
                    binding.connectionStatusIcon.setColorFilter(getColor(R.color.success_color));
                }
                if (binding.connectionStatusText != null) {
                    binding.connectionStatusText.setText("Connected");
                }
                if (binding.connectionStatusSubtext != null) {
                    binding.connectionStatusSubtext.setText("Receiving live data from ESP32");
                }
                if (binding.connectButton != null) {
                    binding.connectButton.setText("Disconnect");
                    Log.d(TAG, "Button text set to: Disconnect");
                }
                // Enable sync button when connected
                if (binding.syncDataButton != null) {
                    binding.syncDataButton.setEnabled(true);
                }
            } else {
                if (binding.connectionStatusIcon != null) {
                    binding.connectionStatusIcon.setImageResource(R.drawable.ic_bluetooth_disabled);
                    binding.connectionStatusIcon.setColorFilter(getColor(R.color.error_color));
                }
                if (binding.connectionStatusText != null) {
                    binding.connectionStatusText.setText("Disconnected");
                }
                if (binding.connectionStatusSubtext != null) {
                    binding.connectionStatusSubtext.setText("Connect to ESP32 to start monitoring");
                }
                if (binding.connectButton != null) {
                    binding.connectButton.setText("Connect");
                    Log.d(TAG, "Button text set to: Connect");
                }
                // Disable sync button when disconnected
                if (binding.syncDataButton != null) {
                    binding.syncDataButton.setEnabled(false);
                }
                // Reset battery display when disconnected
                batteryLevel = -1;
                updateBatteryDisplay(batteryLevel);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateConnectionStatus: " + e.getMessage(), e);
        }
    }
    
    /**
     * Update temperature display with animation
     */
    private void updateTemperatureDisplay(SensorReading reading) {
        try {
            if (reading == null) {
                if (binding.temperatureValue != null) {
                    binding.temperatureValue.setText("--°C");
                }
                if (binding.temperatureStatus != null) {
                    binding.temperatureStatus.setText("No data");
                }
                return;
            }
            
            float temperature = reading.getTemperature();
            
            // Animate temperature value
            if (binding.temperatureValue != null) {
                animateValue(binding.temperatureValue, temperature, "°C");
            }
            
            // Update status
            String status;
            if (temperature < 10) {
                status = "Too cold";
            } else if (temperature > 35) {
                status = "Too hot";
            } else {
                status = "Optimal";
            }
            if (binding.temperatureStatus != null) {
                binding.temperatureStatus.setText(status);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateTemperatureDisplay: " + e.getMessage(), e);
        }
    }
    
    /**
     * Update humidity display with animation
     */
    private void updateHumidityDisplay(SensorReading reading) {
        try {
            if (reading == null) {
                if (binding.humidityValue != null) {
                    binding.humidityValue.setText("--%");
                }
                if (binding.humidityStatus != null) {
                    binding.humidityStatus.setText("No data");
                }
                return;
            }
            
            float humidity = reading.getHumidity();
            Log.d(TAG, "updateHumidityDisplay called with humidity: " + humidity);
            
            // Animate humidity value
            if (binding.humidityValue != null) {
                Log.d(TAG, "Calling animateValue for humidity: " + humidity + " with suffix: %");
                animateValue(binding.humidityValue, humidity, "%");
            }
            
            // Update status
            String status;
            if (humidity < 30) {
                status = "Low humidity";
            } else if (humidity > 80) {
                status = "High humidity";
            } else {
                status = "Optimal";
            }
            if (binding.humidityStatus != null) {
                binding.humidityStatus.setText(status);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateHumidityDisplay: " + e.getMessage(), e);
        }
    }
    
    /**
     * Animate numeric value changes
     */
    private void animateValue(android.widget.TextView textView, float targetValue, String suffix) {
        try {
            if (textView == null) return;
            
            String currentText = textView.getText().toString();
            Log.d(TAG, "animateValue called - currentText: '" + currentText + "', targetValue: " + targetValue + ", suffix: '" + suffix + "'");
            
            float currentValue = 0;
            
            try {
                currentValue = Float.parseFloat(currentText.replaceAll("[^\\d.-]", ""));
                Log.d(TAG, "animateValue - parsed currentValue: " + currentValue);
            } catch (NumberFormatException e) {
                Log.d(TAG, "animateValue - failed to parse currentValue, using 0");
                // If parsing fails, start from 0
            }
            
            android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(currentValue, targetValue);
            animator.setDuration(500);
            animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                String formattedText = String.format(Locale.getDefault(), "%.1f%s", value, suffix);
                Log.d(TAG, "animateValue - setting text to: '" + formattedText + "'");
                textView.setText(formattedText);
            });
            animator.start();
        } catch (Exception e) {
            Log.e(TAG, "Error in animateValue: " + e.getMessage(), e);
        }
    }
    
    /**
     * Animate battery percentage changes
     */
    private void animateBatteryValue(int targetValue) {
        try {
            Log.d(TAG, "animateBatteryValue called with target: " + targetValue);
            
            if (binding.batteryValue == null) {
                Log.e(TAG, "batteryValue TextView is null!");
                return;
            }
            
            // Get current value from text
            String currentText = binding.batteryValue.getText().toString();
            Log.d(TAG, "Current battery text: '" + currentText + "'");
            
            int currentValue = 0;
            try {
                if (currentText.contains("%")) {
                    currentValue = Integer.parseInt(currentText.replace("%", ""));
                } else if (!currentText.equals("N/A")) {
                    currentValue = Integer.parseInt(currentText);
                }
                Log.d(TAG, "Current battery value parsed: " + currentValue);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse current battery value: '" + currentText + "'", e);
                currentValue = 0;
            }
            
            // Create value animator
            android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(currentValue, targetValue);
            animator.setDuration(800);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            
            animator.addUpdateListener(animation -> {
                int animatedValue = (int) animation.getAnimatedValue();
                binding.batteryValue.setText(animatedValue + "%");
            });
            
            animator.start();
        } catch (Exception e) {
            Log.e(TAG, "Error in animateBatteryValue: " + e.getMessage(), e);
            // Fallback to direct text update
            binding.batteryValue.setText(targetValue + "%");
        }
    }
    
    /**
     * Update chart with continuous data from zero
     */
    private void updateChartContinuous() {
        try {
            Log.d(TAG, "=== UPDATE CHART CONTINUOUS CALLED ===");
            Log.d(TAG, "Chart data size: " + chartData.size());
            Log.d(TAG, "Chart view null: " + (binding.trendChart == null));
            
            if (chartData.isEmpty() || binding.trendChart == null) {
                Log.d(TAG, "Chart update skipped - chartData empty: " + chartData.isEmpty() + 
                      ", chart null: " + (binding.trendChart == null));
                return;
            }
            
            // Check if chart is in a valid state
            if (binding.trendChart.getWidth() <= 0 || binding.trendChart.getHeight() <= 0) {
                Log.w(TAG, "Chart has invalid dimensions, skipping update");
                return;
            }
            
            Log.d(TAG, "Updating chart with continuous data from " + chartData.size() + " data points");
            
            // Clear existing data sets
            if (temperatureDataSet != null) {
                temperatureDataSet.clear();
            }
            if (humidityDataSet != null) {
                humidityDataSet.clear();
            }
            
            // Build continuous data from zero
            for (int i = 0; i < chartData.size(); i++) {
                SensorReading reading = chartData.get(i);
                
                // Validate reading data
                if (reading == null) {
                    Log.w(TAG, "Skipping null reading at index " + i);
                    continue;
                }
                
                // Use time-based X values (0s, 2s, 4s, 6s...) representing 2-second intervals
                float xValue = i * 2.0f; // Each data point represents 2 seconds
                
                // Validate temperature and humidity values (Y-axis)
                float temperature = reading.getTemperature();
                float humidity = reading.getHumidity();
                
                // Check for valid numeric values
                if (Float.isNaN(temperature) || Float.isInfinite(temperature)) {
                    Log.w(TAG, "Invalid temperature value: " + temperature + " at index " + i);
                    temperature = 0f;
                }
                if (Float.isNaN(humidity) || Float.isInfinite(humidity)) {
                    Log.w(TAG, "Invalid humidity value: " + humidity + " at index " + i);
                    humidity = 0f;
                }
                
                // Clamp values to reasonable ranges
                temperature = Math.max(-50f, Math.min(100f, temperature));
                humidity = Math.max(0f, Math.min(100f, humidity));
                
                // Add entries to create continuous line
                if (temperatureDataSet != null) {
                    temperatureDataSet.addEntry(new Entry(xValue, temperature));
                }
                if (humidityDataSet != null) {
                    humidityDataSet.addEntry(new Entry(xValue, humidity));
                }
            }
            
            Log.d(TAG, "Continuous chart data sets updated - Temp entries: " + 
                  (temperatureDataSet != null ? temperatureDataSet.getEntryCount() : 0) +
                  ", Hum entries: " + (humidityDataSet != null ? humidityDataSet.getEntryCount() : 0));
            
            // Auto-scale both X and Y axes based on data range
            if (chartData.size() > 0) {
                // Auto-scale X-axis (time range in seconds: 0s, 2s, 4s, 6s...)
                float minX = 0f;
                float maxX = Math.max(0f, (chartData.size() - 1) * 2.0f); // Convert to seconds
                float minValue = Float.MAX_VALUE;
                float maxValue = Float.MIN_VALUE;
                
                // Find min/max values from data
                for (SensorReading reading : chartData) {
                    if (reading != null) {
                        float temperature = reading.getTemperature();
                        float humidity = reading.getHumidity();
                        
                        if (!Float.isNaN(temperature) && !Float.isInfinite(temperature)) {
                            minValue = Math.min(minValue, temperature);
                            maxValue = Math.max(maxValue, temperature);
                        }
                        if (!Float.isNaN(humidity) && !Float.isInfinite(humidity)) {
                            minValue = Math.min(minValue, humidity);
                            maxValue = Math.max(maxValue, humidity);
                        }
                    }
                }
                
                // Ensure we have valid ranges
                if (minValue == Float.MAX_VALUE || maxValue == Float.MIN_VALUE) {
                    minValue = 0f;
                    maxValue = 100f;
                }
                
                // Add padding to X-axis range
                float xPadding = Math.max(1f, maxX * 0.1f); // 10% padding, minimum 1
                minX = Math.max(0f, minX - xPadding);
                maxX = maxX + xPadding;
                
                // Add padding to Y-axis range
                float valueRange = maxValue - minValue;
                if (valueRange <= 0) {
                    valueRange = 10f; // Default value range
                }
                float valuePadding = valueRange * 0.1f; // 10% padding
                minValue = Math.max(0, minValue - valuePadding);
                maxValue = maxValue + valuePadding;
                
                // Update X-axis range (incremental values)
                XAxis xAxis = binding.trendChart.getXAxis();
                xAxis.setAxisMinimum(minX);
                xAxis.setAxisMaximum(maxX);
                
                // Update Y-axis range (sensor values)
                YAxis leftAxis = binding.trendChart.getAxisLeft();
                leftAxis.setAxisMinimum(minValue);
                leftAxis.setAxisMaximum(maxValue);
                
                Log.d(TAG, "X-axis (data points) auto-scaled to range: " + minX + " - " + maxX);
                Log.d(TAG, "Y-axis (values) auto-scaled to range: " + minValue + " - " + maxValue);
            }
            
            // Notify chart of data change
            if (lineData != null) {
                lineData.notifyDataChanged();
                binding.trendChart.notifyDataSetChanged();
                binding.trendChart.invalidate();
                Log.d(TAG, "Continuous chart invalidated and data changed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateChartContinuous: " + e.getMessage(), e);
            // Reset chart to prevent further crashes
            resetChart();
        }
    }
    
    /**
     * Update the real-time chart (for new data points)
     */
    private void updateChart() {
        try {
            Log.d(TAG, "=== UPDATE CHART CALLED ===");
            Log.d(TAG, "Chart data size: " + chartData.size());
            Log.d(TAG, "Chart view null: " + (binding.trendChart == null));
            
            if (chartData.isEmpty() || binding.trendChart == null) {
                Log.d(TAG, "Chart update skipped - chartData empty: " + chartData.isEmpty() + 
                      ", chart null: " + (binding.trendChart == null));
                return;
            }
            
            // Check if chart is in a valid state
            if (binding.trendChart.getWidth() <= 0 || binding.trendChart.getHeight() <= 0) {
                Log.w(TAG, "Chart has invalid dimensions, skipping update");
                return;
            }
            
            Log.d(TAG, "Updating chart with " + chartData.size() + " data points");
            
            // Create new data sets instead of clearing existing ones
            List<Entry> tempEntries = new ArrayList<>();
            List<Entry> humEntries = new ArrayList<>();
            
            // Validate and add data points
            for (int i = 0; i < chartData.size(); i++) {
                SensorReading reading = chartData.get(i);
                
                // Validate reading data
                if (reading == null) {
                    Log.w(TAG, "Skipping null reading at index " + i);
                    continue;
                }
                
                // Use time-based X values (0s, 2s, 4s, 6s...) representing 2-second intervals
                float xValue = i * 2.0f; // Each data point represents 2 seconds
                
                // Validate temperature and humidity values (Y-axis)
                float temperature = reading.getTemperature();
                float humidity = reading.getHumidity();
                
                // Check for valid numeric values
                if (Float.isNaN(temperature) || Float.isInfinite(temperature)) {
                    Log.w(TAG, "Invalid temperature value: " + temperature + " at index " + i);
                    temperature = 0f;
                }
                if (Float.isNaN(humidity) || Float.isInfinite(humidity)) {
                    Log.w(TAG, "Invalid humidity value: " + humidity + " at index " + i);
                    humidity = 0f;
                }
                
                // Clamp values to reasonable ranges
                temperature = Math.max(-50f, Math.min(100f, temperature));
                humidity = Math.max(0f, Math.min(100f, humidity));
                
                // Create entries: X = data point index, Y = sensor value
                tempEntries.add(new Entry(xValue, temperature));
                humEntries.add(new Entry(xValue, humidity));
            }
            
            // Validate that we have valid entries
            if (tempEntries.isEmpty() || humEntries.isEmpty()) {
                Log.w(TAG, "No valid entries to display on chart");
                return;
            }
            
            // Update data sets - clear and rebuild to ensure lines show
            if (temperatureDataSet != null) {
                temperatureDataSet.clear();
                for (Entry entry : tempEntries) {
                    temperatureDataSet.addEntry(entry);
                }
            }
            if (humidityDataSet != null) {
                humidityDataSet.clear();
                for (Entry entry : humEntries) {
                    humidityDataSet.addEntry(entry);
                }
            }
            
            Log.d(TAG, "Chart data sets updated - Temp entries: " + tempEntries.size() +
                  ", Hum entries: " + humEntries.size());
            
            // Auto-scale both X and Y axes based on data range
            if (!tempEntries.isEmpty() || !humEntries.isEmpty()) {
                // Auto-scale X-axis (time range in seconds)
                float minX = 0f;
                float maxX = Math.max(0f, (tempEntries.size() - 1) * 2.0f); // Convert to seconds
                float minValue = Float.MAX_VALUE;
                float maxValue = Float.MIN_VALUE;
                
                // Find min/max from both temperature and humidity data
                for (Entry entry : tempEntries) {
                    if (!Float.isNaN(entry.getY()) && !Float.isInfinite(entry.getY())) {
                    minValue = Math.min(minValue, entry.getY());
                    maxValue = Math.max(maxValue, entry.getY());
                    }
                }
                for (Entry entry : humEntries) {
                    if (!Float.isNaN(entry.getY()) && !Float.isInfinite(entry.getY())) {
                    minValue = Math.min(minValue, entry.getY());
                    maxValue = Math.max(maxValue, entry.getY());
                    }
                }
                
                // Ensure we have valid ranges
                if (maxX < 0) {
                    maxX = 10f; // Default range
                }
                if (minValue == Float.MAX_VALUE || maxValue == Float.MIN_VALUE) {
                    minValue = 0f;
                    maxValue = 100f;
                }
                
                // Add padding to X-axis range
                float xPadding = Math.max(1f, maxX * 0.1f); // 10% padding, minimum 1
                minX = Math.max(0f, minX - xPadding);
                maxX = maxX + xPadding;
                
                // Add padding to value range
                float valueRange = maxValue - minValue;
                if (valueRange <= 0) {
                    valueRange = 10f; // Default value range
                }
                float valuePadding = valueRange * 0.1f; // 10% padding
                minValue = Math.max(0, minValue - valuePadding);
                maxValue = maxValue + valuePadding;
                
                // Update X-axis range (data points)
                XAxis xAxis = binding.trendChart.getXAxis();
                xAxis.setAxisMinimum(minX);
                xAxis.setAxisMaximum(maxX);
                
                // Update Y-axis range (sensor values)
                YAxis leftAxis = binding.trendChart.getAxisLeft();
                leftAxis.setAxisMinimum(minValue);
                leftAxis.setAxisMaximum(maxValue);
                
                Log.d(TAG, "X-axis (data points) auto-scaled to range: " + minX + " - " + maxX);
                Log.d(TAG, "Y-axis (values) auto-scaled to range: " + minValue + " - " + maxValue);
            }
            
            // Notify chart of data change
            if (lineData != null) {
                lineData.notifyDataChanged();
                binding.trendChart.notifyDataSetChanged();
                binding.trendChart.invalidate();
                Log.d(TAG, "Chart invalidated and data changed");
                
                // Force chart to redraw
                binding.trendChart.post(() -> {
                    binding.trendChart.invalidate();
                    Log.d(TAG, "Chart force redraw completed");
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateChart: " + e.getMessage(), e);
            // Reset chart to prevent further crashes
            resetChart();
        }
    }
    
    /**
     * Start the curing process
     */
    private void startCuringProcess() {
        try {
            // Check if ESP32 is connected and receiving data
            if (!bleManager.isConnected()) {
                Toast.makeText(this, "Please connect to ESP32 first", Toast.LENGTH_LONG).show();
                Log.w(TAG, "Cannot start curing - ESP32 not connected");
                return;
            }
            
            if (!hasReceivedData) {
                Toast.makeText(this, "Please wait for sensor data from ESP32", Toast.LENGTH_LONG).show();
                Log.w(TAG, "Cannot start curing - no data received from ESP32 yet");
                return;
            }
            
            executor.execute(() -> {
                try {
                    CuringSession session = new CuringSession();
                    session.setStartTimestamp(System.currentTimeMillis());
                    // Use high strength target (7000 degree-hours)
                    session.setTargetMaturity(MaturityCalculator.getHighStrengthTarget());
                    session.setCurrentMaturity(0f);
                    session.setActive(true);
                    
                    long sessionId = curingSessionDao.insert(session);
                    session.setId(sessionId);
                    
                    mainHandler.post(() -> {
                        activeSession = session;
                        updateCuringUI();
                        Toast.makeText(this, "Curing process started (high strength target)", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error starting curing process: " + e.getMessage(), e);
                    mainHandler.post(() -> 
                        Toast.makeText(this, "Error starting curing process", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in startCuringProcess: " + e.getMessage(), e);
        }
    }
    
    /**
     * Stop the curing process
     */
    private void stopCuringProcess() {
        try {
            executor.execute(() -> {
                try {
                    if (activeSession != null) {
                        activeSession.setEndTimestamp(System.currentTimeMillis());
                        activeSession.setActive(false);
                        curingSessionDao.update(activeSession);
                        
                        mainHandler.post(() -> {
                            activeSession = null;
                            updateCuringUI();
                            Toast.makeText(this, "Curing process stopped", Toast.LENGTH_SHORT).show();
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping curing process: " + e.getMessage(), e);
                    mainHandler.post(() -> 
                        Toast.makeText(this, "Error stopping curing process", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in stopCuringProcess: " + e.getMessage(), e);
        }
    }
    
    /**
     * Update curing UI based on current session
     */
    private void updateCuringUI() {
        try {
            Log.d(TAG, "=== UPDATE CURING UI CALLED ===");
            Log.d(TAG, "Active session null: " + (activeSession == null));
            
            if (activeSession == null) {
                Log.d(TAG, "No active session, hiding progress section");
                if (binding.progressSection != null) {
                    binding.progressSection.setVisibility(View.GONE);
                }
                return;
            }
            
            Log.d(TAG, "=== UPDATING CURING UI ===");
            Log.d(TAG, "Active session details:");
            Log.d(TAG, "- Current maturity: " + activeSession.getCurrentMaturity() + " °C·h");
            Log.d(TAG, "- Current strength: " + activeSession.getCurrentStrength() + " MPa");
            Log.d(TAG, "- Target strength: " + MaturityCalculator.getDefaultTargetStrength() + " MPa");
            Log.d(TAG, "- Active: " + activeSession.isActive());
            Log.d(TAG, "- Completion percentage: " + activeSession.getCompletionPercentage() + "%");
            
            if (binding.progressSection != null) {
                binding.progressSection.setVisibility(View.VISIBLE);
                Log.d(TAG, "Progress section made visible");
            } else {
                Log.e(TAG, "Progress section is null!");
            }
            
            // Update progress with smooth animation
            float currentMaturity = activeSession.getCurrentMaturity();
            float targetStrength = MaturityCalculator.getDefaultTargetStrength();
            float currentStrength = MaturityCalculator.calculateStrengthFromMaturity(currentMaturity);
            float progress = activeSession.getCompletionPercentage();
            
            Log.d(TAG, "=== PROGRESS CALCULATION DEBUG ===");
            Log.d(TAG, "Current maturity: " + currentMaturity + " °C·h");
            Log.d(TAG, "Current strength: " + currentStrength + " MPa");
            Log.d(TAG, "Target strength: " + targetStrength + " MPa");
            Log.d(TAG, "Calculated progress: " + progress + "%");
            Log.d(TAG, "=== END PROGRESS DEBUG ===");
            
            if (binding.curingProgressCircle != null) {
                Log.d(TAG, "Updating progress circle to: " + (int) progress);
                animateProgress(binding.curingProgressCircle, (int) progress);
            } else {
                Log.e(TAG, "Progress circle is null!");
            }
            
            // Update progress text
            if (binding.curingProgressText != null) {
                String progressStr = String.format(Locale.getDefault(), "%.1f%%", progress);
                binding.curingProgressText.setText(progressStr);
                Log.d(TAG, "Updated progress text: " + progressStr);
            } else {
                Log.e(TAG, "curingProgressText is null!");
            }
            
            // Update maturity values
            if (binding.currentMaturityText != null) {
                String currentMaturityStr = String.format(Locale.getDefault(), "%.1f °C·h", activeSession.getCurrentMaturity());
                binding.currentMaturityText.setText(currentMaturityStr);
                Log.d(TAG, "Updated current maturity text: " + currentMaturityStr);
            } else {
                Log.e(TAG, "currentMaturityText is null!");
            }
            
            if (binding.targetMaturityText != null) {
                // Use the new high strength target instead of session target
                float targetMaturity = MaturityCalculator.getHighStrengthTarget();
                String targetMaturityStr = String.format(Locale.getDefault(), "%.0f °C·h", targetMaturity);
                binding.targetMaturityText.setText(targetMaturityStr);
                Log.d(TAG, "Updated target maturity text: " + targetMaturityStr);
            } else {
                Log.e(TAG, "targetMaturityText is null!");
            }
            
            // Update completion date with realistic calculation
            updateCompletionDate();
            
            // Update prediction card with maturity-based calculations
            updatePredictionCard();
            
            // Check for 80% completion notification
            if (progress >= 80 && progress < 81) {
                showCompletionNotification();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateCuringUI: " + e.getMessage(), e);
        }
    }
    
    /**
     * Animate progress circle smoothly
     */
    private void animateProgress(CircularProgressIndicator progressCircle, int targetProgress) {
        try {
            ValueAnimator animator = ValueAnimator.ofInt(progressCircle.getProgress(), targetProgress);
            animator.setDuration(500);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                int progress = (int) animation.getAnimatedValue();
                progressCircle.setProgress(progress);
            });
            animator.start();
        } catch (Exception e) {
            Log.e(TAG, "Error animating progress: " + e.getMessage(), e);
            progressCircle.setProgress(targetProgress);
        }
    }
    
    /**
     * Update completion date prediction
     */
    private void updateCompletionDate() {
        try {
            if (activeSession == null) return;
            
            // Calculate average temperature for realistic estimation
            float avgTemperature = calculateAverageTemperature();
            
            if (avgTemperature > 0) {
                // Use proper ASTM C1074 calculation for high strength (35 MPa)
                float daysToHigh = MaturityCalculator.calculateDaysToTarget(
                    activeSession.getCurrentMaturity(), 
                    MaturityCalculator.getHighStrengthTarget(),
                    avgTemperature
                );
                
                if (daysToHigh > 0) {
                    // Calculate completion date
                    long completionTime = System.currentTimeMillis() + (long)(daysToHigh * 24 * 60 * 60 * 1000);
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
                    String dateStr = sdf.format(new Date(completionTime));
                    
                    if (binding.completionDateText != null) {
                        binding.completionDateText.setText(dateStr);
                    }
                    
                    Log.d(TAG, "Completion date calculated: " + dateStr + 
                          " (" + String.format("%.1f", daysToHigh) + " days to high strength at " + avgTemperature + "°C)");
                } else if (daysToHigh == 0) {
                    if (binding.completionDateText != null) {
                        binding.completionDateText.setText("High strength reached");
                    }
                } else {
                    if (binding.completionDateText != null) {
                        binding.completionDateText.setText("Temperature too low");
                    }
                }
            } else {
                if (binding.completionDateText != null) {
                    binding.completionDateText.setText("No temperature data");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateCompletionDate: " + e.getMessage(), e);
        }
    }
    
    /**
     * Update prediction card with maturity-based calculations
     */
    private void updatePredictionCard() {
        try {
            if (activeSession == null) return;
            
            // Get current maturity and strength
            float currentMaturity = activeSession.getCurrentMaturity();
            float currentStrength = activeSession.getCurrentStrength();
            float targetStrength = MaturityCalculator.getDefaultTargetStrength();
            float curingPercent = activeSession.getCompletionPercentage();
            
            // Calculate 12-hour average temperature for prediction
            float avgTemperature12h = calculate12HourAverageTemperature();
            
            // Calculate daily gain and days to target using proper ASTM C1074 calculations
            float dailyGain = MaturityCalculator.calculateDailyGain(avgTemperature12h);
            float daysToHigh = MaturityCalculator.calculateDaysToTarget(currentMaturity, MaturityCalculator.getHighStrengthTarget(), avgTemperature12h);
            float daysToFull = MaturityCalculator.calculateDaysToFullStrength(currentMaturity, avgTemperature12h);
            
            // Predict completion date/time based on high strength (35 MPa)
            String predictedCompletion = "Calculating...";
            if (daysToHigh > 0) {
                // Calculate completion date/time
                long completionTime = System.currentTimeMillis() + (long)(daysToHigh * 24 * 60 * 60 * 1000);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
                predictedCompletion = sdf.format(new Date(completionTime));
            } else if (daysToHigh == 0) {
                predictedCompletion = "High strength reached";
            } else {
                predictedCompletion = "Temperature too low";
            }
            
            // Update UI elements (if they exist in the layout)
            // Note: These UI elements may need to be added to the layout
            Log.d(TAG, "=== PREDICTION CARD UPDATE ===");
            Log.d(TAG, "Current maturity: " + String.format("%.1f", currentMaturity) + " °C·h");
            Log.d(TAG, "Current strength: " + String.format("%.1f", currentStrength) + " MPa");
            Log.d(TAG, "Target strength: " + String.format("%.1f", targetStrength) + " MPa");
            Log.d(TAG, "Curing percent: " + String.format("%.1f", curingPercent) + "%");
            Log.d(TAG, "12h avg temp: " + String.format("%.1f", avgTemperature12h) + "°C");
            Log.d(TAG, "Daily gain: " + String.format("%.1f", dailyGain) + " °C·h/day");
            Log.d(TAG, "Days to high strength (35 MPa): " + String.format("%.1f", daysToHigh) + " days");
            Log.d(TAG, "Days to full strength (40 MPa): " + String.format("%.1f", daysToFull) + " days");
            Log.d(TAG, "Predicted completion: " + predictedCompletion);
            
            // TODO: Update actual UI elements when they are added to the layout
            // Example:
            // if (binding.currentMaturityCardText != null) {
            //     binding.currentMaturityCardText.setText(String.format("%.1f °C·h", currentMaturity));
            // }
            // if (binding.currentStrengthCardText != null) {
            //     binding.currentStrengthCardText.setText(String.format("%.1f MPa", currentStrength));
            // }
            // if (binding.curingPercentCardText != null) {
            //     binding.curingPercentCardText.setText(String.format("%.0f%%", curingPercent));
            // }
            // if (binding.predictedCompletionCardText != null) {
            //     binding.predictedCompletionCardText.setText(predictedCompletion);
            // }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in updatePredictionCard: " + e.getMessage(), e);
        }
    }
    
    /**
     * Calculate 12-hour average temperature for prediction
     */
    private float calculate12HourAverageTemperature() {
        try {
            if (chartData == null || chartData.isEmpty()) {
                return 0.0f;
            }
            
            long currentTime = System.currentTimeMillis();
            long twelveHoursAgo = currentTime - (12 * 60 * 60 * 1000); // 12 hours in milliseconds
            
            float totalTemp = 0.0f;
            int count = 0;
            
            // Get readings from last 12 hours
            for (SensorReading reading : chartData) {
                if (reading.getTimestamp() >= twelveHoursAgo) {
                    totalTemp += reading.getTemperature();
                    count++;
                }
            }
            
            if (count > 0) {
                float avgTemp = totalTemp / count;
                Log.d(TAG, "12-hour average temperature: " + avgTemp + "°C (from " + count + " readings)");
                return avgTemp;
            } else {
                // Fallback to overall average if no recent data
                return calculateAverageTemperature();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating 12-hour average temperature: " + e.getMessage(), e);
            return calculateAverageTemperature();
        }
    }
    
    /**
     * Process new sensor data
     */
    private void processNewData(SensorReading reading) {
        try {
            Log.d(TAG, "=== PROCESSING NEW DATA ===");
            
            // Validate reading
            if (reading == null) {
                Log.e(TAG, "Reading is null, cannot process");
                return;
            }
            
            if (reading.getTimestamp() == 0) {
                // Ensure each reading has a unique timestamp
                long currentTime = System.currentTimeMillis();
                if (lastReading != null && currentTime <= lastReading.getTimestamp()) {
                    // If current time is same or earlier than last reading, add 1ms
                    currentTime = lastReading.getTimestamp() + 1;
                }
                reading.setTimestamp(currentTime);
            }
            
            lastReading = reading;
            
            Log.d(TAG, "Processing new data - Temp: " + reading.getTemperature() + 
                  "°C, Hum: " + reading.getHumidity() + "%, Time: " + reading.getTimestamp());
            
            // Update displays
            updateTemperatureDisplay(reading);
            updateHumidityDisplay(reading);
            
            // Check for duplicate data (within 1 second)
            boolean isDuplicate = false;
            if (chartData != null && !chartData.isEmpty()) {
                SensorReading lastChartReading = chartData.get(chartData.size() - 1);
                if (Math.abs(reading.getTimestamp() - lastChartReading.getTimestamp()) < 1000) {
                    // Replace last entry
                    isDuplicate = true;
                    chartData.set(chartData.size() - 1, reading);
                    Log.d(TAG, "Replaced duplicate data point");
                }
            }
            
            if (!isDuplicate) {
                if (chartData == null) {
                    chartData = new ArrayList<>();
                }
                chartData.add(reading);
                Log.d(TAG, "Added new data point. Total points: " + chartData.size());
                Log.d(TAG, "Chart data now contains " + chartData.size() + " readings");
            } else {
                Log.d(TAG, "Duplicate data point replaced. Total points: " + chartData.size());
            }
            
            // Update chart by appending new data point
            Log.d(TAG, "Calling updateChart() with " + chartData.size() + " data points");
            updateChart();
            
            // Save to database
            if (executor != null && sensorReadingDao != null) {
            executor.execute(() -> {
                try {
                    sensorReadingDao.insert(reading);
                        Log.d(TAG, "Sensor reading saved to database");
                } catch (Exception e) {
                    Log.e(TAG, "Error saving sensor reading: " + e.getMessage(), e);
                }
            });
            } else {
                Log.w(TAG, "Executor or sensorReadingDao is null, cannot save to database");
            }
            
            // Mark that we've received data and enable start curing button
            hasReceivedData = true;
            lastDataReceivedTime = System.currentTimeMillis();
            
            // Enable start curing button now that we have data flowing
            if (binding.startCuringButton != null && !binding.startCuringButton.isEnabled()) {
                binding.startCuringButton.setEnabled(true);
                binding.startCuringButton.setText("Start Curing");
                Log.d(TAG, "Start curing button enabled - data is flowing from ESP32");
            }
            
            // Auto-create curing session if data is flowing and no session exists
            if (activeSession == null && hasReceivedData) {
                Log.d(TAG, "Auto-creating curing session since data is flowing");
                autoCreateCuringSession();
            }
            
            // Update curing progress if active
            if (activeSession != null) {
                updateCuringProgress(reading);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in processNewData: " + e.getMessage(), e);
        }
    }
    
    /**
     * Automatically create a curing session when data starts flowing
     */
    private void autoCreateCuringSession() {
        try {
            Log.d(TAG, "=== AUTO-CREATING CURING SESSION ===");
            
            executor.execute(() -> {
                try {
                    // Check if there's already an active session
                    CuringSession existingSession = curingSessionDao.getActiveSession();
                    if (existingSession != null) {
                        Log.d(TAG, "Active session already exists, using it");
                        mainHandler.post(() -> {
                            activeSession = existingSession;
                            updateCuringUI();
                        });
                        return;
                    }
                    
                    // Create a new curing session
                    CuringSession session = new CuringSession();
                    session.setStartTimestamp(System.currentTimeMillis());
                    session.setTargetMaturity(MaturityCalculator.getHighStrengthTarget()); // High strength target (7000)
                    session.setCurrentMaturity(0.0f);
                    session.setActive(true);
                    
                    // Save to database
                    long sessionId = curingSessionDao.insert(session);
                    session.setId(sessionId);
                    
                    Log.d(TAG, "Auto-created curing session with ID: " + sessionId);
                    Log.d(TAG, "Target maturity: " + session.getTargetMaturity() + " °C·h");
                    
                    mainHandler.post(() -> {
                        activeSession = session;
                        updateCuringUI();
                        Log.d(TAG, "Auto-created curing session is now active");
                    });
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error auto-creating curing session: " + e.getMessage(), e);
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error in autoCreateCuringSession: " + e.getMessage(), e);
        }
    }
    
    /**
     * Update curing progress based on new data (optimized with throttling)
     */
    private void updateCuringProgress(SensorReading reading) {
        try {
            if (activeSession == null || lastReading == null) return;
            
            long currentTime = System.currentTimeMillis();
            
            // Throttle updates to prevent excessive calculations
            if (currentTime - lastCuringUpdateTime < CURING_UPDATE_INTERVAL) {
                return;
            }
            
            lastCuringUpdateTime = currentTime;
            
            executor.execute(() -> {
                try {
                    // Use the most recent readings for calculation
                    List<SensorReading> recentReadings = chartData.size() > 10 ? 
                        chartData.subList(chartData.size() - 10, chartData.size()) : chartData;
                    
                    if (recentReadings.size() < 2) return;
                    
                    // Calculate total maturity from recent readings using ASTM C1074
                    float totalMaturity = MaturityCalculator.calculateMaturity(recentReadings);
                    
                    Log.d(TAG, "=== MATURITY CALCULATION DEBUG ===");
                    Log.d(TAG, "Recent readings count: " + recentReadings.size());
                    Log.d(TAG, "Calculated total maturity: " + totalMaturity + " °C·h");
                    Log.d(TAG, "Last calculated maturity: " + lastCalculatedMaturity + " °C·h");
                    Log.d(TAG, "Difference: " + Math.abs(totalMaturity - lastCalculatedMaturity));
                    
                    // Always update the maturity (remove threshold for debugging)
                    activeSession.setCurrentMaturity(totalMaturity);
                    curingSessionDao.updateMaturity(activeSession.getId(), totalMaturity);
                    lastCalculatedMaturity = totalMaturity;
                    
                    Log.d(TAG, "Active session current maturity: " + activeSession.getCurrentMaturity() + " °C·h");
                    Log.d(TAG, "Active session target maturity: " + activeSession.getTargetMaturity() + " °C·h");
                    Log.d(TAG, "Session completion percentage: " + activeSession.getCompletionPercentage() + "%");
                    
                    mainHandler.post(() -> updateCuringUI());
                    Log.d(TAG, "Curing progress updated: " + totalMaturity + " °C·h maturity");
                    Log.d(TAG, "=== END MATURITY DEBUG ===");
                } catch (Exception e) {
                    Log.e(TAG, "Error updating curing progress: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in updateCuringProgress: " + e.getMessage(), e);
        }
    }
    
    /**
     * Show completion notification
     */
    private void showCompletionNotification() {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play_circle)
                .setContentTitle("Curing Progress Update")
                .setContentText("Concrete has reached 80% maturity!")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Error showing notification: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create notification channel
     */
    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String name = "Curing Notifications";
                String description = "Notifications for concrete curing progress";
                int importance = NotificationManager.IMPORTANCE_DEFAULT;
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
                channel.setDescription(description);
                
                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                notificationManager.createNotificationChannel(channel);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification channel: " + e.getMessage(), e);
        }
    }
    
    // BLE Callback Implementations
    @Override
    public void onDeviceConnected(String deviceName, String deviceAddress) {
        runOnUiThread(() -> {
            try {
            Log.d(TAG, "Device connected: " + deviceName + " (" + deviceAddress + ")");
            updateConnectionStatus(true, "Connected to " + deviceName);
            
            // Reset data flow detection
            hasReceivedData = false;
            lastDataReceivedTime = 0;
            
            // Keep start curing button disabled until we receive data
            if (binding.startCuringButton != null) {
                binding.startCuringButton.setEnabled(false);
                binding.startCuringButton.setText("Waiting for Data...");
            }
            
            // Start 2-second chart update interval
            startChartUpdateInterval();
            
            Toast.makeText(this, "Connected to " + deviceName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error in onDeviceConnected: " + e.getMessage(), e);
            }
        });
    }
    
    @Override
    public void onDeviceDisconnected() {
        runOnUiThread(() -> {
            try {
            Log.d(TAG, "onDeviceDisconnected callback triggered");
            updateConnectionStatus(false, "Disconnected");
            
            // Reset data flow status and disable start curing button
            hasReceivedData = false;
            lastDataReceivedTime = 0;
            
            if (binding.startCuringButton != null) {
                binding.startCuringButton.setEnabled(false);
                binding.startCuringButton.setText("Connect ESP32 First");
            }
            
            // Stop chart update interval
            stopChartUpdateInterval();
            
            Toast.makeText(this, "Disconnected from ESP32", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error in onDeviceDisconnected: " + e.getMessage(), e);
            }
        });
    }
    
    @Override
    public void onTemperatureReceived(float temperature) {
        runOnUiThread(() -> {
            Log.d(TAG, "Temperature received via callback: " + temperature + "°C");
            // This method is called by individual temperature callbacks
            // The main parsing is now handled in onRawDataReceived
            if (lastReading == null) {
                lastReading = new SensorReading();
            }
            lastReading.setTemperature(temperature);
            
            // Ensure unique timestamp
            long newTimestamp = System.currentTimeMillis();
            if (newTimestamp <= lastReading.getTimestamp()) {
                newTimestamp = lastReading.getTimestamp() + 1;
            }
            lastReading.setTimestamp(newTimestamp);
            updateTemperatureDisplay(lastReading);
        });
    }
    
    @Override
    public void onHumidityReceived(float humidity) {
        runOnUiThread(() -> {
            Log.d(TAG, "Humidity received via callback: " + humidity + "%");
            // This method is called by individual humidity callbacks
            // The main parsing is now handled in onRawDataReceived
            if (lastReading == null) {
                lastReading = new SensorReading();
            }
            lastReading.setHumidity(humidity);
            
            // Ensure unique timestamp
            long newTimestamp = System.currentTimeMillis();
            if (newTimestamp <= lastReading.getTimestamp()) {
                newTimestamp = lastReading.getTimestamp() + 1;
            }
            lastReading.setTimestamp(newTimestamp);
            updateHumidityDisplay(lastReading);
        });
    }
    
    @Override
    public void onRawDataReceived(String data) {
        runOnUiThread(() -> {
            try {
            Log.d(TAG, "Raw data received: " + data);
                
                // Validate data
                if (data == null || data.trim().isEmpty()) {
                    Log.w(TAG, "Received null or empty data");
                    return;
                }
                
            Log.d(TAG, "Data length: " + data.length());
            
                // Handle new ESP32 format: "T:24.1", "H:59.6", "B:82"
                if (data.startsWith("T:")) {
                    // Parse temperature
                    String tempStr = data.substring(2).trim();
                    if (tempStr.isEmpty()) {
                        Log.w(TAG, "Empty temperature string");
                        return;
                    }
                    
                    float temperature = Float.parseFloat(tempStr);
                    Log.d(TAG, "Temperature received: " + temperature + "°C");
                    
                    if (lastReading == null) {
                        lastReading = new SensorReading();
                    }
                    lastReading.setTemperature(temperature);
                    
                    // Ensure unique timestamp
                    long newTimestamp = System.currentTimeMillis();
                    if (newTimestamp <= lastReading.getTimestamp()) {
                        newTimestamp = lastReading.getTimestamp() + 1;
                    }
                    lastReading.setTimestamp(newTimestamp);
                    updateTemperatureDisplay(lastReading);
                    
                    // Process the data for chart and curing calculations
                    processNewData(lastReading);
                    
                } else if (data.startsWith("H:")) {
                    // Parse humidity
                    String humStr = data.substring(2).trim();
                    if (humStr.isEmpty()) {
                        Log.w(TAG, "Empty humidity string");
                        return;
                    }
                    
                    float humidity = Float.parseFloat(humStr);
                    Log.d(TAG, "Humidity received: " + humidity + "%");
                    
                    if (lastReading == null) {
                        lastReading = new SensorReading();
                    }
                    lastReading.setHumidity(humidity);
                    
                    // Ensure unique timestamp
                    long newTimestamp = System.currentTimeMillis();
                    if (newTimestamp <= lastReading.getTimestamp()) {
                        newTimestamp = lastReading.getTimestamp() + 1;
                    }
                    lastReading.setTimestamp(newTimestamp);
                    updateHumidityDisplay(lastReading);
                    
                    // Process the data for chart and curing calculations
                    processNewData(lastReading);
                    
                } else if (data.startsWith("B:")) {
                    // Parse battery
                    String batteryStr = data.substring(2).trim();
                    if (batteryStr.isEmpty()) {
                        Log.w(TAG, "Empty battery string");
                        return;
                    }
                    
                    int battery = Integer.parseInt(batteryStr);
                    batteryLevel = battery;
                    Log.d(TAG, "Battery received: " + battery + "%");
                    updateBatteryDisplay(batteryLevel);
                    
                } else {
                    Log.d(TAG, "Unknown data format: " + data);
                }
                
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing sensor data: " + data, e);
            } catch (StringIndexOutOfBoundsException e) {
                Log.e(TAG, "Error parsing data format: " + data, e);
            } catch (Exception e) {
                Log.e(TAG, "Error processing data: " + data, e);
            }
        });
    }
    
    
    @Override
    public void onConnectionStatusChanged(boolean isConnected, String status) {
        runOnUiThread(() -> {
            try {
            Log.d(TAG, "onConnectionStatusChanged callback triggered: " + isConnected + " - " + status);
            updateConnectionStatus(isConnected, status);
            } catch (Exception e) {
                Log.e(TAG, "Error in onConnectionStatusChanged: " + e.getMessage(), e);
            }
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        try {
            // Check if BLE manager is properly initialized
            if (bleManager == null) {
                Log.e(TAG, "BLE Manager is null in onResume");
                return;
            }
            
            // Safely check connection status
            boolean isConnected = false;
            String deviceName = "";
            try {
                isConnected = bleManager.isConnected();
                deviceName = bleManager.getConnectedDeviceName();
                if (deviceName == null) {
                    deviceName = "";
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking BLE connection status: " + e.getMessage(), e);
                isConnected = false;
                deviceName = "";
            }
            
            String status = isConnected ? "Connected to " + deviceName : "Disconnected";
            updateConnectionStatus(isConnected, status);
            
            Log.d(TAG, "onResume - Connection status updated: " + status);
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
            // Set to disconnected state as fallback
            updateConnectionStatus(false, "Disconnected");
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            // Stop chart update interval
            stopChartUpdateInterval();
            
            if (executor != null) {
                executor.shutdown();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage(), e);
        }
    }
    
    /**
     * Setup permission request launcher
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
                        Log.d(TAG, "All permissions granted");
                        Toast.makeText(this, "Permissions granted! You can now use BLE features.", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.d(TAG, "Some permissions were denied");
                        Toast.makeText(this, "Some permissions were denied. BLE features may not work properly.", Toast.LENGTH_LONG).show();
                    }
                }
        );
    }
    
    /**
     * Check permissions after app startup (non-blocking)
     */
    private void checkPermissionsAfterStartup() {
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "Requesting BLE permissions");
            requestPermissions();
        } else {
            Log.d(TAG, "All permissions already granted");
        }
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
     * Check if all required permissions are granted
     */
    private boolean hasRequiredPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * Safely reset chart to prevent crashes
     */
    private void resetChart() {
        try {
            if (binding.trendChart != null) {
                binding.trendChart.clear();
                binding.trendChart.setData(new LineData());
                
                // Reset axis ranges to defaults
                XAxis xAxis = binding.trendChart.getXAxis();
                xAxis.setAxisMinimum(0f);
                xAxis.setAxisMaximum(30f); // Default to show 30 seconds (15 data points)
                
                YAxis leftAxis = binding.trendChart.getAxisLeft();
                leftAxis.setAxisMinimum(0f);
                leftAxis.setAxisMaximum(100f);
                
                binding.trendChart.invalidate();
                Log.d(TAG, "Chart reset successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resetting chart: " + e.getMessage(), e);
        }
    }
    
    /**
     * Custom time interval formatter for chart X-axis (2-second intervals)
     */
    private class TimeIntervalValueFormatter extends ValueFormatter {
        @Override
        public String getFormattedValue(float value) {
            // Show time in seconds (0s, 2s, 4s, 6s...)
            return String.format(Locale.getDefault(), "%.0fs", value);
        }
    }
    
    /**
     * Custom data point formatter for chart X-axis (legacy)
     */
    private class DataPointValueFormatter extends ValueFormatter {
        @Override
        public String getFormattedValue(float value) {
            // Simply show the data point number
            return String.format(Locale.getDefault(), "%.0f", value);
        }
    }
    
    /**
     * Custom timestamp formatter for chart X-axis (legacy)
     */
    private class TimestampValueFormatter extends ValueFormatter {
        @Override
        public String getFormattedValue(float value) {
            // Convert seconds to milliseconds for Date formatting
            long timestampMs = (long) (value * 1000);
            Date date = new Date(timestampMs);
            
            // Format as HH:mm:ss
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            return sdf.format(date);
        }
    }
    
    /**
     * Custom time formatter for chart X-axis (legacy - for time differences)
     */
    private class TimeValueFormatter extends ValueFormatter {
        @Override
        public String getFormattedValue(float value) {
            int minutes = (int) value;
            int hours = minutes / 60;
            int mins = minutes % 60;
            return String.format(Locale.getDefault(), "%d:%02d", hours, mins);
        }
    }
}