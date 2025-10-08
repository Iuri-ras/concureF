package com.example.concure;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
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
    
    // Data
    private List<SensorReading> chartData;
    private CuringSession activeSession;
    private SensorReading lastReading;
    private int batteryLevel = -1; // -1 means unknown/disconnected
    
    // Performance optimization for curing calculations
    private long lastCuringUpdateTime = 0;
    private static final long CURING_UPDATE_INTERVAL = 5000; // 5 seconds
    private float lastCalculatedMaturity = 0f;
    
    // Chart components
    private LineDataSet temperatureDataSet;
    private LineDataSet humidityDataSet;
    private LineData lineData;
    
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
            database = AppDatabase.getDatabase(this);
            sensorReadingDao = database.sensorReadingDao();
            curingSessionDao = database.curingSessionDao();
            
            // Threading
            executor = Executors.newSingleThreadExecutor();
            mainHandler = new Handler(Looper.getMainLooper());
            
            // Data
            chartData = new ArrayList<>();
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
            
            // Add sample data for testing chart
            addSampleChartData();
            
            // Test battery color changes
            testBatteryColors();
            
            // Start a test curing session
            startTestCuringSession();
            
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
            
            // Configure chart appearance
            chart.setBackgroundColor(Color.WHITE);
            chart.getDescription().setEnabled(false);
            chart.setTouchEnabled(true);
            chart.setDragEnabled(true);
            chart.setScaleEnabled(true);
            chart.setPinchZoom(true);
            chart.setDrawGridBackground(false);
            
            // Configure X-axis
            XAxis xAxis = chart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setDrawGridLines(false);
            xAxis.setValueFormatter(new TimeValueFormatter());
            
            // Configure Y-axis with auto-scaling
            YAxis leftAxis = chart.getAxisLeft();
            leftAxis.setDrawGridLines(true);
            leftAxis.setAxisMinimum(0f);
            leftAxis.setAxisMaximum(100f);
            leftAxis.setGranularity(5f);
            leftAxis.setLabelCount(6, true);
            leftAxis.setSpaceTop(10f);
            leftAxis.setSpaceBottom(10f);
            
            YAxis rightAxis = chart.getAxisRight();
            rightAxis.setEnabled(false);
            
            // Create data sets with empty lists initially
            temperatureDataSet = new LineDataSet(new ArrayList<Entry>(), "Temperature (°C)");
            temperatureDataSet.setColor(Color.parseColor("#1976D2"));
            temperatureDataSet.setCircleColor(Color.parseColor("#1976D2"));
            temperatureDataSet.setLineWidth(3f);
            temperatureDataSet.setCircleRadius(4f);
            temperatureDataSet.setDrawValues(false);
            temperatureDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            
            humidityDataSet = new LineDataSet(new ArrayList<Entry>(), "Humidity (%)");
            humidityDataSet.setColor(Color.parseColor("#00796B"));
            humidityDataSet.setCircleColor(Color.parseColor("#00796B"));
            humidityDataSet.setLineWidth(3f);
            humidityDataSet.setCircleRadius(4f);
            humidityDataSet.setDrawValues(false);
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
                        chartData.clear();
                        chartData.addAll(recentReadings);
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
            
            // Create a test curing session with 7-day target
            CuringSession testSession = new CuringSession();
            testSession.setStartTimestamp(System.currentTimeMillis());
            testSession.setTargetMaturity(MaturityCalculator.get7DayTargetMaturity());
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
            
            // Simulate 24 hours of curing at 25°C average temperature
            // At 25°C with datum temperature of 10°C: (25-10) * 24 = 360 degree-hours
            float simulatedMaturity = 360.0f;
            activeSession.setCurrentMaturity(simulatedMaturity);
            
            float progress = MaturityCalculator.calculateCompletionPercentage(
                simulatedMaturity, activeSession.getTargetMaturity());
            
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
     * Update the real-time chart
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
            
            Log.d(TAG, "Updating chart with " + chartData.size() + " data points");
            
            // Create new data sets instead of clearing existing ones
            List<Entry> tempEntries = new ArrayList<>();
            List<Entry> humEntries = new ArrayList<>();
            
            // Add new data points
            for (int i = 0; i < chartData.size(); i++) {
                SensorReading reading = chartData.get(i);
                float time = (reading.getTimestamp() - chartData.get(0).getTimestamp()) / (1000f * 60f); // Minutes
                
                tempEntries.add(new Entry(time, reading.getTemperature()));
                humEntries.add(new Entry(time, reading.getHumidity()));
            }
            
            // Update data sets
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
            
            // Auto-scale Y-axis based on data range
            if (!tempEntries.isEmpty() || !humEntries.isEmpty()) {
                float minValue = Float.MAX_VALUE;
                float maxValue = Float.MIN_VALUE;
                
                // Find min/max from both temperature and humidity data
                for (Entry entry : tempEntries) {
                    minValue = Math.min(minValue, entry.getY());
                    maxValue = Math.max(maxValue, entry.getY());
                }
                for (Entry entry : humEntries) {
                    minValue = Math.min(minValue, entry.getY());
                    maxValue = Math.max(maxValue, entry.getY());
                }
                
                // Add some padding to the range
                float range = maxValue - minValue;
                float padding = range * 0.1f; // 10% padding
                minValue = Math.max(0, minValue - padding);
                maxValue = maxValue + padding;
                
                // Update Y-axis range
                YAxis leftAxis = binding.trendChart.getAxisLeft();
                leftAxis.setAxisMinimum(minValue);
                leftAxis.setAxisMaximum(maxValue);
                
                Log.d(TAG, "Y-axis auto-scaled to range: " + minValue + " - " + maxValue);
            }
            
            // Notify chart of data change
            if (lineData != null) {
                lineData.notifyDataChanged();
                binding.trendChart.notifyDataSetChanged();
                binding.trendChart.invalidate();
                Log.d(TAG, "Chart invalidated and data changed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in updateChart: " + e.getMessage(), e);
        }
    }
    
    /**
     * Start the curing process
     */
    private void startCuringProcess() {
        try {
            executor.execute(() -> {
                try {
                    CuringSession session = new CuringSession();
                    session.setStartTimestamp(System.currentTimeMillis());
                    // Use 7-day target for faster testing (300 degree-hours)
                    session.setTargetMaturity(MaturityCalculator.get7DayTargetMaturity());
                    session.setCurrentMaturity(0f);
                    session.setActive(true);
                    
                    long sessionId = curingSessionDao.insert(session);
                    session.setId(sessionId);
                    
                    mainHandler.post(() -> {
                        activeSession = session;
                        updateCuringUI();
                        Toast.makeText(this, "Curing process started (7-day target)", Toast.LENGTH_SHORT).show();
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
            
            Log.d(TAG, "Active session details:");
            Log.d(TAG, "- Current maturity: " + activeSession.getCurrentMaturity());
            Log.d(TAG, "- Target maturity: " + activeSession.getTargetMaturity());
            Log.d(TAG, "- Active: " + activeSession.isActive());
            
            if (binding.progressSection != null) {
                binding.progressSection.setVisibility(View.VISIBLE);
                Log.d(TAG, "Progress section made visible");
            } else {
                Log.e(TAG, "Progress section is null!");
            }
            
            // Update progress with smooth animation
            float progress = activeSession.getCompletionPercentage();
            Log.d(TAG, "Calculated progress: " + progress + "%");
            
            if (binding.curingProgressCircle != null) {
                Log.d(TAG, "Updating progress circle to: " + (int) progress);
                animateProgress(binding.curingProgressCircle, (int) progress);
            } else {
                Log.e(TAG, "Progress circle is null!");
            }
            
            // Update progress text
            if (binding.curingProgressText != null) {
                binding.curingProgressText.setText(String.format(Locale.getDefault(), "%.1f%%", progress));
            }
            
            // Update maturity values
            if (binding.currentMaturityText != null) {
                binding.currentMaturityText.setText(String.format(Locale.getDefault(), "%.1f", activeSession.getCurrentMaturity()));
            }
            if (binding.targetMaturityText != null) {
                binding.targetMaturityText.setText(String.format(Locale.getDefault(), "%.1f", activeSession.getTargetMaturity()));
            }
            
            // Update completion date with realistic calculation
            updateCompletionDate();
            
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
                // Use realistic time estimation based on average temperature
                float daysToCompletion = MaturityCalculator.estimateDaysToCompletion(
                    activeSession.getCurrentMaturity(), 
                    activeSession.getTargetMaturity(), 
                    avgTemperature
                );
                
                if (daysToCompletion > 0) {
                    // Calculate completion date
                    long completionTime = System.currentTimeMillis() + (long)(daysToCompletion * 24 * 60 * 60 * 1000);
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
                    String dateStr = sdf.format(new Date(completionTime));
                    
                    if (binding.completionDateText != null) {
                        binding.completionDateText.setText(dateStr);
                    }
                    
                    Log.d(TAG, "Completion date calculated: " + dateStr + 
                          " (" + daysToCompletion + " days at " + avgTemperature + "°C)");
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
     * Process new sensor data
     */
    private void processNewData(SensorReading reading) {
        try {
            Log.d(TAG, "=== PROCESSING NEW DATA ===");
            
            if (reading.getTimestamp() == 0) {
                reading.setTimestamp(System.currentTimeMillis());
            }
            
            lastReading = reading;
            
            Log.d(TAG, "Processing new data - Temp: " + reading.getTemperature() + 
                  "°C, Hum: " + reading.getHumidity() + "%, Time: " + reading.getTimestamp());
            
            // Update displays
            updateTemperatureDisplay(reading);
            updateHumidityDisplay(reading);
            
            // Check for duplicate data (within 1 second)
            boolean isDuplicate = false;
            if (!chartData.isEmpty()) {
                SensorReading lastChartReading = chartData.get(chartData.size() - 1);
                if (Math.abs(reading.getTimestamp() - lastChartReading.getTimestamp()) < 1000) {
                    // Replace last entry
                    isDuplicate = true;
                    chartData.set(chartData.size() - 1, reading);
                    Log.d(TAG, "Replaced duplicate data point");
                }
            }
            
            if (!isDuplicate) {
                chartData.add(reading);
                Log.d(TAG, "Added new data point. Total points: " + chartData.size());
                Log.d(TAG, "Chart data now contains " + chartData.size() + " readings");
            } else {
                Log.d(TAG, "Duplicate data point replaced. Total points: " + chartData.size());
            }
            
            // Update chart
            Log.d(TAG, "Calling updateChart() with " + chartData.size() + " data points");
            updateChart();
            
            // Save to database
            executor.execute(() -> {
                try {
                    sensorReadingDao.insert(reading);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving sensor reading: " + e.getMessage(), e);
                }
            });
            
            // Update curing progress if active
            if (activeSession != null) {
                updateCuringProgress(reading);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in processNewData: " + e.getMessage(), e);
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
                    
                    // Calculate total maturity from recent readings
                    float totalMaturity = MaturityCalculator.calculateMaturity(recentReadings);
                    
                    // Only update if there's a significant change (avoid micro-updates)
                    if (Math.abs(totalMaturity - lastCalculatedMaturity) > 1.0f) {
                        activeSession.setCurrentMaturity(totalMaturity);
                        curingSessionDao.updateMaturity(activeSession.getId(), totalMaturity);
                        lastCalculatedMaturity = totalMaturity;
                        
                        mainHandler.post(() -> updateCuringUI());
                        Log.d(TAG, "Curing progress updated: " + totalMaturity + " maturity points");
                    }
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
            Log.d(TAG, "Device connected: " + deviceName + " (" + deviceAddress + ")");
            updateConnectionStatus(true, "Connected to " + deviceName);
            Toast.makeText(this, "Connected to " + deviceName, Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onDeviceDisconnected() {
        runOnUiThread(() -> {
            Log.d(TAG, "onDeviceDisconnected callback triggered");
            updateConnectionStatus(false, "Disconnected");
            Toast.makeText(this, "Disconnected from ESP32", Toast.LENGTH_SHORT).show();
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
            lastReading.setTimestamp(System.currentTimeMillis());
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
            lastReading.setTimestamp(System.currentTimeMillis());
            updateHumidityDisplay(lastReading);
        });
    }
    
    @Override
    public void onRawDataReceived(String data) {
        runOnUiThread(() -> {
            Log.d(TAG, "Raw data received: " + data);
            Log.d(TAG, "Data length: " + data.length());
            
            try {
                // Handle new ESP32 format: "T:24.1", "H:59.6", "B:82"
                if (data.startsWith("T:")) {
                    // Parse temperature
                    String tempStr = data.substring(2).trim();
                    float temperature = Float.parseFloat(tempStr);
                    Log.d(TAG, "Temperature received: " + temperature + "°C");
                    
                    if (lastReading == null) {
                        lastReading = new SensorReading();
                    }
                    lastReading.setTemperature(temperature);
                    lastReading.setTimestamp(System.currentTimeMillis());
                    updateTemperatureDisplay(lastReading);
                    
                    // Process the data for chart and curing calculations
                    processNewData(lastReading);
                    
                } else if (data.startsWith("H:")) {
                    // Parse humidity
                    String humStr = data.substring(2).trim();
                    float humidity = Float.parseFloat(humStr);
                    Log.d(TAG, "Humidity received: " + humidity + "%");
                    
                    if (lastReading == null) {
                        lastReading = new SensorReading();
                    }
                    lastReading.setHumidity(humidity);
                    lastReading.setTimestamp(System.currentTimeMillis());
                    updateHumidityDisplay(lastReading);
                    
                    // Process the data for chart and curing calculations
                    processNewData(lastReading);
                    
                } else if (data.startsWith("B:")) {
                    // Parse battery
                    String batteryStr = data.substring(2).trim();
                    int battery = Integer.parseInt(batteryStr);
                    batteryLevel = battery;
                    Log.d(TAG, "Battery received: " + battery + "%");
                    updateBatteryDisplay(batteryLevel);
                    
                } else {
                    Log.d(TAG, "Unknown data format: " + data);
                }
                
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing sensor data: " + data, e);
            } catch (Exception e) {
                Log.e(TAG, "Error processing data: " + data, e);
            }
        });
    }
    
    
    @Override
    public void onConnectionStatusChanged(boolean isConnected, String status) {
        runOnUiThread(() -> {
            Log.d(TAG, "onConnectionStatusChanged callback triggered: " + isConnected + " - " + status);
            updateConnectionStatus(isConnected, status);
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        try {
            boolean isConnected = bleManager.isConnected();
            String deviceName = bleManager.getConnectedDeviceName();
            String status = isConnected ? "Connected to " + deviceName : "Disconnected";
            updateConnectionStatus(isConnected, status);
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (executor != null) {
                executor.shutdown();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage(), e);
        }
    }
    
    /**
     * Custom time formatter for chart X-axis
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