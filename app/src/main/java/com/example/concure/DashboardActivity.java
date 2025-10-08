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
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.concure.data.AppDatabase;
import com.example.concure.data.CuringSession;
import com.example.concure.data.CuringSessionDao;
import com.example.concure.data.SensorReading;
import com.example.concure.data.SensorReadingDao;
import com.example.concure.databinding.ActivityDashboardBinding;
import com.example.concure.prediction.MaturityCalculator;
import com.github.mikephil.charting.charts.LineChart;
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
            
            // Initialize temperature and humidity displays
            updateTemperatureDisplay(null);
            updateHumidityDisplay(null);
            
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
            
            // Configure Y-axis
            YAxis leftAxis = chart.getAxisLeft();
            leftAxis.setDrawGridLines(true);
            leftAxis.setAxisMinimum(0f);
            leftAxis.setAxisMaximum(100f);
            
            YAxis rightAxis = chart.getAxisRight();
            rightAxis.setEnabled(false);
            
            // Create data sets
            temperatureDataSet = new LineDataSet(null, "Temperature (°C)");
            temperatureDataSet.setColor(Color.parseColor("#1976D2"));
            temperatureDataSet.setCircleColor(Color.parseColor("#1976D2"));
            temperatureDataSet.setLineWidth(3f);
            temperatureDataSet.setCircleRadius(4f);
            temperatureDataSet.setDrawValues(false);
            temperatureDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            
            humidityDataSet = new LineDataSet(null, "Humidity (%)");
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
            
            // Animate humidity value
            if (binding.humidityValue != null) {
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
            float currentValue = 0;
            
            try {
                currentValue = Float.parseFloat(currentText.replaceAll("[^\\d.-]", ""));
            } catch (NumberFormatException e) {
                // If parsing fails, start from 0
            }
            
            android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(currentValue, targetValue);
            animator.setDuration(500);
            animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                textView.setText(String.format(Locale.getDefault(), "%.1f%s", value, suffix));
            });
            animator.start();
        } catch (Exception e) {
            Log.e(TAG, "Error in animateValue: " + e.getMessage(), e);
        }
    }
    
    /**
     * Update the real-time chart
     */
    private void updateChart() {
        try {
            if (chartData.isEmpty() || binding.trendChart == null) return;
            
            // Clear existing data
            if (temperatureDataSet != null) temperatureDataSet.clear();
            if (humidityDataSet != null) humidityDataSet.clear();
            
            // Add new data points
            for (int i = 0; i < chartData.size(); i++) {
                SensorReading reading = chartData.get(i);
                float time = (reading.getTimestamp() - chartData.get(0).getTimestamp()) / (1000f * 60f); // Minutes
                
                if (temperatureDataSet != null) {
                    temperatureDataSet.addEntry(new Entry(time, reading.getTemperature()));
                }
                if (humidityDataSet != null) {
                    humidityDataSet.addEntry(new Entry(time, reading.getHumidity()));
                }
            }
            
            // Notify chart of data change
            if (lineData != null) {
                lineData.notifyDataChanged();
                binding.trendChart.notifyDataSetChanged();
                binding.trendChart.invalidate();
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
                    session.setTargetMaturity(1000f); // Example target
                    session.setCurrentMaturity(0f);
                    session.setActive(true);
                    
                    long sessionId = curingSessionDao.insert(session);
                    session.setId(sessionId);
                    
                    mainHandler.post(() -> {
                        activeSession = session;
                        updateCuringUI();
                        Toast.makeText(this, "Curing process started", Toast.LENGTH_SHORT).show();
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
            if (activeSession == null) {
                if (binding.progressSection != null) {
                    binding.progressSection.setVisibility(View.GONE);
                }
                return;
            }
            
            if (binding.progressSection != null) {
                binding.progressSection.setVisibility(View.VISIBLE);
            }
            
            // Update progress
            float progress = activeSession.getCompletionPercentage();
            if (binding.curingProgressCircle != null) {
                binding.curingProgressCircle.setProgress((int) progress);
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
            
            // Update completion date
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
     * Update completion date prediction
     */
    private void updateCompletionDate() {
        try {
            if (activeSession == null || chartData.isEmpty()) return;
            
            // Get recent readings for prediction
            List<SensorReading> recentReadings = new ArrayList<>();
            for (int i = Math.max(0, chartData.size() - 10); i < chartData.size(); i++) {
                recentReadings.add(chartData.get(i));
            }
            
            // Calculate predicted completion time
            long hoursToCompletion = MaturityCalculator.estimateTimeToCompletion(
                activeSession.getCurrentMaturity(), 
                activeSession.getTargetMaturity(), 
                recentReadings
            );
            
            if (hoursToCompletion > 0) {
                long completionTime = System.currentTimeMillis() + (hoursToCompletion * 60 * 60 * 1000);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
                String dateStr = sdf.format(new Date(completionTime));
                if (binding.completionDateText != null) {
                    binding.completionDateText.setText(dateStr);
                }
            } else {
                if (binding.completionDateText != null) {
                    binding.completionDateText.setText("Calculating...");
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
            if (reading.getTimestamp() == 0) {
                reading.setTimestamp(System.currentTimeMillis());
            }
            
            lastReading = reading;
            
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
                }
            }
            
            if (!isDuplicate) {
                chartData.add(reading);
            }
            
            // Update chart
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
     * Update curing progress based on new data
     */
    private void updateCuringProgress(SensorReading reading) {
        try {
            if (activeSession == null || lastReading == null) return;
            
            executor.execute(() -> {
                try {
                    SensorReading previous = chartData.size() > 1 ? chartData.get(chartData.size() - 2) : lastReading;
                    float maturityIncrement = MaturityCalculator.calculateMaturityIncrement(previous, reading);
                    
                    // Update session maturity
                    float newMaturity = activeSession.getCurrentMaturity() + maturityIncrement;
                    activeSession.setCurrentMaturity(newMaturity);
                    curingSessionDao.updateMaturity(activeSession.getId(), newMaturity);
                    
                    mainHandler.post(() -> updateCuringUI());
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
            Log.d(TAG, "Temperature received: " + temperature + "°C");
            SensorReading reading = lastReading != null ? lastReading : new SensorReading();
            reading.setTemperature(temperature);
            reading.setCuringActive(activeSession != null);
            processNewData(reading);
        });
    }
    
    @Override
    public void onHumidityReceived(float humidity) {
        runOnUiThread(() -> {
            Log.d(TAG, "Humidity received: " + humidity + "%");
            SensorReading reading = lastReading != null ? lastReading : new SensorReading();
            reading.setHumidity(humidity);
            reading.setCuringActive(activeSession != null);
            processNewData(reading);
        });
    }
    
    @Override
    public void onRawDataReceived(String data) {
        runOnUiThread(() -> {
            Log.d(TAG, "Raw data received: " + data);
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